package scalapb.monix.grpc.testservice

final case class SilentException() extends RuntimeException("SILENT") {
  override def fillInStackTrace(): Throwable = this
  override def getStackTrace: Array[StackTraceElement] = Array()
}
