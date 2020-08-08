package com.netflix.monix.grpc.runtime.client

import io.grpc

import monix.eval.Task
import cats.effect.ExitCase
import monix.execution.Scheduler
import monix.reactive.Observable
import monix.reactive.OverflowStrategy
import monix.execution.Cancelable

class ClientCall[Request, Response] private (
    call: grpc.ClientCall[Request, Response]
) {
  def unaryToUnaryCall(
      message: Request,
      headers: grpc.Metadata
  ): Task[Response] = Task.defer {
    val listener = ClientCallListeners.unary[Response]
    val makeCall = for {
      _ <- start(listener, headers)
      _ <- request(1)
      _ <- sendMessage(message)
      _ <- halfClose
      response <- listener.waitForResponse
    } yield response
    runResponseTaskHandler(makeCall)
  }

  def unaryToStreamingCall(
      message: Request,
      headers: grpc.Metadata
  )(implicit scheduler: Scheduler): Observable[Response] = Observable.defer {
    val listener = ClientCallListeners.streaming[Response](request)
    val makeCall = for {
      _ <- start(listener, headers)
      _ <- request(1)
      _ <- sendMessage(message)
      _ <- halfClose
    } yield listener.responses
    runResponseObservableHandler(Observable.fromTask(makeCall).flatten)
  }

  def streamingToUnaryCall(
      messages: Observable[Request],
      headers: grpc.Metadata
  ): Task[Response] = Task.defer {
    val listener = ClientCallListeners.unary[Response]
    val makeCall = for {
      _ <- start(listener, headers)
      _ <- request(1)
      _ <- messages.foreachL(sendMessage)
      _ <- halfClose
      response <- listener.waitForResponse
    } yield response
    runResponseTaskHandler(makeCall)
  }

  def streamingToStreamingCall(
      messages: Observable[Request],
      headers: grpc.Metadata
  )(implicit scheduler: Scheduler): Observable[Response] = Observable.defer {
    val listener = ClientCallListeners.streaming[Response](request)
    val makeCall = for {
      _ <- start(listener, headers)
      streamRequests = for {
        _ <- request(1)
        _ <- messages.foreachL(sendMessage)
        _ <- halfClose
      } yield ()

      streamRequestsObs = Observable.fromTask(streamRequests).flatMap { _ =>
        // Trick to create an `Observable[Nothing]` so that merge below works
        Observable.create[Nothing](OverflowStrategy.Unbounded) { sub =>
          sub.onComplete()
          Cancelable.empty
        }
      }
    } yield Observable(listener.responses, streamRequestsObs).merge
    runResponseObservableHandler(Observable.fromTask(makeCall).flatten)
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
  ): Task[ClientCall[Request, Response]] = Task {
    new ClientCall(channel.newCall[Request, Response](methodDescriptor, callOptions))
  }
}