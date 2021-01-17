package monix.grpc.runtime.client

import io.grpc

import monix.eval.Task
import monix.execution.atomic.Atomic
import monix.execution.CancelablePromise
import monix.reactive.Observable
import monix.execution.AsyncQueue
import monix.execution.Scheduler

object ClientCallListeners {
  final case class CallStatus(
      status: grpc.Status,
      trailers: grpc.Metadata
  ) {
    def isOk: Boolean = status.isOk()
    def toException: RuntimeException =
      status.asRuntimeException(trailers)
  }

  def unary[R]: UnaryClientCallListener[R] =
    new UnaryClientCallListener()

  def streaming[R](
      askForMoreRequests: Int => Task[Unit]
  )(implicit scheduler: Scheduler): StreamingClientCallListener[R] =
    new StreamingClientCallListener(askForMoreRequests)

  final class UnaryClientCallListener[Response] extends grpc.ClientCall.Listener[Response] {
    private val statusPromise = CancelablePromise[CallStatus]()
    private val headers0 = Atomic(None: Option[grpc.Metadata])
    private val response0 = Atomic(None: Option[Response])

    def waitForResponse: Task[Response] = {
      for {
        callStatus <- Task.fromCancelablePromise(statusPromise)
        result <- {
          if (!callStatus.isOk) Task.raiseError(callStatus.toException)
          else findResponse(callStatus.trailers)
        }
      } yield result
    }

    private def findResponse(trailers: grpc.Metadata): Task[Response] = {
      response0.get() match {
        case Some(response) => Task.now(response)
        case None =>
          val errMsg = "No response received from unary client call!"
          val errStatus = grpc.Status.INTERNAL.withDescription(errMsg)
          Task.raiseError(errStatus.asRuntimeException(trailers))
      }
    }

    override def onHeaders(headers: grpc.Metadata): Unit =
      headers0.compareAndSet(None, Some(headers))

    override def onClose(status: grpc.Status, trailers: grpc.Metadata): Unit =
      statusPromise.trySuccess(CallStatus(status, trailers))

    override def onMessage(message: Response): Unit = {
      if (response0.compareAndSet(None, Some(message))) ()
      else {
        val errMsg = "Too many response messages, expected only one!"
        val errStatus = grpc.Status.INTERNAL.withDescription(errMsg)
        val trailers = headers0.get().getOrElse(new grpc.Metadata)
        statusPromise.trySuccess(CallStatus(errStatus, trailers))
      }
    }
  }

  final class StreamingClientCallListener[Response](
      askForMoreRequests: Int => Task[Unit]
  )(
      implicit scheduler: Scheduler
  ) extends grpc.ClientCall.Listener[Response] {
    private val callStatus0 = CancelablePromise[CallStatus]()
    private val headers0 = Atomic(None: Option[grpc.Metadata])
    private val responses0 = AsyncQueue.unbounded[Option[Response]](None)

    def responses: Observable[Response] = {
      val pullValue = Task.deferFuture(responses0.poll())
      Observable
        .repeatEvalF(pullValue)
        .takeWhile(_.isDefined)
        .flatMap(elems => Observable.fromIterable(elems))
        .doOnComplete(
          Task.fromCancelablePromise(callStatus0).flatMap { status =>
            if (status.isOk) Task.unit
            else Task.raiseError(status.toException)
          }
        )
    }

    override def onHeaders(headers: grpc.Metadata): Unit =
      headers0.compareAndSet(None, Some(headers))

    override def onClose(status: grpc.Status, trailers: grpc.Metadata): Unit = {
      callStatus0.trySuccess(CallStatus(status, trailers))
      Task.deferFuture(responses0.offer(None)).runSyncUnsafe()
    }

    override def onMessage(message: Response): Unit = {
      Task
        .deferFuture(responses0.offer(Some(message)))
        .guarantee(askForMoreRequests(1))
        .runSyncUnsafe()
    }
  }
}
