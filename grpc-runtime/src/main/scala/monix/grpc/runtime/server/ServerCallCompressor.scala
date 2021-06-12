package monix.grpc.runtime.server

abstract sealed class ServerCallCompressor(
    val name: String
) extends Product
    with Serializable

object ServerCallCompressor {
  final case object Gzip extends ServerCallCompressor("gzip")
}
