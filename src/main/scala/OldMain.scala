/*
package remoto

import com.twitter.finagle.Http
import com.twitter.finagle.ServiceFactory
import com.twitter.util.{Future, Time}
import com.twitter.finagle.{ClientConnection, Service}
import com.twitter.util.Future
import com.twitter.finagle.http.Request
import com.twitter.finagle.http.Response
import com.twitter.io.Pipe
import com.twitter.io.Buf
import monix.eval.Task
import cats.effect.ExitCase
import com.twitter.util.Promise
import java.util.concurrent.CancellationException
import monix.execution.Scheduler
import com.twitter.finagle.http.Status
import monix.reactive.Observer
import monix.execution.Ack
import com.twitter.finagle.http.Method
import monix.reactive.Observable

object Main {
  def main(args: Array[String]): Unit = {
    implicit val scheduler = Scheduler.io()
    val service = new GrpcService[Request, Response] {
      //def serve(request: Request): Task[Response] = ???
      override def apply(request: Request): Future[Response] = {
        val writable = new Pipe[Buf]()
        Future.value(Response(request.version, Status.Ok, writable))
      }
    }

    Http.server.withHttp2
      .withStreaming(true)
      .serve("localhost:8080", service)
  }

  abstract class GrpcService[-Req, +Rep](implicit scheduler: Scheduler) extends Service[Req, Rep] {
    val observer = new Observer.Sync[Buf] {
      def onError(ex: Throwable): Unit = ???
      def onComplete(): Unit = ???
      def onNext(elem: Buf): Ack = ???
    }

    def requestToResponse[T, R](req: T): Task[R] = ???
    def requestToResponseStream[T, R](req: T): Observable[R] = ???
    def requestStreamToResponse[T, R](req: Observable[T]): Task[R] = ???
    def requestStreamToResponseStream[T, R](req: Observable[T]): Observable[R] = ???

    //def serve(request: Request): Task[Response]
    def apply(request: Request): Future[Response] = {
      // monixToFuture(serve(request))
      val writable = new Pipe[Buf]()
      Future.value(Response(request.version, Status.Ok, writable))
    }

    implicit final def monixToFuture[T](t: Task[T]): Future[T] = {
      val promise = new Promise[T]()
      val monixTask = t.map(result => promise.setValue(result)).guaranteeCase {
        case ExitCase.Completed => Task.unit
        case ExitCase.Canceled => Task(promise.setException(new CancellationException))
        case ExitCase.Error(err) => Task(promise.setException(err))
      }

      // Run future in the background, callback will complete twitter promise
      monixTask.onCancelRaiseError(new CancellationException).runToFuture
      promise
    }
  }
}
 */
