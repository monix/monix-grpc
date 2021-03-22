val Scala213 = "2.13.4"
val Scala212 = "2.12.12"

ThisBuild / scalaVersion := Scala212

inThisBuild(
  List(
    resolvers += Resolver.sonatypeRepo("snapshots"),
    organization := "me.vican.jorge",
    homepage := Some(url("https://github.com/jvican/dijon")),
    licenses := Seq("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")),
    scmInfo := Some(
      ScmInfo(
        url("https://github.com/jvican/dijon"),
        "scm:git:git@github.com:jvican/dijon.git"
      )
    ),
    developers := List(
      Developer(
        "jvican",
        "Jorge Vicente Cantero",
        "jorgevc@fastmail.es",
        url("https://jvican.github.io/")
      )
    )
  )
)

val releaseSettings = List(
  releaseEarlyWith := SonatypePublisher,
  publishTo := sonatypePublishToBundle.value
)

lazy val grpcRuntime = project
  .in(file("grpc-runtime"))
  .settings(releaseSettings)
  .settings(
    name := "monix-grpc-runtime",
    crossScalaVersions := List("2.12.12", "2.13.3"),
    libraryDependencies ++= List(
      "io.grpc" % "grpc-api" % "1.36.0",
      "io.grpc" % "grpc-stub" % "1.36.0",
      "io.grpc" % "grpc-protobuf" % "1.36.0",
      "io.monix" %% "monix" % "3.2.2",
      "org.scalameta" %% "munit" % "0.7.22",
      "com.thesamet.scalapb" %% "scalapb-runtime" % "0.10.9"
    )
  )

lazy val grpcCodeGen = projectMatrix
  .in(file("grpc-codegen"))
  .defaultAxes()
  .enablePlugins(BuildInfoPlugin)
  .settings(releaseSettings)
  .settings(
    name := "monix-grpc-codegen",
    buildInfoKeys := List[BuildInfoKey](name, version, scalaVersion, sbtVersion),
    buildInfoPackage := "monix.grpc.codegen.build",
    name := "monix-grpc-codegen",
    libraryDependencies ++= Seq(
      "com.thesamet.scalapb" %% "compilerplugin" % scalapb.compiler.Version.scalapbVersion
    )
  )
  .jvmPlatform(scalaVersions = Seq(Scala212, Scala213))

lazy val codeGenJVM212 = grpcCodeGen.jvm(Scala212)

lazy val protocGenMonixGrpc = protocGenProject("protoc-gen-monix-grpc", codeGenJVM212)
  .settings(releaseSettings)
  .settings(
    scalaVersion := "2.12.12",
    Compile / mainClass := Some("monix.grpc.codegen.GrpcCodeGenerator")
  )

lazy val e2e = project
  .in(file("e2e"))
  .dependsOn(grpcRuntime)
  .enablePlugins(LocalCodeGenPlugin)
  .settings(
    crossScalaVersions := Seq("2.12.12", "2.13.3"),
    skip in publish := true,
    libraryDependencies ++= Seq(
      "com.thesamet.scalapb" %% "scalapb-runtime-grpc" % scalapb.compiler.Version.scalapbVersion,
      "io.grpc" % "grpc-netty" % "1.36.0",
      "org.scalameta" %% "munit" % "0.7.22",
      "com.typesafe.scala-logging" %% "scala-logging" % "3.9.2",
      "org.slf4j" % "slf4j-api" % "1.7.30",
      "org.apache.logging.log4j" % "log4j-slf4j-impl" % "2.13.3"
    ),
    testFrameworks += new TestFramework("munit.Framework"),
    PB.targets in Compile := Seq(
      scalapb.gen(grpc = true) -> (sourceManaged in Compile).value,
      genModule(
        "monix.grpc.codegen.GrpcCodeGenerator$"
      ) -> (sourceManaged in Compile).value
    ),
    PB.protocVersion := "3.13.0",
    codeGenClasspath := (codeGenJVM212 / Compile / fullClasspath).value,
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")
  )
