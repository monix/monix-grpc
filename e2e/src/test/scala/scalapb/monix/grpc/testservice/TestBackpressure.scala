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
  override def munitFixtures = List(stub)
  val rCount = 1000000000
  test("bidi stream calls should backpressure on client side") {
    val client = stub()
    val counter = AtomicInt(0)
    val requests = Observable.repeat(1).take(rCount).doOnNext(_=> Task{
      println(s"currentCount ${counter.getAndAdd(1)}")
    })
    client.bidiStreaming(requests .map(_ => Request(Scenario.SLOW, Seq.fill(1000)(1))), new Metadata()).completedL.runToFuture(global)
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
