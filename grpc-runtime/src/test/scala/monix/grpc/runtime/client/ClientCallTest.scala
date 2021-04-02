package monix.grpc.runtime.client

import io.grpc
import io.grpc.ClientCall.Listener
import io.grpc.{Metadata, Status}
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import monix.execution.atomic.AtomicBoolean
import monix.reactive.subjects.ConcurrentSubject
import monix.reactive.{MulticastStrategy, Observable}
import munit.FunSuite

import scala.concurrent.Promise
import scala.concurrent.duration.DurationInt

class ClientCallTest extends FunSuite {
  test(
    "unaryToUnaryCall should not start the call unless the response is consumed"
  ) {
    val mock = new ClientCallMock[Int, Int]()
    val call = new ClientCall[Int, Int](mock).unaryToUnaryCall(1, new Metadata())
    assertEquals(mock.hasStarted.get(), false)
  }

  test(
    "unaryToUnaryCall should first start the call before doing any request"
  ) {
    val mock = new ClientCallMock[Int, Int]()
    val test = for {
      received <- new ClientCall[Int, Int](mock).unaryToUnaryCall(1, new Metadata()).start
      listener <- mock.listener
      amountRequested <- mock.requestAmount.firstL
      messageSend <- mock.sendMessages.firstL
      _ = listener.onMessage(2)
      _ = listener.onClose(Status.OK, new Metadata())
      response <- received.join
    } yield {
      assertEquals(amountRequested, 2)
      assertEquals(messageSend, 1)
      assertEquals(response, 2)
      assert(mock.halveClose.get)
    }
    test.runToFuture
  }

  test(
    "unaryToUnaryCall should fail if 2 responses are received"
  ) {
    val mock = new ClientCallMock[Int, Int]()
    val result = for {
      received <- new ClientCall[Int, Int](mock).unaryToUnaryCall(1, new Metadata()).start
      listener <- mock.listener
      _ = listener.onMessage(2)
      _ = listener.onMessage(3)
      _ = listener.onClose(Status.OK, new Metadata())
      response <- received.join
    } yield ()

    result
      .redeem(
        e => assertEquals(e.getMessage, "INTERNAL: Too many response messages, expected only one!"),
        _ => fail("should have notified an error")
      )
      .runToFuture
  }

  test(
    "streamingToUnaryCall should not start the call unless the response is consumed"
  ) {
    val mock = new ClientCallMock[Int, Int]()
    val call = new ClientCall[Int, Int](mock).streamingToUnaryCall(Observable(1), new Metadata())
    assertEquals(mock.hasStarted.get(), false)
  }

  test(
    "streamingToStreamingCall first start the call before interacting with the call"
  ) {
    val mock = new ClientCallMock[Int, Int]()
    val received = new ClientCall[Int, Int](mock)
      .streamingToStreamingCall(Observable(1, 2), new Metadata())
      .toListL
      .runToFuture

    val futureAmountRequested = mock.requestAmount.toListL.runToFuture
    val futureMessageSend = mock.sendMessages.toListL.runToFuture

    for {
      listener <- mock.listener.runToFuture
      _ = listener.onMessage(3)
      _ = listener.onMessage(4)
      _ = listener.onClose(Status.OK, new Metadata())
      _ <- mock.complete().runToFuture
      amountRequested <- futureAmountRequested
      messageSend <- futureMessageSend
      response <- received
    } yield {
      assertEquals(response, List(3, 4))
      assertEquals(amountRequested, List(1, 1, 1))
      assertEquals(messageSend, List(1, 2))
    }
  }

  test(
    "unaryToStreamingCall first start the call before interacting with the call"
  ) {
    val mock = new ClientCallMock[Int, Int]()
    val received = new ClientCall[Int, Int](mock)
      .unaryToStreamingCall(1, new Metadata())
      .toListL
      .runToFuture

    val futureAmountRequested = mock.requestAmount.toListL.runToFuture
    val futureMessageSend = mock.sendMessages.toListL.runToFuture

    for {
      listener <- mock.listener.runToFuture
      _ = listener.onMessage(3)
      _ = listener.onMessage(4)
      _ = listener.onClose(Status.OK, new Metadata())
      _ <- mock.complete().runToFuture
      amountRequested <- futureAmountRequested
      messageSend <- futureMessageSend
      response <- received
    } yield {
      assertEquals(response, List(3, 4))
      assertEquals(amountRequested, List(1, 1, 1))
      assertEquals(messageSend, List(1))
    }
  }

}

class ClientCallMock[Req, Res](var isReadyVal: Boolean = true) extends grpc.ClientCall[Req, Res] {
  val hasStarted = AtomicBoolean(false)

  val requestAmount = ConcurrentSubject[Int](MulticastStrategy.replay)
  val sendMessages = ConcurrentSubject[Req](MulticastStrategy.replay)
  val cancel = ConcurrentSubject[(String, Throwable)](MulticastStrategy.replay)
  val halveClose = AtomicBoolean(false)

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
      halveClose.set(true)
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
    }.delayExecution(5.milli)
  }
}

case object UsedBeforeStart extends RuntimeException("used before start")
