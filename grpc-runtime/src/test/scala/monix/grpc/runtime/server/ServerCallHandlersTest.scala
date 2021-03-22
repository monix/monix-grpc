package monix.grpc.runtime.server

import io.grpc.{Metadata, Status}
import monix.eval.Task
import monix.execution.BufferCapacity
import monix.execution.Scheduler.Implicits.global
import monix.grpc.runtime.client.ServerCallMock
import monix.grpc.runtime.server.ServerCallHandlers.StreamingCallListener
import monix.grpc.runtime.utils.TestSubscriber
import munit.FunSuite

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.{Duration, DurationInt, FiniteDuration}

/**
 * Copyright (C) 22.03.21 - REstore NV
 */

class ServerCallHandlersTest extends FunSuite {
  override def munitTimeout: Duration = 1.second

  test("StreamingCallListener initially requests 1 response") {
    val mock = ServerCallMock[Int, Int]()
    val listener =
      new StreamingCallListener[Int, Int](
        ServerCall(mock, ServerCallOptions.default),
        BufferCapacity.Unbounded()
      )
    listener.runStreamingResponseListener(new Metadata())(_ => Task.never)

    mock.requestAmount.firstL
      .map(assertEquals(_, 1))
      .runToFuture
  }

  test("StreamingCallListener requests 1 more element when a new element is being processed") {
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

  test("StreamingCallListener onReadyEffect is triggered onReady") {
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

  test("StreamingCallListener onCancel cancels the rpc call") {
    val mock = ServerCallMock[Int, Int]
    val listener =
      new StreamingCallListener[Int, Int](
        ServerCall(mock, ServerCallOptions.default),
        BufferCapacity.Unbounded()
      )
    listener.runStreamingResponseListener(new Metadata())(_ => Task.never)

    Task(listener.onCancel()).delayExecution(2.milli).runAsyncAndForget
    mock.closeValue
      .take()
      .map { case (status, _) =>
        assertEquals(status, Status.CANCELLED)
      }
  }

}

//case class ServerCallTest[Request, Response](
//    autoAcc: Boolean
//  )(
//    implicit val scheduler: TestScheduler = TestScheduler()
//  ) extends Cancelable {
//  val serverCallMock = new ServerCallMock[Request, Response]()(scheduler)
//  val requests = TestSubscriber[Request](autoAcc)
//  val responses = ConcurrentSubject[Response](autoAcc)
//
//  private var cancelable: Option[Cancelable] = None
//
//  def streamingCall(requests: Observer[Request] = requests, responses: Observable[Response] = responses) = {
//    ServerCallHandlers
//      .streamingToStreamingCall[Request, Response]((r, metadata) => {
//        cancelable = Some(r.subscribe(requests))
//        responses
//      })
//      .startCall(serverCallMock, new Metadata())
//  }
//
//  override def cancel(): Unit = {
//    cancelable.foreach(_.cancel())
//  }
//}
