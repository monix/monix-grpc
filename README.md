# Welcome to monix-grpc

This library allows you to generate [Monix](https://zio.dev) stubs and servers of [gRPC](https://grpc.io/)

## Highlights

* Supports all types of RPCs (unary, client streaming, server streaming, bidirectional).
* Uses Monix's `Observable` to let you easily implement streaming requests.
* Cancellable RPCs: client-side Monix cancellations are propagated to the server to abort the request and save resources.

## Installation

Find the latest snapshot in [here](https://oss.sonatype.org/content/repositories/snapshots/com/thesamet/scalapb/zio-grpc/zio-grpc-core_2.13/).

Add the following to your `project/plugins.sbt`:

    val MonixGrpcVersion = "0.0.1"

    addSbtPlugin("com.thesamet" % "sbt-protoc" % "1.0.16")

    libraryDependencies += "me.vican.jorge" %% "monix-grpc-codegen" % MonixGrpcVersion

Add the following to your `build.sbt`:

    val grpcVersion = "1.36.0"
    
    PB.targets in Compile := Seq(
        scalapb.gen(grpc = false) -> (sourceManaged in Compile).value,
        monix.grpc.codegen.GrpcCodeGenerator -> (sourceManaged in Compile).value,
    )

    libraryDependencies ++= Seq(
        "com.thesamet.scalapb" %% "scalapb-runtime-grpc" % scalapb.compiler.Version.scalapbVersion,
        "io.grpc" % "grpc-netty" % grpcVersion
    )

## Usage

Place your service proto files in `src/main/protobuf`, and the plugin
will generate Scala sources for them.