package scalapb.monix.grpc.testservice

import cats.effect.{ExitCase, Resource}
import io.grpc
import io.grpc.inprocess.{InProcessChannelBuilder, InProcessServerBuilder}
import monix.eval.Task
import monix.execution.{CancelableFuture, CancelablePromise, Scheduler}
import monix.reactive.Observable
import monix.reactive.subjects.PublishSubject
import org.slf4j.{Logger, LoggerFactory}

import java.util.UUID
import java.util.concurrent.TimeUnit
import scala.concurrent.blocking
import scala.concurrent.duration.FiniteDuration
import monix.grpc.runtime.server.ServerCallOptions
import monix.grpc.runtime.server.PerMethodServerCallOptions

abstract class GrpcBaseSpec extends munit.FunSuite {
  private val logger: Logger = LoggerFactory.getLogger(this.getClass)
  final class GrpcTestState(
      val stub: TestServiceApi.Stub,
      private[this] val grpcServer: grpc.Server,
      private[this] val grpcChannel: grpc.ManagedChannel
  ) {

    def withClientStream[R](
        sendRequests: ClientStream[R] => Task[Unit]
    )(
        receiveResponses: Observable[R] => Task[Unit]
    ): Task[Unit] = {
      val subscribed = CancelablePromise[Unit]()
      val subject = PublishSubject[R]()
      val stream = new ClientStream[R](subject)

      val startSendingRequests = for {
        _ <- Task.fromCancelablePromise(subscribed)
        result <- sendRequests(stream).onErrorHandle(
          logger.error("Couldn't send request in client stream!", _)
        )
      } yield result

      startSendingRequests.start.flatMap { sendFiber =>
        val requests = subject.doAfterSubscribe(Task(subscribed.success(())).void)
        receiveResponses(requests).guaranteeCase {
          case ExitCase.Completed => sendFiber.join
          case ExitCase.Canceled => sendFiber.cancel
          case ExitCase.Error(_) => sendFiber.cancel
        }
      }
    }
  }

  final class ClientStream[T](underlying: PublishSubject[T]) {
    def onErrorL(err: Throwable): Task[Unit] =
      Task(underlying.onError(err))
    def onCompleteL: Task[Unit] =
      Task(underlying.onComplete())
    def onNextL(elem: T, randomMillis: Int): Task[Unit] = Task.defer {
      Task
        .sleep(randomDuration(randomMillis))
        .>>(Task.deferFuture(underlying.onNext(elem)).void)
    }
  }

  private val defaultPort: Int = 8002
  private val testId = UUID.randomUUID().toString

  implicit val scheduler: Scheduler = Scheduler.Implicits.global
  implicit val taskCtx: Task.Options = Task.defaultOptions.enableLocalContextPropagation

  def testGrpc[T](
      name: String
  )(body: GrpcTestState => Any)(implicit loc: munit.Location): Unit =
    testGrpc(name: munit.TestOptions, None)(body)

  def testGrpc[T](
      opts: munit.TestOptions
  )(body: GrpcTestState => Any)(implicit loc: munit.Location): Unit =
    testGrpc(opts, None)(body)

  def testGrpc[T](
      name: String,
      serverBufferSize: Option[Int]
  )(body: GrpcTestState => Any)(implicit loc: munit.Location): Unit =
    testGrpc(name: munit.TestOptions, serverBufferSize)(body)

  def testGrpc[T](
      opts: munit.TestOptions,
      serverBufferSize: Option[Int]
  )(body: GrpcTestState => Any)(implicit loc: munit.Location): Unit = {
    val stateResource = serverResource(defaultPort, serverBufferSize).flatMap { server =>
      channelResource(defaultPort).map { channel =>
        val stub = TestServiceApi.stub(channel)
        new GrpcTestState(stub, server, channel)
      }
    }

    test(opts) {
      val setupTimeout = FiniteDuration(3, TimeUnit.SECONDS)
      val totalTimeout = FiniteDuration(munitTimeout.toNanos, TimeUnit.NANOSECONDS)
      val minimumTimeout = setupTimeout + setupTimeout
      assert(totalTimeout >= minimumTimeout, s"Minimum allowed munit timeout is $minimumTimeout!")
      val testCaseTimeout = totalTimeout.-(setupTimeout)
      stateResource.use(state => toTestTask(body(state), testCaseTimeout)).runToFutureOpt
    }
  }

  private def serverResource(
      port: Int,
      bufferSize: Option[Int]
  )(implicit scheduler: Scheduler): Resource[Task, grpc.Server] = Resource {
    val perMethodOpts: PerMethodServerCallOptions = _ => Task {
      bufferSize match {
        case Some(bufferSize) => ServerCallOptions().withBufferSize(bufferSize)
        case None => ServerCallOptions()
      }
    }

    val service = TestServiceApi.service(new TestService(logger), perMethodOpts)
    val server = InProcessServerBuilder.forName(testId).addService(service).build()
    val waitForTermination = Task(blocking(server.awaitTermination(1, TimeUnit.SECONDS))).void
      .onErrorHandle(logger.error(s"Timed out to close grpc server after 1s!", _))
    Task(server.start() -> Task(blocking(server.shutdownNow())).void.guarantee(waitForTermination))
  }

  private def channelResource(
      port: Int
  )(implicit scheduler: Scheduler): Resource[Task, grpc.ManagedChannel] = Resource {
    Task {
      val channel = InProcessChannelBuilder.forName(testId).build()
      //val channel = NettyChannelBuilder.forAddress("localhost", port).usePlaintext().build()
      val closeChannel = Task(blocking(channel.shutdownNow())).void
        .guarantee(Task(blocking(channel.awaitTermination(1, TimeUnit.SECONDS))).void)
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

  private def randomDuration(untilMillis: Int): FiniteDuration = {
    val n = scala.math.max(untilMillis, 1)
    FiniteDuration(scala.util.Random.nextInt(n), TimeUnit.MILLISECONDS)
  }
}
