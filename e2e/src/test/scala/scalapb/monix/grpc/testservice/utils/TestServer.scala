package scalapb.monix.grpc.testservice.utils

import com.typesafe.scalalogging.LazyLogging
import io.grpc.netty.NettyChannelBuilder
import io.grpc._
import monix.eval.Task
import monix.execution.Scheduler.global
import monix.reactive.Observable
import scalapb.monix.grpc.testservice.Request.Scenario
import scalapb.monix.grpc.testservice.TestServiceGrpc.TestService
import scalapb.monix.grpc.testservice.{Request, Response, TestServiceApi}

import scala.concurrent.duration.SECONDS

class TestService() extends TestServiceApi[Metadata] with LazyLogging {

  override def unary(request: Request, ctx: Metadata): Task[Response] = {
    logger.info(s"unary: received $request")
    request.scenario match {
      case Scenario.OK => Task(Response("OK"))
      case Scenario.ERROR_NOW => Task.raiseError(SilentException())
      case Scenario.DELAY => Task.never
      case _ => Task.raiseError(new RuntimeException("TEST-FAIL"))
    }
  }

  override def serverStreaming(request: Request, ctx: Metadata): Observable[Response] = {
    logger.info(s"serverStreaming: received $request")
    request.scenario match {
      case Scenario.OK => Observable(Response("OK1"), Response("OK2"))
      case Scenario.ERROR_NOW => Observable.raiseError(SilentException())
      case Scenario.ERROR_AFTER =>
        Observable(Response("OK1"), Response("OK2")) ++ Observable.raiseError(SilentException())
      case Scenario.DELAY => Observable.never
      case _ => Observable(Response("OK"))
    }
  }

  override def clientStreaming(request: Observable[Request], ctx: Metadata): Task[Response] =
    request
      .doOnNext(r => Task.apply(logger.info(s"clientStreaming: received $r")))
      .scanEval(Task(0)) { (successCount, req) =>
        req.scenario match {
          case Scenario.OK => Task(successCount + 1)
          case Scenario.ERROR_NOW => Task.raiseError(SilentException())
          case Scenario.DELAY => Task.never
          case _ => Task.raiseError(new RuntimeException("TEST-FAIL"))
        }
      }
      .lastOrElseL(0)
      .map(count => Response(s"OK$count"))

  override def bidiStreaming(request: Observable[Request], ctx: Metadata): Observable[Response] = {
    request
      .doOnNext(r => Task.apply(logger.info(s"bidiStreaming: received $r")))
      .scanEval(Task(0)) { (successCount, req) =>
        req.scenario match {
          case Scenario.OK => Task(successCount + 1)
          case Scenario.ERROR_NOW => Task.raiseError(SilentException())
          case Scenario.DELAY => Task.never
          case _ => Task.raiseError(new RuntimeException("TEST-FAIL"))
        }
      }
      .map(count => Response(s"OK$count"))
  }

}

object TestServer {

  def createServer(port: Int): Server = {
    val server = new TestService()
    ServerBuilder
      .forPort(port)
      .addService(TestServiceApi.bindService(server)(global))
      .build()
  }

  def client(port: Int): (ManagedChannel, TestServiceApi[Metadata]) = {
    val channel = NettyChannelBuilder
      .forAddress("localhost", port)
      .usePlaintext()
      .keepAliveTimeout(2, SECONDS)
      .build()
    (channel, TestServiceApi.stub(channel, CallOptions.DEFAULT)(global))
  }
}
