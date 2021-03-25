package scalapb.monix.grpc.testservice

import com.typesafe.scalalogging.Logger
import io.grpc.{ManagedChannel, Metadata, Server}
import munit.Suite
import scalapb.monix.grpc.testservice.utils.TestServer

import scala.util.Try

trait GrpcServerFixture {
  self: Suite =>
  def clientFixture(
      port: Int,
      logger: Logger,
      inprocess: Boolean = false
  ): Fixture[TestServiceApi] =
    new Fixture[TestServiceApi]("server") {
      private val server: Server = TestServer.createServer(port, logger, inprocess)
      private var client: TestServiceApi = null
      private var channel: ManagedChannel = null

      def apply(): TestServiceApi = client

      override def beforeAll(): Unit = {
        server.start()
        val channelClient = TestServer.client(port, inprocess)
        channel = channelClient._1
        client = channelClient._2
      }

      override def afterAll(): Unit = {
        Try(channel.shutdown())
        Try(server.shutdown())
      }
    }
}
