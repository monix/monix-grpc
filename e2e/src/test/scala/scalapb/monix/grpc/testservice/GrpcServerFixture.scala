package scalapb.monix.grpc.testservice

import io.grpc
import com.typesafe.scalalogging.Logger
import scalapb.monix.grpc.testservice.utils.TestServer

import scala.util.Try
import scala.concurrent.blocking
import scala.concurrent.duration.FiniteDuration
import monix.eval.Task
import monix.execution.CancelableFuture
import cats.effect.Resource
import java.util.concurrent.TimeUnit
import scalapb.monix.grpc.testservice.utils.TestService
import monix.execution.Scheduler
import io.grpc.netty.NettyServerBuilder
import com.typesafe.scalalogging.LazyLogging
import io.grpc.inprocess.InProcessServerBuilder
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.CallOptions
import java.util.UUID

trait GrpcServerFixture {
  self: munit.Suite =>

  def clientFixture(
      port: Int,
      logger: Logger,
      inprocess: Boolean = false
  ): Fixture[TestServiceApi] = new Fixture[TestServiceApi]("server") {
    private val server: grpc.Server = TestServer.createServer(port, logger, inprocess)
    private var client: TestServiceApi = null
    private var channel: grpc.ManagedChannel = null

    def apply(): TestServiceApi = client

    override def beforeAll(): Unit = {
      server.start()
      val channelClient = TestServer.client(port, inprocess)
      channel = channelClient._1
      client = channelClient._2
    }

    override def afterAll(): Unit = {
      Try(channel.shutdown())
      Try(server.shutdown())
    }
  }
}

abstract class GrpcBaseSpec extends munit.FunSuite with LazyLogging {
  case class GrpcTestState(
      stub: TestServiceApi,
      grpcServer: grpc.Server,
      grpcChannel: grpc.ManagedChannel
  )

  private val defaultPort: Int = 8002
  private val testId = UUID.randomUUID().toString
  implicit val taskCtx = Task.defaultOptions.enableLocalContextPropagation

  def testGrpc[T](name: String)(
      body: GrpcTestState => Any
  )(implicit loc: munit.Location): Unit = {
    import monix.execution.Scheduler.Implicits.global
    val stateResource = serverResource(defaultPort).flatMap { server =>
      channelResource(defaultPort).map { channel =>
        val stub = TestServiceApi.stub(channel, CallOptions.DEFAULT)
        GrpcTestState(stub, server, channel)
      }
    }

    test(name) {
      val setupTimeout = FiniteDuration(3, TimeUnit.SECONDS)
      val totalTimeout = FiniteDuration(munitTimeout._1, munitTimeout._2)
      val minimumTimeout = setupTimeout + setupTimeout
      assert(totalTimeout >= minimumTimeout, s"Minimum allowed munit timeout is $minimumTimeout!")
      val testCaseTimeout = totalTimeout.-(setupTimeout)
      stateResource.use(state => toTestTask(body(state), testCaseTimeout)).runToFutureOpt
    }
  }

  private def serverResource(port: Int)(implicit
      scheduler: Scheduler
  ): Resource[Task, grpc.Server] = Resource {
    val service = TestServiceApi.bindService(new TestService(logger))
    //val server = NettyServerBuilder.forPort(port).addService(service).build()
    val server = InProcessServerBuilder.forName(testId).addService(service).build()
    val waitForTermination = Task(blocking(server.awaitTermination(1, TimeUnit.SECONDS))).void
      .onErrorHandle(logger.error(s"Timed out to close grpc server after 1s!", _))
    Task(server.start() -> Task(blocking(server.shutdownNow())).void.guarantee(waitForTermination))
  }

  private def channelResource(port: Int)(implicit
      scheduler: Scheduler
  ): Resource[Task, grpc.ManagedChannel] = Resource {
    Task {
      val channel = InProcessChannelBuilder.forName(testId).build()
      //val channel = NettyChannelBuilder.forAddress("localhost", port).usePlaintext().build()
      val closeChannel = Task(blocking(channel.shutdownNow())).void
        .guarantee(Task(blocking(channel.awaitTermination(1, TimeUnit.SECONDS))))
        .onErrorHandle(err => logger.error(s"Timed out to close grpc client after 1s!", err))
      channel -> closeChannel
    }
  }

  private def toTestTask[T](body: => Any, timeout: FiniteDuration): Task[Unit] = {
    val testTask = Task(body).flatMap {
      case testTask: Task[_] => testTask.void
      case f: CancelableFuture[_] => Task.fromFuture(f).void
      case value => Task.unit
    }
    testTask.timeout(timeout)
  }
}
