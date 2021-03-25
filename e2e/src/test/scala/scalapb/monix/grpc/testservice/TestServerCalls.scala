package scalapb.monix.grpc.testservice

import com.typesafe.scalalogging.LazyLogging
import io.grpc.{Metadata, Server, StatusRuntimeException}
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import monix.reactive.subjects.{PublishSubject, ReplaySubject, Subject}
import munit.Location
import scalapb.monix.grpc.testservice.utils.SilentException

import java.util.concurrent.TimeoutException
import scala.concurrent.duration.{Duration, DurationInt}

class TestServerCalls extends munit.FunSuite with GrpcServerFixture with LazyLogging {
  val stub = clientFixture(8000, logger, false)
  override def munitFixtures = List(stub)

  implicit val opt = Task.defaultOptions.enableLocalContextPropagation

  test("unary call responds successfully") {
    val client = stub()
    client
      .unary(Request(Request.Scenario.OK))
      .map { r =>
        assertEquals(r.out, 1)
      }
      .runToFutureOpt
  }

  test("unary call responds with a failure") {
    val client = stub()
    client
      .unary(Request(Request.Scenario.ERROR_NOW))
      .redeem(
        expectedException,
        r => fail(s"The server should not return a response $r")
      )
      .runToFutureOpt
  }

  test("unary call times out") {
    val client = stub()
    client
      .unary(Request(Request.Scenario.DELAY))
      .timeout(1.seconds)
      .redeem(
        e => assert(e.isInstanceOf[TimeoutException]),
        r => fail(s"The server should not return a response $r")
      )
      .runToFutureOpt
  }

  test("serverStreaming call responds successfully") {
    val client = stub()
    client
      .serverStreaming(Request(Request.Scenario.OK))
      .toListL
      .map { r =>
        assert(r.map(_.out) == Seq(1, 2))
      }
      .runToFutureOpt
  }

  test("serverStreaming call responds with a failure") {
    val client = stub()
    client
      .serverStreaming(Request(Request.Scenario.ERROR_NOW))
      .toListL
      .redeem(
        expectedException,
        r => fail(s"The server should not return a response $r")
      )
      .runToFutureOpt
  }

  test("serverStreaming call responds during the response stream with a failure") {
    val client = stub()
    client
      .serverStreaming(Request(Request.Scenario.ERROR_AFTER))
      .map(r => Right(r))
      .onErrorHandle(Left(_))
      .toListL
      .map { responses =>
        assert(responses.take(2).map(_.right.get.out) == Seq(1, 2))
      }
      .runToFutureOpt
  }

  test("serverStreaming call times out") {
    val client = stub()
    client
      .serverStreaming(Request(Request.Scenario.DELAY))
      .toListL
      .timeout(1.second)
      .redeem(
        e => assert(e.isInstanceOf[TimeoutException]),
        r => fail(s"The server should not return receive a response $r")
      )
      .runToFutureOpt
  }

  test("clientStreaming responds successfully") {
    val client = stub()
    val subject = ReplaySubject[Request]()

    def response = client
      .clientStreaming(subject)
      .map(r => assertEquals(r.out, 3))
      .runToFutureOpt

    for {
      _ <- subject.onNext(Request(Request.Scenario.OK))
      _ <- subject.onNext(Request(Request.Scenario.OK))
      _ <- subject.onNext(Request(Request.Scenario.OK))
    } yield subject.onComplete()

    response
  }

  test("clientStreaming call responds with a failure") {
    val client = stub()
    val subject = ReplaySubject[Request]()

    def response = client
      .clientStreaming(subject)
      .redeem(
        expectedException,
        r => fail(s"The server should not return a response $r")
      )
      .runToFutureOpt

    for {
      _ <- subject.onNext(Request(Request.Scenario.OK))
      _ <- subject.onNext(Request(Request.Scenario.OK))
      _ <- subject.onNext(Request(Request.Scenario.ERROR_NOW))
      _ <- subject.onNext(Request(Request.Scenario.OK))
    } yield ()

    response
  }

  test("clientStreaming responds with a failure") {
    val client = stub()
    val subject = ReplaySubject[Request]()

    def response = client
      .clientStreaming(subject)
      .redeem(
        _ => (),
        _ => fail("The client send an error and no exception is on the server result")
      )
      .runToFutureOpt

    subject.onError(SilentException())
    response
  }

  test("clientStreaming call times out") {
    val client = stub()
    val subject = ReplaySubject[Request]()

    def response = client
      .clientStreaming(subject)
      .timeout(1.second)
      .redeem(
        e => assert(e.isInstanceOf[TimeoutException]),
        r => fail(s"The server should not return a response $r")
      )
      .runToFutureOpt

    for {
      _ <- subject.onNext(Request(Request.Scenario.OK))
      _ <- subject.onNext(Request(Request.Scenario.OK))
      _ <- subject.onNext(Request(Request.Scenario.DELAY))
    } yield ()

    response
  }

  test("bidiStreaming call succeeds") {
    val client = stub()
    val subject = ReplaySubject[Request]()
    val response = client
      .bidiStreaming(subject)
      .toListL
      .map(r => assertEquals(r.map(_.out), List(1)))
      .runToFutureOpt

    for {
      _ <- subject.onNext(Request(Request.Scenario.OK))
    } yield subject.onComplete()
    response
  }

  test("bidiStreaming responds with a failure when the client makes the request stream fail") {
    val client = stub()
    val subject = ReplaySubject[Request]()

    def response = client
      .bidiStreaming(subject)
      .toListL
      .redeem(
        _ => (),
        r => fail(s"The server should not return a response $r")
      )
      .runToFutureOpt

    subject.onError(SilentException())
    response
  }

  test("bidiStreaming call responds with a failure") {
    val client = stub()
    val subject = ReplaySubject[Request]()
    val response = client
      .bidiStreaming(subject)
      .toListL
      .redeem(
        expectedException,
        r => assertEquals(r.size, 2)
      )
      .runToFutureOpt

    subject.onNext(Request(Request.Scenario.OK))
    subject.onNext(Request(Request.Scenario.OK))
    subject.onNext(Request(Request.Scenario.ERROR_NOW))
    response
  }

  test("bidiStreaming call times out") {
    val client = stub()
    val subject = ReplaySubject[Request]()
    val response = client
      .bidiStreaming(subject)
      .toListL
      .timeout(1.second)
      .redeem(
        e => assert(e.isInstanceOf[TimeoutException]),
        r => fail(s"The server should not return a response $r")
      )
      .runToFutureOpt

    response
  }

  private def expectedException(e: Throwable)(implicit loc: Location) = {
    assert(e.isInstanceOf[StatusRuntimeException])
    assertEquals(e.getMessage, "INTERNAL: SILENT")
  }

  override def munitTimeout: Duration = 2.second
}
