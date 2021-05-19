import Dependencies._

ThisBuild / scalaVersion := Scala212

inThisBuild(
  List(
    resolvers += Resolver.sonatypeRepo("snapshots"),
    resolvers += Resolver.mavenCentral,
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

lazy val grpcRuntime = projectMatrix
  .jvmPlatform(scalaVersions = scalaVersions)
  .in(file("grpc-runtime"))
  .settings(releaseSettings)
  .settings(
    name := "monix-grpc-runtime",
    testFrameworks += new TestFramework("munit.Framework"),
    libraryDependencies ++= grpcDepenencies ++ munit ++ monix
  )

lazy val grpcCodeGen = projectMatrix
  .jvmPlatform(scalaVersions = scalaVersions)
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

lazy val codeGenJVM212 = grpcCodeGen.jvm(Scala212)

lazy val protocGenMonixGrpc = protocGenProject("protoc-gen-monix-grpc", codeGenJVM212)
  .settings(releaseSettings)
  .settings(
    scalaVersion := "2.12.12",
    Compile / mainClass := Some("monix.grpc.codegen.GrpcCodeGenerator")
  )

lazy val e2e = projectMatrix
  .jvmPlatform(scalaVersions = scalaVersions)
  .in(file("e2e"))
  .dependsOn(grpcRuntime)
  .enablePlugins(LocalCodeGenPlugin)
  .settings(
    publish / skip := true,
    libraryDependencies ++= grpcDepenencies ++ munit ++ monix ++ logging,
    testFrameworks += new TestFramework("munit.Framework"),
    Compile / PB.targets := Seq(
      scalapb.gen(grpc = false) -> (Test / sourceManaged).value,
      genModule(
        "monix.grpc.codegen.GrpcCodeGenerator$"
      ) -> (Test / sourceManaged).value
    ),
    PB.protocVersion := "3.13.0",
    codeGenClasspath := (codeGenJVM212 / Compile / fullClasspath).value
  )
