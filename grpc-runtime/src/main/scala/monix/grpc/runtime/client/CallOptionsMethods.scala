package monix.grpc.runtime.client

import io.grpc.{CallOptions, Deadline}
import monix.eval.Task

import java.util.concurrent.TimeUnit
import scala.concurrent.duration._

trait CallOptionsMethods[Repr] {
  def mapCallOptions(f: CallOptions => Task[CallOptions]): Repr

  def withCallOptions(callOptions: CallOptions): Repr = mapCallOptions(_ => Task.now(callOptions))

  def withDeadline(deadline: Deadline): Repr =
    mapCallOptions(co => Task.now(co.withDeadline(deadline)))

  def withDeadline(duration: Duration): Repr =
    mapCallOptions(co => Task.now(co.withDeadlineAfter(duration.toNanos, TimeUnit.NANOSECONDS)))

  def withDeadlineMillis(millis: Long): Repr = withDeadline(millis.millis)

  def withReceiveBufferSize(numberOfMessages: Int): Repr =
    mapCallOptions(co =>
      Task.now(co.withOption[Int](CallOptionsMethods.receiveBufferSize, numberOfMessages))
    )

  def customOption[T](key: CallOptions.Key[T], value: T): Repr =
    mapCallOptions(co => Task.now(co.withOption(key, value)))
}

object CallOptionsMethods {
  val receiveBufferSize = CallOptions.Key.createWithDefault("clientBufferSize", 1000)
}
