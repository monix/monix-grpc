val Scala213 = "2.13.4"
val Scala212 = "2.12.12"
val Scala3 = "3.0.0"
import scalapb.compiler.Version.grpcJavaVersion
import scalapb.compiler.Version.scalapbVersion
val scalaVersions = Seq(Scala212, Scala213, Scala3)
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
    crossScalaVersions := scalaVersions,
    testFrameworks += new TestFramework("munit.Framework"),
    libraryDependencies ++= List(
      "io.grpc" % "grpc-api" % grpcJavaVersion,
      "io.monix" %% "monix" % "3.4.0",
      "com.thesamet.scalapb" %% "scalapb-runtime" % scalapbVersion,
      "io.grpc" % "grpc-stub" % grpcJavaVersion % Test,
      "io.grpc" % "grpc-protobuf" % grpcJavaVersion % Test,
      "org.scalameta" %% "munit" % "0.7.26" % Test
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
      "com.thesamet.scalapb" %% "compilerplugin" % scalapbVersion
    )
  )
  .jvmPlatform(scalaVersions = scalaVersions)

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
    crossScalaVersions := scalaVersions,
    publish / skip := true,
    libraryDependencies ++= Seq(
      "com.thesamet.scalapb" %% "scalapb-runtime-grpc" % scalapbVersion,
      "io.grpc" % "grpc-netty" % grpcJavaVersion,
      "org.scalameta" %% "munit" % "0.7.26",
      "org.slf4j" % "slf4j-api" % "1.7.30",
      "org.apache.logging.log4j" % "log4j-slf4j-impl" % "2.13.3"
    ),
    testFrameworks += new TestFramework("munit.Framework"),
    Compile / PB.targets := Seq(
      scalapb.gen(grpc = false) -> (Compile / sourceManaged).value,
      genModule(
        "monix.grpc.codegen.GrpcCodeGenerator$"
      ) -> (Compile / sourceManaged).value
    ),
    PB.protocVersion := "3.13.0",
    codeGenClasspath := (codeGenJVM212 / Compile / fullClasspath).value
  )
