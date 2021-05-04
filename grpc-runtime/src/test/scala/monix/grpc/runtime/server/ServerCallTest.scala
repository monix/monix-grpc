package monix.grpc.runtime.server

import io.grpc.{Metadata, Status}
import monix.eval.Task
import monix.execution.BufferCapacity
import monix.execution.Scheduler.Implicits.global
import monix.grpc.runtime.client.ServerCallMock
import monix.grpc.runtime.server.ServerCallHandlers.StreamingCallListener
import monix.grpc.runtime.utils.TestSubscriber
import munit.FunSuite

import scala.concurrent.duration.{Duration, DurationInt}

class ServerCallTest extends FunSuite {
  override def munitTimeout: Duration = 1.second

  test("requests 1 response after subscribing") {
    val mock = ServerCallMock[Int, Int]()
    val listener =
      new StreamingCallListener[Int, Int](
        ServerCall(mock, ServerCallOptions.default),
        BufferCapacity.Unbounded()
      )

    listener.runStreamingResponseListener(new Metadata())(_.lastOptionL.map(_ => ()))

    mock.requestAmount.firstL
      .map(assertEquals(_, 1))
      .runToFuture
  }

  test("requests new elements as they are being processed") {
    val mock = ServerCallMock[Int, Int]
    val testSubscriber = TestSubscriber[Int](false)
    val listener =
      new StreamingCallListener[Int, Int](
        ServerCall(mock, ServerCallOptions.default),
        BufferCapacity.Unbounded()
      )

    listener.runStreamingResponseListener(new Metadata()) { requests =>
      requests.subscribe(testSubscriber)
      Task.never
    }

    //race condition the runStreamingResponseListener is not ready directly after the call
    Task {
      listener.onMessage(1)
      listener.onMessage(2)
      listener.onMessage(3)
      listener.onMessage(4)
    }.delayExecution(2.milli).runAsyncAndForget

    for {
      _ <- testSubscriber.next
      _ <- {
        assertEquals(mock.requestCount.get(), 2)
        testSubscriber.continue
      }
      _ <- testSubscriber.next
      _ <- {
        assertEquals(mock.requestCount.get(), 3)
        testSubscriber.continue
      }
      _ <- testSubscriber.next
      _ <- {
        assertEquals(mock.requestCount.get(), 4)
        testSubscriber.continue
      }
    } yield ()
  }

  test("onReadyEffect is triggered onReady") {
    val mock = ServerCallMock[Int, Int]
    val listener =
      new StreamingCallListener[Int, Int](
        ServerCall(mock, ServerCallOptions.default),
        BufferCapacity.Unbounded()
      )
    listener.runStreamingResponseListener(new Metadata())(_ => Task.never)

    assertEquals(listener.onReadyEffect.tryTake(), None)
    listener.onReady()
    assertEquals(listener.onReadyEffect.tryTake(), Some(()))
  }

  test("rpc call is cancelled successfully") {
    val mock = ServerCallMock[Int, Int]
    val listener =
      new StreamingCallListener[Int, Int](
        ServerCall(mock, ServerCallOptions.default),
        BufferCapacity.Unbounded()
      )
    listener.runStreamingResponseListener(new Metadata())(_ => Task.never)

    Task(listener.onCancel()).delayExecution(2.milli).runAsyncAndForget
    mock.onClose.map { case (status, _) =>
      assertNoDiff(
        status.toString,
        "Status{code=CANCELLED, description=Propagating cancellation because server response handler was cancelled!, cause=null}"
      )
    }
  }
}
