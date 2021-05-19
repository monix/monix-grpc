package monix.grpc.runtime.client

import munit.FunSuite

import io.grpc
import io.grpc.ClientCall.Listener
import io.grpc.{Metadata, Status}

import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import monix.execution.atomic.AtomicBoolean
import monix.reactive.subjects.ConcurrentSubject
import monix.reactive.{MulticastStrategy, Observable}
import monix.grpc.runtime.utils.ClientCallMock

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
      assert(mock.halfClosed.get())
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
