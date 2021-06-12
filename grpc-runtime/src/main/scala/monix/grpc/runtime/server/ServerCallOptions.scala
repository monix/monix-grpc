package monix.grpc.runtime.server

import io.grpc
import monix.eval.Task
import monix.execution.{AsyncVar, BufferCapacity}
import monix.reactive.Observable

final class ServerCallOptions private (
    val compressor: Option[ServerCallCompressor],
    val bufferSize: Option[Int]
) {
  def withCompresor(
      compressor: ServerCallCompressor
  ): ServerCallOptions = new ServerCallOptions(Some(compressor), bufferSize)

  def withNoCompression: ServerCallOptions =
    new ServerCallOptions(None, bufferSize)

  def withBufferSize(
      bufferSize: Int
  ): ServerCallOptions = new ServerCallOptions(compressor, Some(bufferSize))

  def withNoBuffering: ServerCallOptions =
    new ServerCallOptions(compressor, None)
}

object ServerCallOptions {
  val default: ServerCallOptions =
    new ServerCallOptions(Some(???), Some(32))
}
