package monix.grpc.runtime.client

import io.grpc
import monix.eval.Task
import cats.effect.ExitCase
import monix.execution.{BufferCapacity, Cancelable, Scheduler}
import monix.reactive.Observable
import monix.reactive.OverflowStrategy
import monix.eval.TaskLocal

import java.time.Instant

class ClientCall[Request, Response] private (
    call: grpc.ClientCall[Request, Response],
    bufferCapacity: BufferCapacity
) {

  def unaryToUnaryCall(
      message: Request,
      headers: grpc.Metadata
  ): Task[Response] = Task.defer {
    val listener = ClientCallListeners.unary[Response]
    val makeCall = for {
      _ <- start(listener, headers)
      _ <- request(1)
      _ <- sendMessage(message).guarantee(halfClose)
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
    val listener = ClientCallListeners.streaming[Response](bufferCapacity, request)
    val makeCall = for {
      _ <- start(listener, headers)
      _ <- request(1)
      _ <- sendMessage(message).guarantee(halfClose)
    } yield listener.responses
    runResponseObservableHandler(
      Observable
        .fromTask(TaskLocal.isolate(makeCall).executeWithOptions(_.enableLocalContextPropagation))
        .flatten
    )
  }

  def streamingToUnaryCall(
      messages: Observable[Request],
      headers: grpc.Metadata
  ): Task[Response] = Task.defer {
    val listener = ClientCallListeners.unary[Response]
    val makeCall = for {
      _ <- start(listener, headers)
      _ <- request(1)
      runningRequest <- {
        val clientStream = messages
          .mapEval(message =>
            if (call.isReady) {
              sendMessage(message)
            } else {
              val waitUntilReady = Task.fromFuture(listener.onReadyEffect.take())
              waitUntilReady.>>(sendMessage(message))
            }
          )
          .completedL
          .guarantee(halfClose)
        Task.racePair(
          listener.waitForResponse,
          clientStream
        )
      }
      response <- runningRequest match {
        case Left((response, clientStream)) =>
          clientStream.cancel.redeem(_ => response, _ => response)
        case Right((eventualResponse, _)) => eventualResponse.join
      }
    } yield response

    TaskLocal
      .isolate(runResponseTaskHandler(makeCall))
      .executeWithOptions(_.enableLocalContextPropagation)
  }

  def streamingToStreamingCall(
      requests: Observable[Request],
      headers: grpc.Metadata
  )(implicit
      scheduler: Scheduler
  ): Observable[Response] = {
    val listener = ClientCallListeners.streaming[Response](bufferCapacity, request)
    val makeCall = for {
      _ <- start(listener, headers)
      _ <- request(1)
      requestStream <-
        requests
          .mapEval(requests =>
            if (call.isReady) {
              sendMessage(requests)
            } else {
              val waitUntilReady = Task.fromFuture(listener.onReadyEffect.take())
              waitUntilReady.>>(sendMessage(requests))
            }
          )
          .guarantee(halfClose)
          .completedL
    } yield requestStream

    runResponseObservableHandler(
      listener.responses.doOnSubscribe(
        TaskLocal.isolate(makeCall).executeWithOptions(_.enableLocalContextPropagation)
      )
    )
  }

  private def runResponseTaskHandler[R](response: Task[R]): Task[R] = {
    response.guaranteeCase {
      case ExitCase.Completed => Task.unit
      case ExitCase.Canceled => cancel(s"Cancelling call $call", None)
      case ExitCase.Error(err) =>
        val cancelMsg = s"Cancelling call, found unexpected error ${err.getMessage}"
        cancel(cancelMsg, Some(err))
    }
  }

  private def runResponseObservableHandler[R](response: Observable[R]): Observable[R] = {
    response.guaranteeCase {
      case ExitCase.Completed => Task.unit
      case ExitCase.Canceled => cancel(s"Cancelling call $call", None)
      case ExitCase.Error(err) =>
        val cancelMsg = s"Cancelling call, found unexpected error ${err.getMessage}"
        cancel(cancelMsg, Some(err))
    }
  }

  private def start(
      listener: grpc.ClientCall.Listener[Response],
      headers: grpc.Metadata
  ): Task[Unit] = Task(call.start(listener, headers))

  private def request(numMessages: Int): Task[Unit] = {
    Task(call.request(numMessages))
  }

  private def sendMessage(message: Request): Task[Unit] = {
    Task(call.sendMessage(message))
  }

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
      channel.newCall[Request, Response](methodDescriptor, callOptions),
      BufferCapacity.Bounded(32)
    )
  }
}
