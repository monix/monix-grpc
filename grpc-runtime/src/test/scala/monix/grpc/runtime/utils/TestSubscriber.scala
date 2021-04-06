package monix.grpc.runtime.utils

import monix.execution.{Ack, AsyncVar, Scheduler}
import monix.reactive.observers.Subscriber

import scala.concurrent.Future

case class TestSubscriber[T](autoAcc: Boolean)(implicit val scheduler: Scheduler)
    extends Subscriber[T] {

  var onNextEvents = Seq[T]()
  var onErrorEvents = Seq[Throwable]()
  var isComplete: Boolean = false
  private val acks = AsyncVar.empty[Ack]()
  val nextEvent = AsyncVar.empty[T]()

  def next =
    nextEvent.take()

  def continue =
    acks.put(Ack.Continue)

  def stop =
    acks.put(Ack.Stop)

  override def onNext(elem: T): Future[Ack] = {
    onNextEvents = onNextEvents :+ elem

    if (autoAcc) {
      Future.successful(Ack.Continue)
    } else {
      nextEvent
        .put(elem)
        .flatMap(_ => acks.take())
    }
  }

  override def onError(ex: Throwable): Unit =
    onErrorEvents = onErrorEvents :+ ex

  override def onComplete(): Unit =
    isComplete = true
}
