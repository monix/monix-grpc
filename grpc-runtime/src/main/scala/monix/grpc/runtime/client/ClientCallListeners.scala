package monix.grpc.runtime.client

import io.grpc
import monix.eval.Task
import monix.execution.atomic.Atomic
import monix.execution.{AsyncVar, CancelablePromise, Scheduler}
import monix.reactive.subjects.ConcurrentSubject
import monix.reactive.{MulticastStrategy, Observable, OverflowStrategy}

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
      request: Int => Task[Unit]
  )(implicit
      scheduler: Scheduler
  ): StreamingClientCallListener[R] =
    new StreamingClientCallListener(request)

  private[client] final class UnaryClientCallListener[Response]
      extends grpc.ClientCall.Listener[Response] {
    private val statusPromise = CancelablePromise[CallStatus]()
    private val headers0 = Atomic(None: Option[grpc.Metadata])
    private val response0 = Atomic(None: Option[Response])
    val onReadyEffect: AsyncVar[Unit] = AsyncVar.empty[Unit]()

    def waitForResponse: Task[Response] = {
      Task.fromCancelablePromise(statusPromise).flatMap { callStatus =>
        if (!callStatus.isOk) Task.raiseError(callStatus.toException)
        else findResponse(callStatus.trailers)
      }
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

    override def onReady(): Unit = onReadyEffect.tryPut(())
  }

  private[client] final class StreamingClientCallListener[Response](
      request: Int => Task[Unit]
  )(implicit
      scheduler: Scheduler
  ) extends grpc.ClientCall.Listener[Response] {

    private val headers0 = Atomic(None: Option[grpc.Metadata])
    private val responses0 = ConcurrentSubject[Response](
      MulticastStrategy.publish,
      OverflowStrategy.Unbounded
    )

    val onReadyEffect: AsyncVar[Unit] = AsyncVar.empty[Unit]()
    def incomingResponses: Observable[Response] = responses0

    override def onHeaders(headers: grpc.Metadata): Unit =
      headers0.compareAndSet(None, Some(headers))

    override def onClose(status: grpc.Status, trailers: grpc.Metadata): Unit =
      if (status.isOk) responses0.onComplete()
      else responses0.onError(CallStatus(status, trailers).toException)

    override def onMessage(message: Response): Unit =
      Task.deferFuture(responses0.onNext(message)).runSyncUnsafe()

    override def onReady(): Unit =
      onReadyEffect.tryPut(())
  }
}
