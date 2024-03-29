package scalapb.monix.grpc.testservice

import io.grpc._
import monix.eval.Task
import monix.reactive.Observable
import org.slf4j.Logger
import scalapb.monix.grpc.testservice.Request.Scenario

import java.time.Instant
import scala.concurrent.duration.{DurationInt, SECONDS}
import scala.util.Random

class TestService(logger: Logger) extends TestServiceApi {
  override def unary(request: Request, ctx: Metadata): Task[Response] = {
    request.scenario match {
      case Scenario.DELAY => Task.never
      case Scenario.OK => Task(Response(1))
      case Scenario.ERROR_NOW => Task.raiseError(SilentException())
      case _ => Task.raiseError(new RuntimeException("TEST-FAIL"))
    }
  }

  override def serverStreaming(request: Request, ctx: Metadata): Observable[Response] = {
    request.scenario match {
      case Scenario.DELAY => Observable.never
      case Scenario.OK => Observable(Response(1), Response(2))
      case Scenario.ERROR_NOW => Observable.raiseError(SilentException())

      case Scenario.ERROR_AFTER =>
        Observable(Response(1), Response(2)) ++ Observable.raiseError(SilentException())

      case Scenario.BACK_PRESSURE =>
        Observable.range(0, request.backPressureResponses).map(x => generateLargeResponse(x.toInt))

      case _ => Observable(Response(1))
    }
  }

  override def clientStreaming(requests: Observable[Request], ctx: Metadata): Task[Response] = {
    val initialResponse = generateLargeResponse(0)
    requests
      .scanEval(Task.now(initialResponse)) { (accResponse: Response, request: Request) =>
        request.scenario match {
          case Scenario.OK => Task(generateNextResponseFrom(accResponse))
          case Scenario.ERROR_NOW => Task.raiseError(SilentException())
          case Scenario.DELAY => Task.never
          case Scenario.SLOW =>
            Task(generateNextResponseFrom(accResponse)).delayResult(50.millis)

          case _ => Task.raiseError(new RuntimeException("TEST-FAIL"))
        }
      }
      .lastOrElseL(initialResponse)
  }

  override def bidiStreaming(requests: Observable[Request], ctx: Metadata): Observable[Response] = {
    val initialResponses = Observable.pure(generateLargeResponse(0))
    requests
      .scanEval(Task(initialResponses)) { (acc: Observable[Response], request: Request) =>
        request.scenario match {
          case Scenario.DELAY => Task.never
          case Scenario.ERROR_NOW => Task.raiseError(SilentException())
          case Scenario.OK => Task(acc.map(generateNextResponseFrom(_)))
          case Scenario.SLOW =>
            Task(acc.map(generateNextResponseFrom(_))).delayResult(50.millis)

          case Scenario.BACK_PRESSURE =>
            Task {
              acc.last.flatMap { lastResponse =>
                val startIdx = lastResponse.out + 1
                Observable
                  .range(startIdx, startIdx + request.backPressureResponses)
                  .map(idx => lastResponse.copy(out = idx.toInt))
                  .take(request.backPressureResponses)
              }
            }

          case _ => Task.raiseError(new RuntimeException("TEST-FAIL"))
        }
      }
      .flatten
      .map(_.copy(timestamp = Instant.now().toEpochMilli))
  }

  private def generateLargeResponse(id: Int): Response =
    Response(id, Instant.now().toEpochMilli, IndexedSeq.fill(10)(Random.nextDouble()))
  private def generateNextResponseFrom(response: Response): Response =
    response.copy(out = response.out + 1, timestamp = Instant.now.toEpochMilli())
}
