package monix.grpc.runtime.client

import io.grpc.{Metadata, MethodDescriptor, ServerCall, Status}
import monix.execution.{AsyncVar, Scheduler}
import monix.execution.atomic.AtomicInt
import monix.reactive.MulticastStrategy
import monix.reactive.subjects.{ConcurrentSubject, Subject}

/**
 * Copyright (C) 22.03.21 - REstore NV
 */

final case class ServerCallMock[Request, Response]()(implicit val scheduler: Scheduler)
    extends ServerCall[Request, Response] {
  val requestCount: AtomicInt = AtomicInt.apply(0)

  val requestAmount = ConcurrentSubject[Int](MulticastStrategy.replay)
  val sendMessages = ConcurrentSubject[Response](MulticastStrategy.replay)
  val headers = ConcurrentSubject[Metadata](MulticastStrategy.replay)
  val closeValue = AsyncVar.empty[(Status, Metadata)]()

  def onClose = closeValue.take()

  override def request(numMessages: Int): Unit = {
    requestAmount.onNext(numMessages)
    requestCount.increment(numMessages)
  }

  def cancel = {
    isCancelled = true
  }

  override def sendHeaders(headers: Metadata): Unit = this.headers.onNext(headers)

  override def sendMessage(message: Response): Unit = this.sendMessages.onNext(message)

  override def close(status: Status, trailers: Metadata): Unit = {}

  var isCancelled: Boolean = false

  override def getMethodDescriptor: MethodDescriptor[Request, Response] = ???
}
