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

  testGrpc("bidirectional streaming call backpressures on client side".only, Some(32)) { state =>
    state.withClientStream(sendTestRequests(128, Scenario.SLOW, _)) { clientRequests0 =>
      val events = new mutable.ListBuffer[Long]()
      val clientRequests = clientRequests0
        .doOnNext(_ => Task(events.synchronized { events.+=(Instant.now().toEpochMilli())} ).void)

      state.stub.bidiStreaming(clientRequests).completedL.map { _ =>
        assertBackpressureFromTimestamps(128, events.synchronized { events.toList} )
      }
    }
  }

  testGrpc("bidirectional streaming call backpressures on server side") { state =>
    state.withClientStream(sendTestRequests(128, Scenario.BACK_PRESSURE, _)) { clientRequests =>
      state.stub
        .bidiStreaming(clientRequests)
        .mapEval(r => Task.unit.delayResult(50.millis).map(_ => r.timestamp))
        .toListL
        .map(assertBackpressureFromTimestamps(128, _))
    }
  }

  testGrpc("client streaming call backpressures on client side") { state =>
    state.withClientStream(sendTestRequests(128, Scenario.SLOW, _)) { clientRequests0 =>
      val events = new mutable.ListBuffer[Long]()
      val clientRequests = clientRequests0
        .doOnNext(_ => Task(events.+=(Instant.now().toEpochMilli())).void)

      state.stub.clientStreaming(clientRequests).map { _ =>
        assertBackpressureFromTimestamps(128, events.toList)
      }
    }
  }

  testGrpc("server streaming call backpressures on server side") { state =>
    state.stub
      .serverStreaming(Request(Scenario.BACK_PRESSURE, 128))
      .mapEval(r => Task.unit.delayResult(50.millis).map(_ => r.timestamp))
      .toListL
      .map(assertBackpressureFromTimestamps(128, _))
  }

  // Let's create a case where bidirectional calls send
  testGrpc("client pressure doesn't overwhelm server side".ignore) { state =>
    def sendNumberRequests(stream: ClientStream[Number]) =
      sendRequests(128 /*TestService.NumberOfStreamElements*/, stream)(generateNumRequest)

    state.withClientStream(sendNumberRequests) { clientRequests =>
      state.stub.withBufferSize(16).requestPressure(clientRequests).void //.map(println(_))
    }
  }

  def sendTestRequests(
      count: Int,
      scenario: Scenario,
      stream: ClientStream[Request]
  ): Task[Unit] = sendRequests(count, stream)(generateRequest(_, scenario))

  private def sendRequests[R](
      count: Int,
      stream: ClientStream[R]
  )(generateRequest: Int => R): Task[Unit] = Observable
    .range(1, count + 1)
    //.doOnNext(_ => Task(println("generating ")))
    .mapEval(i => stream.onNextL(generateRequest(i.toInt), 0))
    .completedL
    .guaranteeCase {
      case ExitCase.Completed => stream.onCompleteL
      case ExitCase.Error(err) => stream.onErrorL(err)
      case ExitCase.Canceled => stream.onErrorL(new CancellationException())
    }

  private def generateRequest(idx: Int, scenario: Scenario): Request =
    Request(scenario, idx, IndexedSeq.fill(10)(Random.nextDouble()))

  private def generateNumRequest(n: Int): Number =
    Number.of(Array.fill(1024 * 32)(n))

  private def assertBackpressureFromTimestamps(
      expectedCount: Int,
      obtainedTimestamps: Seq[Long]
  )(implicit loc: munit.Location): Unit = {
    assertEquals(obtainedTimestamps.size, expectedCount)
    val average = obtainedTimestamps.iterator
      .sliding(2)
      .map(timestamps => timestamps.last - timestamps.head)
      .sum / obtainedTimestamps.size

    val expectedAverageResponseTime = 45
    assert(clue(average) >= clue(expectedAverageResponseTime))
  }
}
