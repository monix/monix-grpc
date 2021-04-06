package monix.grpc.runtime.utils

import io.grpc
import io.grpc.Metadata
import io.grpc.ClientCall.Listener

import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import monix.execution.atomic.AtomicBoolean
import monix.reactive.subjects.ConcurrentSubject
import monix.reactive.MulticastStrategy

import scala.concurrent.Promise
import scala.concurrent.duration.DurationInt

class ClientCallMock[Req, Res](var isReadyVal: Boolean = true) extends grpc.ClientCall[Req, Res] {
  case object UsedBeforeStart extends RuntimeException("used before start")

  val hasStarted = AtomicBoolean(false)
  val requestAmount = ConcurrentSubject[Int](MulticastStrategy.replay)
  val sendMessages = ConcurrentSubject[Req](MulticastStrategy.replay)
  val cancel = ConcurrentSubject[(String, Throwable)](MulticastStrategy.replay)
  val halfClosed = AtomicBoolean(false)

  private val listenerVal: Promise[Listener[Res]] = Promise[Listener[Res]]()

  val listener: Task[Listener[Res]] = Task.fromFuture(listenerVal.future)

  override def start(responseListener: Listener[Res], headers: Metadata): Unit = {
    hasStarted.getAndSet(true)
    listenerVal.success(responseListener)
  }

  override def request(numMessages: Int): Unit = {
    if (hasStarted.get()) {
      requestAmount.onNext(numMessages)
    } else {
      requestAmount.onError(UsedBeforeStart)
    }
  }

  override def cancel(message: String, cause: Throwable): Unit = {
    if (hasStarted.get()) {
      cancel.onNext((message, cause))
    } else {
      cancel.onError(UsedBeforeStart)
    }
  }

  override def halfClose(): Unit = {
    if (hasStarted.get()) {
      halfClosed.set(true)
    }
  }

  override def sendMessage(message: Req): Unit = {
    if (hasStarted.get()) {
      sendMessages.onNext(message)
    } else {
      sendMessages.onError(UsedBeforeStart)
    }
  }

  override def isReady(): Boolean = isReadyVal

  def triggerIsReady() =
    listener.map(_.onReady())

  def complete() = {
    Task {
      requestAmount.onComplete()
      sendMessages.onComplete()
      cancel.onComplete()
    }.delayExecution(5.millis)
  }
}
