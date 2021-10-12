package monix.grpc.runtime.client

import io.grpc
import monix.eval.Task

import java.util.concurrent.TimeUnit
import scala.concurrent.duration._

/**
 * Defines a SAM trait that allows implementors to pick which grpc call
 * options should be configured per grpc method instead of uniformly setting
 * the same options for all service stub calls.
 */
abstract class PerMethodClientCallOptions extends (
  grpc.MethodDescriptor[_, _] => Task[grpc.CallOptions]
)

trait ClientCallOptionsApi[Repr] {
  protected def mapCallOptions(f: grpc.CallOptions => Task[grpc.CallOptions]): Repr

  def withCallOptions(callOptions: grpc.CallOptions): Repr =
    mapCallOptions(_ => Task.now(callOptions))

  def withDeadline(deadline: grpc.Deadline): Repr =
    mapCallOptions(opts => Task.now(opts.withDeadline(deadline)))

  def withTimeout(duration: Duration): Repr =
    mapCallOptions(opts => Task.now(opts.withDeadlineAfter(duration.toNanos, TimeUnit.NANOSECONDS)))

  def withTimeoutMillis(millis: Long): Repr =
    withTimeout(millis.millis)

  def withOption[T](key: grpc.CallOptions.Key[T], value: T): Repr =
    mapCallOptions(opts => Task.now(opts.withOption(key, value)))

  def withBufferSize(bufferSize: Int): Repr =
    withOption(ClientCallOptionsApi.clientBufferSize, bufferSize)
}

object ClientCallOptionsApi {
  private[client] val clientBufferSize: grpc.CallOptions.Key[Int] =
    grpc.CallOptions.Key.createWithDefault("clientBufferSize", 0)
}
