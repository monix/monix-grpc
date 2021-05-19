package scalapb.monix.grpc.testservice

import io.grpc.Metadata
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import monix.reactive.subjects.ConcurrentSubject
import monix.reactive.{MulticastStrategy, Observable}
import scalapb.monix.grpc.testservice.Request.Scenario

import java.time.Instant
import scala.concurrent.duration.DurationInt
import scala.util.Random
import cats.effect.ExitCase
import java.util.concurrent.CancellationException
import monix.reactive.subjects.ReplaySubject
import scala.collection.mutable

class BackpressureSpec extends GrpcBaseSpec {

  testGrpc("bidirectional streaming call backpressures on client side") { state =>
    state.withClientStream(sendRequests(100, Scenario.SLOW, _)) { clientRequests0 =>
      val events = new mutable.ListBuffer[Long]()
      val clientRequests = clientRequests0
        .doOnNext(_ =>
          Task {
            events.+=(Instant.now().toEpochMilli())
            events
          }
        )

      state.stub.bidiStreaming(clientRequests).completedL.map { _ =>
        assertBackpressureFromTimestamps(100, events.toList)
      }
    }
  }

  testGrpc("bidirectional streaming call backpressures on server side") { state =>
    state.withClientStream(sendRequests(100, Scenario.BACK_PRESSURE, _)) { clientRequests =>
      state.stub
        .bidiStreaming(clientRequests)
        .mapEval(r => Task.unit.delayResult(50.millis).map(_ => r.timestamp))
        .toListL
        .map(assertBackpressureFromTimestamps(100, _))
    }
  }

  testGrpc("client streaming call backpressures on client side") { state =>
    state.withClientStream(sendRequests(100, Scenario.SLOW, _)) { clientRequests0 =>
      val events = new mutable.ListBuffer[Long]()
      val clientRequests = clientRequests0
        .doOnNext(_ => Task(events.+=(Instant.now().toEpochMilli())))

      state.stub.clientStreaming(clientRequests).map { _ =>
        assertBackpressureFromTimestamps(100, events.toList)
      }
    }
  }

  testGrpc("server streaming call backpressures on server side") { state =>
    state.stub
      .serverStreaming(Request(Scenario.BACK_PRESSURE, 100))
      .mapEval(r => Task.unit.delayResult(50.millis).map(_ => r.timestamp))
      .toListL
      .map(assertBackpressureFromTimestamps(100, _))
  }

  private def generateRequest(idx: Int, scenario: Scenario): Request =
    Request(scenario, idx, Array.fill(10)(Random.nextDouble()))
  private def sendRequests(
      count: Int,
      scenario: Scenario,
      stream: ClientStream[Request]
    ): Task[Unit] = Observable
    .range(1, count + 1)
    .mapEval(_ => stream.onNextL(generateRequest(1, scenario), 0))
    .completedL
    .guaranteeCase {
      case ExitCase.Completed => stream.onCompleteL
      case ExitCase.Error(err) => stream.onErrorL(err)
      case ExitCase.Canceled => stream.onErrorL(new CancellationException())
    }

  private def assertBackpressureFromTimestamps(
      expectedCount: Int,
      obtainedTimestamps: Seq[Long]
    )(
      implicit loc: munit.Location
    ): Unit = {
    assertEquals(obtainedTimestamps.size, expectedCount)
    val average = obtainedTimestamps.iterator
      .sliding(2)
      .map(timestamps => timestamps.last - timestamps.head)
      .sum / obtainedTimestamps.size

    val expectedAverageResponseTime = 45
    assert(clue(average) >= clue(expectedAverageResponseTime))
  }
}
