package monix.grpc.runtime.client

import cats.effect.ExitCase
import io.grpc
import monix.eval.{Task, TaskLocal}
import monix.execution.{AsyncVar, Scheduler}
import monix.reactive.Observable

class ClientCall[Request, Response] private[client] (val call: grpc.ClientCall[Request, Response])
    extends AnyVal {

  def unaryToUnaryCall(
      message: Request,
      headers: grpc.Metadata
  ): Task[Response] = Task.defer {
    val listener = ClientCallListeners.unary[Response]
    val makeCall = for {
      _ <- start(listener, headers)
      _ <- requestMessagesFromUnaryCall
      _ <- sendMessage(message).guaranteeCase {
        case ExitCase.Completed => halfClose
        case ExitCase.Error(e) =>
          cancel("Caught error when sending client message", Some(e))
        case ExitCase.Canceled =>
          cancel("Unexpected cancellation when sending client message", None)
      }
      response <- listener.waitForResponse
    } yield response
    TaskLocal
      .isolate(runResponseTaskHandler(makeCall))
      .executeWithOptions(_.enableLocalContextPropagation)
  }

  def unaryToStreamingCall(
      message: Request,
      headers: grpc.Metadata
  )(implicit
      scheduler: Scheduler
  ): Observable[Response] = Observable.defer {
    val listener = ClientCallListeners.streaming[Response](request)
    val startCall = for {
      _ <- start(listener, headers)
      _ <- request(1)
      _ <- sendMessage(message).guaranteeCase {
        case ExitCase.Completed => halfClose
        case ExitCase.Error(e) =>
          cancel("Caught error when sending client message", Some(e))
        case ExitCase.Canceled =>
          cancel("Unexpected cancellation when sending client message", None)
      }
    } yield ()

    runResponseObservableHandler(
      isolateObservable(
        listener.incomingResponses
          .doAfterSubscribe(startCall)
          .doOnNext(_ => request(1))
      )
    )
  }

  def streamingToUnaryCall(
      messages: Observable[Request],
      headers: grpc.Metadata
  ): Task[Response] = Task.defer {
    val listener = ClientCallListeners.unary[Response]
    val makeCall = for {
      _ <- start(listener, headers)
      _ <- requestMessagesFromUnaryCall
      runningRequest <- Task.racePair(
        listener.waitForResponse.attempt,
        sendStreamingRequests(messages, listener.onReadyEffect).attempt
      )
      response <- runningRequest match {
        case Right((responseFiber, _)) =>
          responseFiber.join.flatMap(Task.fromEither(_))
        case Left((response: Either[Throwable, Response], clientStreamFiber)) =>
          clientStreamFiber.cancel.flatMap(_ => Task.fromEither(response))
      }
    } yield response

    TaskLocal
      .isolate(runResponseTaskHandler(makeCall))
      .executeWithOptions(_.enableLocalContextPropagation)
  }

  private def sendStreamingRequests(
      requests: Observable[Request],
      onReady: AsyncVar[Unit]
  ): Task[Unit] = {
    def sendMessageWhenReady(request: Request): Task[Unit] =
      // Don't send message until the `onReady` async var is full and the call is ready
      Task.deferFuture(onReady.take()).restartUntil(_ => call.isReady).>>(sendMessage(request))

    requests
      .mapEval { request =>
        if (call.isReady) sendMessage(request)
        else sendMessageWhenReady(request)
      }
      .completedL
      .guaranteeCase {
        case ExitCase.Completed => halfClose
        case ExitCase.Error(e) => cancel("Caught unexpected client stream error!", Some(e))
        case ExitCase.Canceled => cancel("Client stream was canceled!", None)
      }
  }

  def streamingToStreamingCall(
      requests: Observable[Request],
      headers: grpc.Metadata
  )(implicit
      scheduler: Scheduler
  ): Observable[Response] = Observable.defer {
    val listener = ClientCallListeners.streaming[Response](request)

    val makeCall = start(listener, headers).>> {
      sendStreamingRequests(requests, listener.onReadyEffect).start.map { sendRequestsFiber =>
        listener.incomingResponses
          .doAfterSubscribe(request(1))
          .doOnNext(_ => request(1))
          .guaranteeCase {
            case ExitCase.Completed => sendRequestsFiber.join
            case ExitCase.Canceled => sendRequestsFiber.cancel
            case ExitCase.Error(err) => sendRequestsFiber.cancel
          }
      }
    }

    runResponseObservableHandler(
      isolateObservable(Observable.fromTask(makeCall).flatten)
    )
  }

  private def runResponseTaskHandler[R](response: Task[R]): Task[R] = {
    response.guaranteeCase {
      case ExitCase.Completed => Task.unit
      case ExitCase.Canceled => cancel(s"Client cancelled call $call", None)
      case ExitCase.Error(err) =>
        val cancelMsg = s"Caught unexpected error when processing response for $call"
        cancel(cancelMsg, Some(err))
    }
  }

  private def runResponseObservableHandler[R](response: Observable[R]): Observable[R] = {
    response.guaranteeCase {
      case ExitCase.Completed => Task.unit
      case ExitCase.Canceled => cancel(s"Client cancelled call $call", None)
      case ExitCase.Error(err) =>
        val cancelMsg = s"Caught unexpected error when processing response for $call"
        cancel(cancelMsg, Some(err))
    }
  }

  private def isolateObservable[T](thunk: => Observable[T]): Observable[T] = {
    val subscribingTask = TaskLocal
      .isolate(Task(thunk))
      .executeWithOptions(_.enableLocalContextPropagation)
    Observable.fromTask(subscribingTask).flatten
  }

  private def start(
      listener: grpc.ClientCall.Listener[Response],
      headers: grpc.Metadata
  ): Task[Unit] = Task(call.start(listener, headers))

  /**
   * Asks for two messages even though we expect only one so that if a
   * misbehaving client sends more than one response in a unary call we catch
   * the contract violation and fail right away.
   *
   * @note This is a trick employed by the official grpc-java, check the
   *  source if you want to learn more.
   */
  private def requestMessagesFromUnaryCall: Task[Unit] =
    request(2)

  private def request(numMessages: Int): Task[Unit] =
    Task(call.request(numMessages))

  private def sendMessage(message: Request): Task[Unit] =
    Task(call.sendMessage(message))

  private def halfClose: Task[Unit] =
    Task(call.halfClose())

  private def cancel(message: String, cause: Option[Throwable]): Task[Unit] =
    Task(call.cancel(message, cause.orNull))
}

object ClientCall {
  def apply[Request, Response](
      channel: grpc.Channel,
      methodDescriptor: grpc.MethodDescriptor[Request, Response],
      callOptions: grpc.CallOptions
  ): ClientCall[Request, Response] = {
    new ClientCall(
      channel.newCall[Request, Response](methodDescriptor, callOptions)
    )
  }
}
