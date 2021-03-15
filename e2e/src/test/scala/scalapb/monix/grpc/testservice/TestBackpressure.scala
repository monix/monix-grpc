package scalapb.monix.grpc.testservice

/**
 * Copyright (C) 15.03.21 - REstore NV
 */

class TestBackpressure extends munit.FunSuite with GrpcServerFixture {
  val stub = clientFixture(8002)
  override def munitFixtures = List(stub)

  test("bidi stream calls should backpressure on client side") {
    val client = stub()
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
