package monix.grpc.runtime.server

import cats.effect.ExitCase
import io.grpc
import monix.eval.{Task, TaskLocal}
import monix.execution.atomic.AtomicAny
import monix.execution.{AsyncVar, BufferCapacity, CancelablePromise, Scheduler}
import monix.reactive.subjects.ConcurrentSubject
import monix.reactive.{MulticastStrategy, Observable, OverflowStrategy}

/**
 * Defines the grpc service API handlers that are used in the stub code
 * generated from our domain-specific code generator.
 */
object ServerCallHandlers {

  /**
   * Defines a grpc service call handler that receives only one request from the
   * client and returns one response from the server.
   *
   * @param f is the function that turns a request and metadata into a response.
   * @param options is the configuration to configure options for this call.
   * @param scheduler is the (implicit) scheduler available in the service definition.
   * @return a grpc server call handler that will be responsible for processing this call.
   */
  def unaryToUnaryCall[T, R](
      f: (T, grpc.Metadata) => Task[R],
      options: ServerCallOptions = ServerCallOptions.default
  )(implicit
      scheduler: Scheduler
  ): grpc.ServerCallHandler[T, R] = new grpc.ServerCallHandler[T, R] {
    def startCall(
        grpcCall: grpc.ServerCall[T, R],
        metadata: grpc.Metadata
    ): grpc.ServerCall.Listener[T] = {
      val call = ServerCall(grpcCall, options)
      val listener = new UnaryCallListener(call, scheduler)
      listener.runUnaryResponseListener(metadata) { msg =>
        Task.defer(f(msg, metadata)).flatMap(call.sendMessage)
      }
      listener
    }

  }

  /**
   * Defines a grpc service call handler that receives only one request from the
   * client and returns several responses from the server.
   *
   * @param f is the function that turns a request and metadata into a response.
   * @param options is the configuration to configure options for this call.
   * @param scheduler is the (implicit) scheduler available in the service definition.
   * @return a grpc server call handler that will be responsible for processing this call.
   */
  def unaryToStreamingCall[T, R](
      f: (T, grpc.Metadata) => Observable[R],
      options: ServerCallOptions = ServerCallOptions.default
  )(implicit
      scheduler: Scheduler
  ): grpc.ServerCallHandler[T, R] = new grpc.ServerCallHandler[T, R] {
    def startCall(
        grpcCall: grpc.ServerCall[T, R],
        metadata: grpc.Metadata
    ): grpc.ServerCall.Listener[T] = {
      val call = ServerCall(grpcCall, options)
      val listener = new UnaryCallListener(call, scheduler)

      listener.runUnaryResponseListener(metadata) { msg =>
        call.sendStreamingResponses(
          Observable.defer(f(msg, metadata)),
          listener.onReadyEffect
        )
      }
      listener
    }
  }

  private[server] final class UnaryCallListener[T, R](
      call: ServerCall[T, R],
      scheduler: Scheduler
  ) extends grpc.ServerCall.Listener[T] {
    val onReadyEffect: AsyncVar[Unit] = AsyncVar.empty[Unit]()

    private val requestMsg = AtomicAny[Option[T]](None)
    private val completed = CancelablePromise[grpc.Status]()
    private val isCancelled = CancelablePromise[Unit]()

    def runUnaryResponseListener(
        metadata: grpc.Metadata
    )(
        sendResponse: T => Task[Unit]
    ): Unit = {
      val handleResponse = for {
        _ <- call.requestMessagesFromUnaryCall
        _ <- Task.fromCancelablePromise(completed)
        _ <- call.sendHeaders(metadata)
        _ <- requestMsg.get() match {
          case Some(msg) => sendResponse(msg)
          case None =>
            val errMsg = "Missing request message for unary call!"
            val errStatus = grpc.Status.INTERNAL.withDescription(errMsg)
            Task.raiseError(errStatus.asRuntimeException(metadata))
        }
      } yield ()

      TaskLocal
        .isolate(runResponseHandler(call, handleResponse, isCancelled))
        .executeWithOptions(_.enableLocalContextPropagation)
        .runAsyncAndForget(scheduler)
    }

    override def onHalfClose(): Unit =
      completed.trySuccess(grpc.Status.OK)

    override def onCancel(): Unit =
      isCancelled.trySuccess(())

    override def onMessage(msg: T): Unit = {
      if (requestMsg.compareAndSet(None, Some(msg))) ()
      else {
        val errMsg = "Too many requests received for unary request"
        val errStatus = grpc.Status.INTERNAL.withDescription(errMsg)
        completed.tryFailure(errStatus.asRuntimeException())
      }
    }

    override def onReady(): Unit = onReadyEffect.tryPut(())

  }

  /**
   * Defines a grpc service call handler that receives several requests request
   * from the client and returns one response from the server.
   *
   * @param f is the function that turns a request and metadata into a response.
   * @param options is the configuration to configure options for this call.
   * @param scheduler is the (implicit) scheduler available in the service definition.
   * @return a grpc server call handler that will be responsible for processing this call.
   */
  def streamingToUnaryCall[T, R](
      f: (Observable[T], grpc.Metadata) => Task[R],
      options: ServerCallOptions = ServerCallOptions.default
  )(implicit
      scheduler: Scheduler
  ): grpc.ServerCallHandler[T, R] = new grpc.ServerCallHandler[T, R] {
    def startCall(
        grpcCall: grpc.ServerCall[T, R],
        metadata: grpc.Metadata
    ): grpc.ServerCall.Listener[T] = {
      val call = ServerCall(grpcCall, options)
      val listener = new StreamingCallListener(call, options.bufferCapacity)(scheduler)
      listener.runStreamingResponseListener(metadata) { msgs =>
        Task.defer(f(msgs, metadata)).flatMap(call.sendMessage)
      }
      listener
    }
  }

  /**
   * Defines a grpc service call handler that receives several requests request
   * from the client and returns several responses from the server.
   *
   * @param f is the function that turns a request and metadata into a response.
   * @param options is the configuration to configure options for this call.
   * @param scheduler is the (implicit) scheduler available in the service definition.
   * @return a grpc server call handler that will be responsible for processing this call.
   */
  def streamingToStreamingCall[T, R](
      f: (Observable[T], grpc.Metadata) => Observable[R],
      options: ServerCallOptions = ServerCallOptions.default
  )(implicit
      scheduler: Scheduler
  ): grpc.ServerCallHandler[T, R] = new grpc.ServerCallHandler[T, R] {
    def startCall(
        grpcCall: grpc.ServerCall[T, R],
        metadata: grpc.Metadata
    ): grpc.ServerCall.Listener[T] = {

      val call = ServerCall(grpcCall, options)
      val listener = new StreamingCallListener(call, options.bufferCapacity)(scheduler)
      listener.runStreamingResponseListener(metadata) { msgs =>
        call.sendStreamingResponses(
          Observable.defer(f(msgs, metadata)),
          listener.onReadyEffect
        )
      }
      listener
    }
  }

  private[server] final class StreamingCallListener[Request, Response](
      call: ServerCall[Request, Response],
      capacity: BufferCapacity
  )(implicit
      scheduler: Scheduler
  ) extends grpc.ServerCall.Listener[Request] {
    private val isCancelled = CancelablePromise[Unit]()
    private val subject = ConcurrentSubject[Request](
      MulticastStrategy.publish,
      OverflowStrategy.Unbounded
    )

    val onReadyEffect: AsyncVar[Unit] = AsyncVar.empty[Unit]()

    def runStreamingResponseListener(
        metadata: grpc.Metadata
    )(
        sendResponses: Observable[Request] => Task[Unit]
    ): Unit = {
      val handleResponse = for {
        _ <- call.sendHeaders(metadata)
        _ <- sendResponses {
          subject
            .doAfterSubscribe(call.request(1))
            .doOnNext(_ => call.request(1))
        }
      } yield ()

      TaskLocal
        .isolate(runResponseHandler(call, handleResponse, isCancelled))
        .executeWithOptions(_.enableLocalContextPropagation)
        .runAsyncAndForget(scheduler)

    }

    override def onCancel(): Unit =
      isCancelled.trySuccess(())

    override def onHalfClose(): Unit =
      subject.onComplete()

    override def onMessage(msg: Request): Unit =
      Task.deferFuture(subject.onNext(msg)).runSyncUnsafe()

    override def onComplete(): Unit =
      subject.onComplete()

    override def onReady(): Unit =
      onReadyEffect.tryPut(())
  }

  private def runResponseHandler[T, R](
      call: ServerCall[T, R],
      handleResponse: Task[Unit],
      isCancelled: CancelablePromise[Unit]
  ): Task[Unit] = {
    val finalHandler = handleResponse.guaranteeCase {
      case ExitCase.Completed => call.closeStream(grpc.Status.OK, new grpc.Metadata())
      case ExitCase.Canceled => call.closeStream(grpc.Status.CANCELLED, new grpc.Metadata())
      case ExitCase.Error(err) => reportError(err, call, new grpc.Metadata())
    }

    // If `isCancelled` is completed, then client cancelled the grpc call and
    // `finalHandler` will be cancelled automatically by the `race` method
    Task.race(finalHandler, Task.fromCancelablePromise(isCancelled)).void
  }

  private def reportError[T, R](
      err: Throwable,
      call: ServerCall[T, R],
      unknownErrorMetadata: grpc.Metadata
  ): Task[Unit] = {
    err match {
      case err: grpc.StatusException =>
        val metadata = Option(err.getTrailers).getOrElse(new grpc.Metadata())
        call.closeStream(err.getStatus, metadata)
      case err: grpc.StatusRuntimeException =>
        val metadata = Option(err.getTrailers).getOrElse(new grpc.Metadata())
        call.closeStream(err.getStatus, metadata)
      case err =>
        val status = grpc.Status.INTERNAL.withDescription(err.getMessage).withCause(err)
        call.closeStream(status, unknownErrorMetadata)
    }
  }
}
