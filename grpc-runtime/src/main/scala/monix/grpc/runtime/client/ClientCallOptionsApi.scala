package monix.grpc.runtime.client

import io.grpc.{CallOptions, Deadline}
import monix.eval.Task

import java.util.concurrent.TimeUnit
import scala.concurrent.duration._

trait ClientCallOptionsApi[Repr] {
  protected def mapCallOptions(f: CallOptions => Task[CallOptions]): Repr

  def withCallOptions(callOptions: CallOptions): Repr =
    mapCallOptions(_ => Task.now(callOptions))

  def withDeadline(deadline: Deadline): Repr =
    mapCallOptions(opts => Task.now(opts.withDeadline(deadline)))

  def withTimeout(duration: Duration): Repr =
    mapCallOptions(opts => Task.now(opts.withDeadlineAfter(duration.toNanos, TimeUnit.NANOSECONDS)))

  def withTimeoutMillis(millis: Long): Repr =
    withTimeout(millis.millis)

  def withOption[T](key: CallOptions.Key[T], value: T): Repr =
    mapCallOptions(opts => Task.now(opts.withOption(key, value)))

  def withBufferSize(bufferSize: Int): Repr =
    withOption(ClientCallOptionsApi.clientBufferSize, bufferSize)
}

object ClientCallOptionsApi {
  private[client] val clientBufferSize: CallOptions.Key[Int] =
    CallOptions.Key.createWithDefault("clientBufferSize", 0)
}
