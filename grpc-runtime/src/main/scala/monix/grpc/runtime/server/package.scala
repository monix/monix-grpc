package monix.grpc

import cats.effect.Resource
import monix.eval.Task
import io.grpc.ServerBuilder
import io.grpc.Server
import java.util.concurrent.TimeUnit
import scala.concurrent.blocking

package object server {

  /**
   * Turns an instance of grpc-java's [[io.grpc.ServerBuilder]] into a
   * resource of a [[io.grpc.Server]] instance. The server is shut down when
   * the resource is released as follows:
   *
   * 1. Shut down the server by not accepting new calls and waiting until
   *    running calls finish.
   * 2. Wait for the calls to finish in 30s, otherwise a forceful shutdown
   *    is run to force the complete channel termination.
   */
  def makeResource[B <: ServerBuilder[B]](builder: B): Resource[Task, Server] =
    Resource.make(Task(builder.build())) { ch =>
      Task {
        ch.shutdown()
        if (!blocking(ch.awaitTermination(30, TimeUnit.SECONDS)))
          ch.shutdownNow()
      }
    }
}
