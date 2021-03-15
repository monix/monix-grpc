package scalapb.monix.grpc.testservice

import io.grpc.{ManagedChannel, Metadata, Server}
import scalapb.monix.grpc.testservice.utils.TestServer

import scala.util.Try

/**
 * Copyright (C) 15.03.21 - REstore NV
 */

class GrpcBaseSpec(port: Int) extends munit.FunSuite {
  val stub = new Fixture[TestServiceGrpcService[Metadata]]("server") {
    private val server: Server = TestServer.createServer(8000)
    private var client: TestServiceGrpcService[Metadata] = null
    private var channel: ManagedChannel = null

    def apply() = client

    override def beforeAll(): Unit = {
      server.start()
      val channelClient = TestServer.client(8000)
      channel = channelClient._1
      client = channelClient._2
    }

    override def afterAll(): Unit = {
      Try(channel.shutdown())
      Try(server.shutdown())
    }
  }

  override def munitFixtures = List(stub)
}
