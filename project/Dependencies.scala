import sbt._
object Dependencies {

  object versions {
    val grpc = scalapb.compiler.Version.grpcJavaVersion
    val scalaPb = scalapb.compiler.Version.scalapbVersion
  }

  val Scala213 = "2.13.4"
  val Scala212 = "2.12.12"
  val Scala3 = "3.0.0"

  val scalaVersions = Seq(Scala212, Scala213, Scala3)

  val grpcDepenencies = Seq(
    "io.grpc" % "grpc-api" % versions.grpc,
    "io.grpc" % "grpc-stub" % versions.grpc % Test,
    "io.grpc" % "grpc-protobuf" % versions.grpc % Test,
    "io.grpc" % "grpc-netty" % versions.grpc % Test,
    "com.thesamet.scalapb" %% "scalapb-runtime" % versions.scalaPb,
    "com.thesamet.scalapb" %% "scalapb-runtime-grpc" % versions.scalaPb
  )

  val scalaPbCompiler = "com.thesamet.scalapb" %% "compilerplugin" % versions.scalaPb

  val munit = Seq("org.scalameta" %% "munit" % "0.7.26" % Test)

  val monix = Seq("io.monix" %% "monix" % "3.4.0")

  val logging = Seq(
    "org.slf4j" % "slf4j-api" % "1.7.30",
    "org.apache.logging.log4j" % "log4j-slf4j-impl" % "2.13.3"
  )
}
