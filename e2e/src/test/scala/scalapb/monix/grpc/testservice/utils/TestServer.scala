package scalapb.monix.grpc.testservice.utils

import com.typesafe.scalalogging.LazyLogging
import io.grpc.netty.{NettyChannelBuilder, NettyServerBuilder}
import io.grpc._
import io.netty.channel.local.LocalServerChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.channel.{ChannelFactory, ServerChannel}
import monix.eval.Task
import monix.execution.Scheduler.global
import monix.reactive.Observable
import scalapb.monix.grpc.testservice.Request.Scenario
import scalapb.monix.grpc.testservice.{Request, Response, TestServiceGrpcService}

import java.time.Instant
import scala.concurrent.duration.{DurationInt, SECONDS}

class TestServer() extends TestServiceGrpcService[Metadata] with LazyLogging {

  override def unary(request: Request, ctx: Metadata): Task[Response] = {
    logger.info(s"unary: received $request")
    request.scenario match {
      case Scenario.OK => Task(Response(1))
      case Scenario.ERROR_NOW => Task.raiseError(SilentException())
      case Scenario.DELAY => Task.never
      case _ => Task.raiseError(new RuntimeException("TEST-FAIL"))
    }
  }

  override def serverStreaming(request: Request, ctx: Metadata): Observable[Response] = {
    logger.info(s"serverStreaming: received ${request.scenario}")
    val responseStream = request.scenario match {
      case Scenario.OK => Observable(Response(1), Response(2))
      case Scenario.ERROR_NOW => Observable.raiseError(SilentException())
      case Scenario.ERROR_AFTER =>
        Observable(Response(1), Response(2)) ++ Observable.raiseError(SilentException())
      case Scenario.BACK_PRESSURE =>
        Observable
          .unfold(bigResponse)(s =>
            Some(s -> s.copy(out = s.out + 1, timestamp = Instant.now().toEpochMilli))
          )
          .take(request.backPressureResponses)
      case Scenario.DELAY => Observable.never
      case _ => Observable(Response(1))
    }

    responseStream.doOnNext(r => Task.apply(logger.info(s"response: ${r.out} ${r.timestamp}")))
  }

  def bigResponse = Response(0, Instant.now().toEpochMilli, Array.fill(100000)(1))

  override def clientStreaming(request: Observable[Request], ctx: Metadata): Task[Response] = {
    request
      .doOnNext(r => Task.apply(logger.info(s"clientStreaming: received ${r.scenario}")))
      .scanEval(Task[Response](bigResponse)) { (previousResponse, req) =>
        req.scenario match {
          case Scenario.OK =>
            Task(Response(previousResponse.out + 1, Instant.now().toEpochMilli, Seq()))
          case Scenario.ERROR_NOW => Task.raiseError(SilentException())
          case Scenario.DELAY => Task.never
          case Scenario.SLOW =>
            Task(Response(previousResponse.out + 1, Instant.now().toEpochMilli, Seq()))
              .delayResult(10.milli)
          case _ => Task.raiseError(new RuntimeException("TEST-FAIL"))
        }
      }
      .lastOrElseL(bigResponse)
  }

  override def bidiStreaming(request: Observable[Request], ctx: Metadata): Observable[Response] = {
    request
      .doOnNext(r => Task.apply(logger.info(s"bidiStreaming: received ${r.scenario}")))
      .scanEval(Task(Observable(bigResponse))) { (previousResponse, req) =>
        req.scenario match {
          case Scenario.OK =>
            Task(
              previousResponse.map(r => Response(r.out + 1, Instant.now().toEpochMilli, r.bulk))
            )
          case Scenario.ERROR_NOW => Task.raiseError(SilentException())
          case Scenario.DELAY => Task.never
          case Scenario.SLOW =>
            Task
              .delay(
                previousResponse.map(r => Response(r.out + 1, Instant.now().toEpochMilli, Seq()))
              )
              .delayExecution(10.milli)
          case Scenario.BACK_PRESSURE =>
            Task(
              previousResponse.last.flatMap(r =>
                Observable
                  .unfold(r.out)(idx =>
                    Some(r.copy(out = idx, Instant.now().toEpochMilli) -> (idx + 1))
                  )
                  .take(req.backPressureResponses)
              )
            )
          case _ => Task.raiseError(new RuntimeException("TEST-FAIL"))
        }
      }
      .flatten
      .doOnNext(r => Task.apply(logger.info(s"response: ${r.out} ${r.timestamp}")))
  }

}

object TestServer {

  def createServer(port: Int): Server = {
    val server = new TestServer()
    NettyServerBuilder
      .forPort(port)
      .addService(TestServiceGrpcService.bindService(server)(global))
      .build()
  }

  def client(port: Int): (ManagedChannel, TestServiceGrpcService[Metadata]) = {
    val channel = NettyChannelBuilder
      .forAddress("localhost", port)
      .usePlaintext()
      .keepAliveTimeout(2, SECONDS)
      .build()
    (channel, TestServiceGrpcService.stub(channel, CallOptions.DEFAULT)(global))
  }
}
