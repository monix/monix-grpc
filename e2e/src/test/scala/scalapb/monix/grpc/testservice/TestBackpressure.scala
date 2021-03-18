package scalapb.monix.grpc.testservice

import com.typesafe.scalalogging.LazyLogging
import io.grpc.Metadata
import monix.eval.Task
import monix.execution.{ExecutionModel, Scheduler}
import monix.reactive.subjects.ConcurrentSubject
import monix.reactive.{MulticastStrategy, Observable}
import scalapb.monix.grpc.testservice.Request.Scenario

import java.time.Instant
import scala.concurrent.ExecutionContext.global
import scala.concurrent.duration.DurationInt

class TestBackpressure extends munit.FunSuite with GrpcServerFixture with LazyLogging {
  val stub = clientFixture(8002)
  val request = Request(Scenario.OK, requestCount, Seq.fill(10000)(1))

  override def munitFixtures = List(stub)
  implicit val scheduler = Scheduler(global, executionModel = ExecutionModel.AlwaysAsyncExecution)

  val requestCount = 400
  val slowTask = Task().delayResult(10.milli)

  def requests(scenario: Scenario) = Observable
    .unfold(1)(s => Some(s -> (s + 1)))
    .take(requestCount)
    .map(_ => request.copy(scenario = scenario))

  val expectedAverageResponseTime = 7

  test("bidi stream calls should backpressure on client side".tag(Slow)) {
    val client = stub()
    val subject = ConcurrentSubject[Instant](MulticastStrategy.replay)

    val requestStream = requests(Scenario.SLOW)
      .doOnNext(_ => Task(subject.onNext(Instant.now())))

    client
      .bidiStreaming(requestStream, new Metadata())
      .completedL
      .runToFuture
      .onComplete(_ => subject.onComplete())

    subject
      .map(_.toEpochMilli)
      .toListL
      .map { events =>
        assert(averageEventDuration(events) > expectedAverageResponseTime)
      }
      .runToFuture
  }

  test("bidi stream calls should backpressure on server side".tag(Slow)) {
    val client = stub()

    client
      .bidiStreaming(
        requests(Scenario.OK),
        new Metadata()
      )
      .doOnNext(x => Task(println(s"got message ${x.out}")))
      .mapEval(r => slowTask.*>(slowTask).map(_ => r))
      .map(_.timestamp)
      .toListL
      .map { events =>
        val averageEvent = averageEventDuration(events)
        assert(
          averageEvent > expectedAverageResponseTime,
          s"$averageEvent the server produced the events too fast"
        )
      }
      .runToFuture
  }

  test("client stream calls should backpressure on client side".tag(Slow)) {
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
        val averageEvent = averageEventDuration(events)
        assert(
          averageEvent > expectedAverageResponseTime,
          s"$averageEvent the client produced the messages too fast"
        )
      }
      .runToFuture
  }

  test("server stream calls should backpressure on server side".tag(Slow)) {
    val client = stub()
    client
      .serverStreaming(Request(Scenario.BACK_PRESSURE, requestCount), new Metadata())
      .doOnNext(x => Task(logger.info(s"received: ${x.out}")))
      .mapEval(r => slowTask.map(_ => r))
      .toListL
      .map { events =>
        val averageDuration = averageEventDuration(events.map(_.timestamp))
        assert(
          averageDuration > expectedAverageResponseTime,
          s"$expectedAverageResponseTime the server produced responses too fast"
        )
      }
      .runToFuture
  }

  def averageEventDuration(timestamps: Seq[Long]) = {
    timestamps
      .sliding(2)
      .map(timestamps => timestamps.last - timestamps.head)
      .sum / requestCount

  }
}
