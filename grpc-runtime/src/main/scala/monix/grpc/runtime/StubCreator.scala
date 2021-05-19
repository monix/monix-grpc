package monix.grpc.runtime

import io.grpc.Channel
import monix.execution.Scheduler

/**
 * Copyright (C) 19.05.21 - REstore NV
 */

trait StubCreator[T] {
  def stub(channel: Channel)(implicit scheduler: Scheduler): T
}
