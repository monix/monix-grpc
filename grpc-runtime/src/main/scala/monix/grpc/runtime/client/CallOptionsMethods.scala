package monix.grpc.runtime.client

import io.grpc.{CallOptions, Deadline}
import monix.eval.Task

import java.util.concurrent.TimeUnit
import scala.concurrent.duration._

trait CallOptionsMethods[Repr] {
  def mapCallOptions(f: CallOptions => Task[CallOptions]): Repr

  def withCallOptions(callOptions: CallOptions): Repr = mapCallOptions(_ => Task(callOptions))

  def withDeadline(deadline: Deadline): Repr = mapCallOptions(co => Task(co.withDeadline(deadline)))

  def withTimeout(duration: Duration): Repr =
    mapCallOptions(co => Task(co.withDeadlineAfter(duration.toNanos, TimeUnit.NANOSECONDS)))

  def withTimeoutMillis(millis: Long): Repr = withTimeout(millis.millis)

  def withReceiveBufferSize(numberOfMessages: Int) =
    mapCallOptions(co =>
      Task(co.withOption[Int](CallOptionsMethods.receiveBufferSize, numberOfMessages))
    )

  def customOption[T](key: CallOptions.Key[T], value: T) =
    mapCallOptions(co => Task(co.withOption(key, value)))
}

object CallOptionsMethods {
  val receiveBufferSize = CallOptions.Key.createWithDefault("clientBufferSize", 1000)
}
