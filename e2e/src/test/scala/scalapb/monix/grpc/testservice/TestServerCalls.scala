package scalapb.monix.grpc.testservice

import io.grpc.{Metadata, Server, StatusRuntimeException}
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import monix.execution.schedulers.TestScheduler
import munit.Location

import java.util.concurrent.TimeoutException
import scala.concurrent.duration.DurationInt

/**
 * Copyright (C) 11.03.21 - REstore NV
 */

class TestServerCalls extends munit.FunSuite {
  val stub = new Fixture[TestServiceGrpcService[Metadata]]("server") {
    private val server: Server = TestServer.createServer(8000)
    private var client: TestServiceGrpcService[Metadata] = null
    def apply() = client
    override def beforeAll(): Unit = {
      server.start()
      client = TestServer.monixStub(8000)
    }
    override def afterAll(): Unit = {
      server.shutdown()
    }
  }

  implicit val opt = Task.defaultOptions.enableLocalContextPropagation
  override def munitFixtures = List(stub)

  private val ok = Response("OK")
  private val okStream = List(Response("OK1"), Response("OK2"))

  private def expectedException(e: Throwable)(implicit loc: Location) = {
    assert(e.isInstanceOf[StatusRuntimeException])
    assertEquals(e.getMessage, "INTERNAL: SILENT")
  }

  test("unary call success"){
    val client = stub()
    client.unary(Request(Request.Scenario.OK), new Metadata())
      .map{r =>
        assertEquals(r, ok)
      }.runToFutureOpt
  }

  test("unary call fail"){
    val client = stub()
    client.unary(Request(Request.Scenario.ERROR_NOW), new Metadata())
      .redeem(
        expectedException,
        r => fail(s"we should not receive a response $r")
      )
      .runToFutureOpt
  }

  test("unary call timeout"){
    val client = stub()
    client.unary(Request(Request.Scenario.DELAY), new Metadata())
      .timeout(1.seconds)
      .redeem(
        e => assert(e.isInstanceOf[TimeoutException]),
        r => fail(s"we should not receive a response $r")
      )
      .runToFutureOpt
  }

  test("serverStream call success"){
    val client = stub()
    client.serverStreaming(Request(Request.Scenario.OK), new Metadata())
      .toListL
      .map{ r =>
        assertEquals(r, okStream)
      }
      .runToFutureOpt
  }

  test("serverStream call fail"){
    val client = stub()
    client.serverStreaming(Request(Request.Scenario.ERROR_NOW), new Metadata())
      .toListL
      .redeem(
        expectedException,
        r => fail(s"we should not receive a response $r")
      )
      .runToFutureOpt
  }

  test("serverStream call fail after 2 responses"){
    val client = stub()
    client.serverStreaming(Request(Request.Scenario.ERROR_AFTER), new Metadata())
      .map(r => Right(r))
      .onErrorHandle(Left(_))
      .toListL
      .map{ responses =>
        assertEquals(responses.take(2).map(_.right.get), okStream)
        assertEquals(responses.take(2).map(_.right.get), okStream)
      }
      .runToFutureOpt
  }

  test("serverStream call timeout"){
    val client = stub()
    client.serverStreaming(Request(Request.Scenario.DELAY), new Metadata())
      .toListL
      .timeout(1.second)
      .redeem(
        e => assert(e.isInstanceOf[TimeoutException]),
        r => fail(s"we should not receive a response $r")
      )
      .runToFutureOpt
  }

  test("clientStream call success"){
    val client = stub()
  }

  test("clientStream call fail"){
    val client = stub()

  }

  test("clientStream call never"){
    val client = stub()

  }

  test("biStream call success"){
    val client = stub()

  }

  test("biStream call fail"){
    val client = stub()

  }

  test("biStream call never"){
    val client = stub()

  }

}
