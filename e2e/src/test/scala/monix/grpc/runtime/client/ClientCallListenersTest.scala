package monix.grpc.runtime.client

import io.grpc.{Metadata, Status}
import io.grpc.Status.Code
import monix.eval.Task
import monix.execution.{AsyncVar, BufferCapacity}
import monix.execution.Scheduler.Implicits.global
import monix.execution.atomic.AtomicInt
import monix.execution.schedulers.TestScheduler
import monix.reactive.Observable
import munit.FunSuite

import scala.concurrent.TimeoutException
import scala.concurrent.duration.DurationInt

class ClientCallListenersTest extends FunSuite {
  test("an unary call listener onReady() should cause the onReadyEffect to unblock") {
    val listener = ClientCallListeners.unary[Int]
    assert(listener.onReadyEffect.tryTake().isEmpty)
    listener.onReady()
    assert(listener.onReadyEffect.tryTake().isDefined)
  }

  test("a streaming call onReady() should cause the onReadyEffect to unblock") {
    val listener = ClientCallListeners.streaming[Int](BufferCapacity.Bounded(128), _ => Task(()))
    assert(listener.onReadyEffect.tryTake().isEmpty)
    listener.onReady()
    assert(listener.onReadyEffect.tryTake().isDefined)
  }

  test("a streaming call onMessage blocks when the queue is full") {
    val listener = ClientCallListeners.streaming[Long](BufferCapacity.Bounded(8), _ => Task(()))
    Observable
      .unfold(1)(s => Some(s, s + 1))
      .mapEval { m =>
        Task
          .apply {
            listener.onMessage(m)
            m
          }
          .timeout(10.milli)
      }
      .onErrorHandleWith { case e: TimeoutException =>
        Observable.empty
      }
      .toListL
      .map { messagesSend =>
        assertEquals(messagesSend.size, 8)
      }
      .runToFuture
  }

  test("a streaming request, request a new element after an element is consumed from the queue") {
    val requestCount = AtomicInt(0)
    val listener =
      ClientCallListeners.streaming[Int](BufferCapacity.Bounded(8), c => Task(requestCount.transform(_ + c)))

    listener.onMessage(1)
    listener.onMessage(2)
    listener.onMessage(3)
    listener.onMessage(4)
    listener.onClose(Status.OK, new Metadata())
    assertEquals(requestCount.get(), 0)
    listener.responses.foreach { x => assertEquals(requestCount.get(), x) }
  }

}
