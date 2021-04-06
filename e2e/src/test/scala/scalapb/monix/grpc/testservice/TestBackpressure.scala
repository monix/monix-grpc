package scalapb.monix.grpc.testservice

import com.typesafe.scalalogging.LazyLogging
import io.grpc.Metadata
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import monix.reactive.subjects.ConcurrentSubject
import monix.reactive.{MulticastStrategy, Observable}
import scalapb.monix.grpc.testservice.Request.Scenario

import java.time.Instant
import scala.concurrent.duration.DurationInt
import scala.util.Random

class TestBackpressure extends munit.FunSuite with GrpcServerFixture with LazyLogging {
  val stub = clientFixture(8002, logger, true)
  def request = Request(Scenario.OK, 1, Array.fill(10)(Random.nextDouble()))

  override def munitFixtures = List(stub)

  val requestCount = 100
  val slowTask = Task().delayResult(50.milli)

  def requests(scenario: Scenario) = Observable
    .unfold(1)(s => Some(s -> (s + 1)))
    .take(requestCount)
    .map(_ => request.copy(scenario = scenario))

  val expectedAverageResponseTime = 45

  test("bidi stream call backpressures on client side".tag(Slow)) {
    val client = stub()
    val subject = ConcurrentSubject[Instant](MulticastStrategy.replay)

    val requestStream = requests(Scenario.SLOW)
      .doOnNext(x => Task(subject.onNext(Instant.now())))

    client
      .bidiStreaming(requestStream, new Metadata())
      .doOnNext(m => Task(logger.debug(s"received response ${m.out}")))
      .completedL
      .runToFuture
      .onComplete(_ => subject.onComplete())

    subject
      .map(_.toEpochMilli)
      .toListL
      .map { events =>
        assert(clue(averageEventDuration(events)) >= clue(expectedAverageResponseTime))
      }
      .runToFuture
  }

  test("bidi stream call backpressures on server side".tag(Slow)) {
    val client = stub()

    client
      .bidiStreaming(
        requests(Scenario.BACK_PRESSURE),
        new Metadata()
      )
      .doOnNext(x => Task(logger.debug(s"received response ${x.out}")))
      .mapEval(r => slowTask.map(_ => r))
      .map(_.timestamp)
      .toListL
      .map { events =>
        assert(
          clue(averageEventDuration(events)) >= clue(expectedAverageResponseTime)
        )
      }
      .runToFuture
  }

  test("client stream call backpressures on client side".tag(Slow)) {
    val client = stub()
    val subject = ConcurrentSubject[Instant](MulticastStrategy.replay)

    val requestStream = requests(Scenario.SLOW)
      .doOnNext(_ => Task(subject.onNext(Instant.now())))

    client
      .clientStreaming(requestStream, new Metadata())
      .runToFuture
      .onComplete(_ => subject.onComplete())

    subject
      .map(_.toEpochMilli)
      .toListL
      .map { events =>
        assert(
          clue(averageEventDuration(events)) >= clue(expectedAverageResponseTime)
        )
      }
      .runToFuture
  }

  test("server stream call backpressures on server side".tag(Slow)) {
    val client = stub()
    client
      .serverStreaming(Request(Scenario.BACK_PRESSURE, requestCount), new Metadata())
      .doOnNext(x => Task(logger.info(s"received: ${x.out}")))
      .mapEval(r => slowTask.map(_ => r))
      .toListL
      .map { events =>
        assert(
          clue(averageEventDuration(events.map(_.timestamp))) >= clue(expectedAverageResponseTime)
        )
      }
      .runToFuture
  }

  def averageEventDuration(timestamps: Seq[Long], expectedResponses: Int = requestCount) = {
    assertEquals(timestamps.size, expectedResponses)
    timestamps
      .sliding(2)
      .map(timestamps => timestamps.last - timestamps.head)
      .sum / timestamps.size

  }
}
