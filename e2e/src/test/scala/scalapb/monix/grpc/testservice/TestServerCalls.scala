package scalapb.monix.grpc.testservice

import io.grpc.{Metadata, Server}
import monix.eval.Task
import monix.execution.Scheduler.global
import monix.execution.schedulers.TestScheduler

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
  override def munitFixtures = List(stub)

  test("unary call success"){
    val client = stub()
    client.unary(Request(Request.Scenario.OK), new Metadata())
      .map{r =>
        assertEquals(r.out, "OK")
      }.runToFutureOpt(global, Task.defaultOptions.enableLocalContextPropagation)
  }

  test("unary call fail"){
    val client = stub()

  }

  test("unary call never"){
    val client = stub()

  }

  test("serverStream call success"){
    val client = stub()

  }

  test("serverStream call fail"){
    val client = stub()

  }

  test("serverStream call never"){
    val client = stub()

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
