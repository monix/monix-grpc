package monix.grpc.runtime.server

import io.grpc
import monix.eval.Task
import monix.execution.{AsyncVar, BufferCapacity}
import monix.reactive.Observable

/**
 * Defines a server call that accepts a client {@tparam Request} and returns a
 * server {@tparam Response}. The following definition is the Monix-based
 * version of the wrapped `grpc.ServerCall` and thus tries to resemble the
 * underlying API as much as possible while accepting `Task` and `Observable`
 * as input and output types.
 *
 * @param call is the instance of the wrapped grpc server call.
 * @param options is a field for custom user-defined server call options.
 */
final class ServerCall[Request, Response] private (
    val call: grpc.ServerCall[Request, Response],
    val options: ServerCallOptions
) {

  /**
   * Requests up to the given number of messages from the call to be delivered
   * to{@link Listener#onMessage(Object)}. Once {@code numMessages} have been
   * delivered no further request messages will be delivered until more
   * messages are requested by calling this method again.
   *
   * Servers use this mechanism to provide back-pressure to the client for
   * flow-control. This method is safe to call from multiple threads without
   * external synchronization.
   *
   * @param numMessages the number of messages to be delivered to the listener.
   */
  def request(numMessages: Int): Task[Unit] =
    handleError(Task(call.request(numMessages)), s"Failed to request message $numMessages!")

  /**
   * Asks for two messages even though we expect only one so that if a
   * misbehaving client sends more than one response in a unary call we catch
   * the contract violation and fail right away.
   *
   * @note This is a trick employed by the official grpc-java, check the
   *  source if you want to learn more.
   */
  def requestMessagesFromUnaryCall: Task[Unit] = request(2)

  /**
   * Sends response header metadata prior to sending a response message. This
   * method may only be called once and cannot be called after calls to
   * {@link #sendMessage} or {@link #close}.
   *
   * Since {@link Metadata} is not thread-safe, the caller must not access
   * (read or write) {@code headers} after this point.
   *
   * @param headers metadata to send prior to any response body.
   * @return a task that will complete when headers are successfully sent or
   *  that will throw an `IllegalStateException` error if{@code close} has
   *  been called, a message has been sent, or headers have already been sent
   */
  def sendHeaders(headers: grpc.Metadata): Task[Unit] =
    handleError(Task(call.sendHeaders(headers)), s"Failed to send headers!", headers)

  /**
   * Sends a response message. Messages are the primary form of communication
   * associated with RPCs. Multiple response messages may exist for streaming
   * calls.
   *
   * @param message is the response message to send to the client.
   * @return a task that will complete when message is successfully sent or
   *  that will throw an `IllegalStateException` error if headers not sent or
   *  call is {@link #close}d
   */
  def sendMessage(message: Response): Task[Unit] =
    handleError(Task(call.sendMessage(message)), s"Failed to send message $message!")

  /**
   * Subscribed to the `responses` observable provided as a parameter and sends
   * each received response to the client with built-in flow control.
   *
   * @param responses is the observable that streams responses.
   * @param onReady is an async var that will be full when the client is ready
   *  to receive another message and thus the server can send a response.
   *
   * @return a task that will complete when all messages are successfully sent or
   *  that will throw an `IllegalStateException` error if headers not sent or
   *  call is {@link #close}d
   */
  def sendStreamingResponses(
      responses: Observable[Response],
      onReady: AsyncVar[Unit]
  ): Task[Unit] = {
    def sendMessageWhenReady(response: Response): Task[Unit] =
      // Don't send message until the `onReady` async var is full and the call is ready
      Task.deferFuture(onReady.take()).restartUntil(_ => isReady).>>(sendMessage(response))

    responses.mapEval { response =>
      if (isReady) sendMessage(response)
      else sendMessageWhenReady(response)
    }.completedL
  }

  /**
   * Close the call with the provided status. No further sending or receiving
   * will occur. If {@link Status#isOk} is {@code false}, then the call is
   * said to have failed.
   *
   * If no errors or cancellations are known to have occurred, then a
   * {@link Listener#onComplete} notification should be expected, independent
   * of {@code status}. Otherwise {@link Listener#onCancel} has been or will
   * be called.
   *
   * Since {@link Metadata} is not thread-safe, the caller must not access
   * (read or write) {@code trailers} after this point.
   *
   * This method implies the caller completed processing the RPC, but it does
   * not imply the RPC is complete. The call implementation will need
   * additional time to complete the RPC and during this time the client is
   * still able to cancel the request or a network error might cause the RPC
   * to fail. If you wish to know when the call is actually completed/closed,
   * you have to use{@link Listener#onComplete} or {@link Listener#onCancel}
   * instead. This method is not necessarily invoked when Listener.onCancel
   * () is called.
   *
   * @return a task that will complete when successfully closed and will throw
   *  `IllegalStateException` if call is already {@code close}d.
   */
  def close(status: grpc.Status, trailers: grpc.Metadata): Task[Unit] =
    Task.delay(call.close(status, trailers))

  /**
   * If {@code true}, indicates that the call is capable of sending additional
   * messages without requiring excessive buffering internally. This event is
   * just a suggestion and the application is free to ignore it, however
   * doing so may result in excessive buffering within the call.
   *
   * If {@code false}, {@link Listener#onReady()} will be called after
   * {@code isReady()} transitions to {@code true}.
   *
   * This abstract class's implementation always returns {@code true}.
   * Implementations generally override the method.
   */
  private def isReady: Boolean = call.isReady

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

  /**
   * Creates a server call that accepts a client {@tparam Request} and returns
   * a server {@tparam Response}. The following definition supports Monix and
   * automatic flow control out-of-the-box and so embraces `Task` and
   * `Observable` as part of its API.
   */
  def apply[Request, Response](
      call: grpc.ServerCall[Request, Response],
      options: ServerCallOptions
  ): ServerCall[Request, Response] = {
    options.enabledMessageCompression.foreach(call.setMessageCompression)
    options.compressor.foreach { compressor =>
      grpc.CompressorRegistry.getDefaultInstance().register(compressor)
      compressor.getMessageEncoding()
    }
    new ServerCall(call, options)
  }
}
