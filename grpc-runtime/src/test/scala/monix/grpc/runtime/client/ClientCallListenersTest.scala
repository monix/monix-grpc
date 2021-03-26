package monix.grpc.runtime.client

import io.grpc.{Metadata, Status}
import monix.eval.Task
import monix.execution.BufferCapacity
import monix.execution.Scheduler.Implicits.global
import monix.execution.atomic.AtomicInt
import monix.grpc.runtime.utils.TestSubscriber
import munit.FunSuite

class ClientCallListenersTest extends FunSuite {
  test("an unary call listener onReady() triggers the onReadyEffect") {
    val listener = ClientCallListeners.unary[Int]
    assert(listener.onReadyEffect.tryTake().isEmpty)
    listener.onReady()
    assert(listener.onReadyEffect.tryTake().isDefined)
  }

  test("a streaming call listener onReady() triggers the onReadyEffect") {
    val listener = ClientCallListeners.streaming[Int](BufferCapacity.Bounded(128), _ => Task(()))
    assert(listener.onReadyEffect.tryTake().isEmpty)
    listener.onReady()
    assert(listener.onReadyEffect.tryTake().isDefined)
  }

  test("a streaming call listener requests a new elements when one is taken out for processing") {
    val requestCount = AtomicInt(0)
    val testSubscriber = new TestSubscriber[Int](false)

    val listener = ClientCallListeners
      .streaming[Int](BufferCapacity.Bounded(8), c => Task(requestCount.transform(_ + c)))

    assertEquals(requestCount.get(), 0)
    listener.incomingResponses.subscribe(testSubscriber)
    listener.onMessage(1)
    listener.onMessage(2)
    listener.onMessage(3)
    listener.onClose(Status.OK, new Metadata())

    for {
      _ <- Task(
        assertEquals(requestCount.get(), 2)
      ).runToFuture
      _ <- testSubscriber.next
      _ <- {
        assertEquals(requestCount.get(), 2)
        testSubscriber.continue
      }
      _ <- testSubscriber.next
      _ <- {
        assertEquals(requestCount.get(), 3)
        testSubscriber.continue
      }
      _ <- testSubscriber.next
      _ <- {
        assertEquals(requestCount.get(), 4)
        testSubscriber.continue
      }
    } yield {}
  }

}
