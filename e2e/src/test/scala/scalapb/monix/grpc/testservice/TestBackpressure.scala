package scalapb.monix.grpc.testservice

import io.grpc.Metadata
import monix.eval.Task
import monix.execution.Scheduler.global
import monix.execution.atomic.AtomicInt
import monix.reactive.{Observable, OverflowStrategy}
import monix.reactive.subjects.{PublishSubject, ReplaySubject, Subject}
import scalapb.monix.grpc.testservice.Request.Scenario

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

class TestBackpressure extends munit.FunSuite with GrpcServerFixture {
  val stub = clientFixture(8002)
  val request = Request(Scenario.SLOW, Array.fill(1000)(1.toInt))

  override def munitFixtures = List(stub)
  val rCount = 200
  test("bidi stream calls should backpressure on client side") {
    val client = stub()

    val requests = Observable
      .repeat(request)
      .take(rCount)
      .doOnNext(x => Task(print(x)))

    client
      .bidiStreaming(requests, new Metadata())
      .completedL
      .runToFuture(global)
  }

  test("bidi stream calls should backpressure on server side") {
    val client = stub()
  }

  test("client stream calls should backpressure on client side") {
    val client = stub()
  }

  test("server stream calls should backpressure on server side") {
    val client = stub()
  }
}
