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
import monix.execution.AsyncVar

class ServerCallTest extends FunSuite {
  override def munitTimeout: Duration = 1.second

  test("requests 1 response after subscribing") {
    val mock = ServerCallMock[Int, Int]()
    val makeOpts = Task.now(ServerCallOptions())
    val listener = new StreamingCallListener[Int, Int](ServerCall(mock))

    listener.runStreamingResponseListener(new Metadata(), makeOpts)(_.lastOptionL.map(_ => ()))

    mock.requestAmount.firstL
      .map(assertEquals(_, 1))
      .runToFuture
  }

  test("can request elements individually that are generated on demand") {
    val syncVar = AsyncVar[Unit](())
    val messagesVar = AsyncVar[Unit](())
    val mock = ServerCallMock[Int, Int]()
    // Configure no buffer size so that each element can be generated individually
    val makeOpts = Task.now(ServerCallOptions().withBufferSize(0))
    val testSubscriber = TestSubscriber[Int](false)
    val listener = new StreamingCallListener[Int, Int](ServerCall(mock))

    listener.runStreamingResponseListener(new Metadata(), makeOpts) { requests =>
      requests.subscribe(testSubscriber)
      syncVar.tryPut(())
      Task.never
    }

    Task.deferFuture(syncVar.take()).>>(Task {
      listener.onMessage(1)
      listener.onMessage(2)
      listener.onMessage(3)
      listener.onMessage(4)
      messagesVar.put(())
    }).runAsyncAndForget

    for {
      _ <- messagesVar.take()
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
      _ <- testSubscriber.next
      _ <- {
        assertEquals(mock.requestCount.get(), 5)
        testSubscriber.continue
      }
    } yield ()
  }

  test("`onReady` triggers `onReadyEffect` (implementation detail)") {
    val mock = ServerCallMock[Int, Int]()
    val makeOpts = Task.now(ServerCallOptions())
    val listener = new StreamingCallListener[Int, Int](ServerCall(mock))
    listener.runStreamingResponseListener(new Metadata(), makeOpts)(_ => Task.never)

    assertEquals(listener.onReadyEffect.tryTake(), None)
    listener.onReady()
    assertEquals(listener.onReadyEffect.tryTake(), Some(()))
  }

  test("server call produces grpc cancellation when task is cancelled") {
    val mock = ServerCallMock[Int, Int]()
    val makeOpts = Task.now(ServerCallOptions())
    val listener = new StreamingCallListener[Int, Int](ServerCall(mock))
    listener.runStreamingResponseListener(new Metadata(), makeOpts)(_ => Task.never)

    Task(listener.onCancel()).delayExecution(2.milli).runAsyncAndForget
    mock.onClose.map { case (status, _) =>
      assertNoDiff(
        status.toString,
        "Status{code=CANCELLED, description=Propagating cancellation because server response handler was cancelled!, cause=null}"
      )
    }
  }
}
