package monix.grpc.runtime.utils

import monix.execution.{Ack, AsyncVar, Scheduler}
import monix.reactive.observers.Subscriber

import scala.concurrent.Future
import monix.execution.CancelableFuture

case class TestSubscriber[T](autoAcc: Boolean)(implicit val scheduler: Scheduler)
    extends Subscriber[T] {

  private[this] var onNextEvents = Seq[T]()
  private[this] var onErrorEvents = Seq[Throwable]()
  private[this] var isComplete: Boolean = false
  private[this] val acks = AsyncVar.empty[Ack]()
  private[this] val nextEvent = AsyncVar.empty[T]()

  def next(): CancelableFuture[T] =
    nextEvent.take()

  def continue(): CancelableFuture[Unit] =
    acks.put(Ack.Continue)

  def stop(): CancelableFuture[Unit] =
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
