package scalapb.monix.grpc.testservice

import io.grpc.{ManagedChannel, Metadata, Server}
import munit.Suite
import scalapb.monix.grpc.testservice.utils.TestServer

import scala.util.Try

trait GrpcServerFixture {
  self: Suite =>
  def clientFixture(port: Int) = new Fixture[TestServiceGrpcService[Metadata]]("server") {
    private val server: Server = TestServer.createServer(port)
    private var client: TestServiceGrpcService[Metadata] = null
    private var channel: ManagedChannel = null

    def apply() = client

    override def beforeAll(): Unit = {
      server.start()
      val channelClient = TestServer.client(port)
      channel = channelClient._1
      client = channelClient._2
    }

    override def afterAll(): Unit = {
      Try(channel.shutdown())
      Try(server.shutdown())
    }
  }
}
