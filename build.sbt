inThisBuild(
  List(
    scalaVersion := "2.13.3",
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
      "io.grpc" % "grpc-api" % "1.35.0",
      "io.monix" %% "monix" % "3.2.2",
      "com.thesamet.scalapb" %% "scalapb-runtime" % "0.10.8",
      "com.thesamet.scalapb" %% "scalapb-runtime-grpc" % "0.10.1"
    )
  )

lazy val grpcCodeGen = project
  .in(file("grpc-codegen"))
  .enablePlugins(BuildInfoPlugin)
  .settings(releaseSettings)
  .settings(
    name := "monix-grpc-codegen",
    // So that it can used from sbt 1.x...
    scalaVersion := "2.12.12",
    buildInfoKeys := List[BuildInfoKey](name, version, scalaVersion, sbtVersion),
    buildInfoPackage := "monix.grpc.codegen.build",
    name := "monix-grpc-codegen",
    libraryDependencies ++= Seq(
      "com.thesamet.scalapb" %% "compilerplugin" % scalapb.compiler.Version.scalapbVersion
    )
  )

lazy val protocGenMonixGrpc = protocGenProject("protoc-gen-monix-grpc", grpcCodeGen)
  .settings(releaseSettings)
  .settings(
    // So that it can used from sbt 1.x...
    scalaVersion := "2.12.12",
    Compile / mainClass := Some("monix.grpc.codegen.GrpcCodeGenerator")
  )
