package monix.grpc.runtime.server

import io.grpc
import monix.eval.Task
import monix.execution.BufferCapacity

// TODO: Add attributes, compression, message compression.
class ServerCall[Request, Response] private (
    val call: grpc.ServerCall[Request, Response]
) extends AnyVal {

  def isReady: Boolean = call.isReady

  def request(numMessages: Int): Task[Unit] =
    handleError(Task(call.request(numMessages)), s"Failed to request message $numMessages!")

  def sendHeaders(headers: grpc.Metadata): Task[Unit] =
    handleError(Task(call.sendHeaders(headers)), s"Failed to send headers!", headers)

  def sendMessage(message: Response): Task[Unit] = {
    handleError(Task(call.sendMessage(message)), s"Failed to send message $message!")
  }

  def closeStream(status: grpc.Status, trailers: grpc.Metadata): Task[Unit] =
    Task.delay(call.close(status, trailers))

  private def handleError(
      effect: Task[Unit],
      errorMsg: String,
      headers: grpc.Metadata = new grpc.Metadata()
  ): Task[Unit] = effect.onErrorHandleWith {
    case err: grpc.StatusException => Task.raiseError(err)
    case err: grpc.StatusRuntimeException => Task.raiseError(err)
    case err =>
      val errorStatus = grpc.Status.INTERNAL.withDescription(errorMsg).withCause(err)
      Task.raiseError(errorStatus.asRuntimeException(headers))
  }
}

object ServerCall {
  def apply[Request, Response](
      call: grpc.ServerCall[Request, Response],
      options: ServerCallOptions
  ): ServerCall[Request, Response] = {
    val compressions = options.compressor.map(_.name)
    compressions.foreach(call.setCompression)
    new ServerCall(call)
  }
}

abstract class ServerCallOptions private (
    val compressor: Option[ServerCompressor],
    val bufferCapacity: BufferCapacity
) {
  //needs to be private for binary compatibility
  private def copy(
      compressor: Option[ServerCompressor] = this.compressor,
      bufferCapacity: BufferCapacity
  ): ServerCallOptions = new ServerCallOptions(compressor, bufferCapacity) {}

  def withServerCompressor(
      compressor: Option[ServerCompressor]
  ): ServerCallOptions = copy(compressor, bufferCapacity)

  def withServerCompressor(
      bufferCapacity: BufferCapacity
  ): ServerCallOptions = copy(compressor, bufferCapacity)
}

object ServerCallOptions {
  val default: ServerCallOptions =
    new ServerCallOptions(Some(GzipCompressor), BufferCapacity.Bounded(32)) {}
}

abstract sealed class ServerCompressor(val name: String) extends Product with Serializable

case object GzipCompressor extends ServerCompressor("gzip")
