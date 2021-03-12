package scalapb.monix.grpc

/**
 * Copyright (C) 11.03.21 - REstore NV
 */

package object testservice {
  case class SilentException() extends RuntimeException("SILENT") {
    override def getStackTrace: Array[StackTraceElement] = Array()
  }

}
