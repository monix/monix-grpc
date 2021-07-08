package monix.grpc

import cats.effect.Resource
import monix.eval.Task
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import java.util.concurrent.TimeUnit
import scala.concurrent.blocking

package object client {

  /**
   * Turns an instance of grpc-java's [[ManagedChannelBuilder]] into a
   * resource of a [[ManagedChannel]] instance. The managed channel is shut
   * down when the resource is released as follows:
   *
   * 1. Shut down the channel and wait until the running calls finish.
   * 2. If calls don't finish within 30s, trigger a forceful shutdown
   *    to force the complete channel termination.
   */
  def makeResource[B <: ManagedChannelBuilder[B]](builder: B): Resource[Task, ManagedChannel] =
    Resource.make(Task(builder.build())) { ch =>
      Task {
        ch.shutdown()
        if (!blocking(ch.awaitTermination(30, TimeUnit.SECONDS)))
          ch.shutdownNow()
      }
    }
}
