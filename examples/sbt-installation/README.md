# Install monix-grpc with sbt

The following README and project shows how you can use monix-grpc with sbt.

You can install monix-grpc by:

1. Enabling `sbt-protoc` in your build and adding Monix's grpc code generator.

    ```scala
    addSbtPlugin("com.thesamet" % "sbt-protoc" % "1.0.4")

    libraryDependencies +=
      "me.vican.jorge" %% "monix-grpc-codegen" % "0.0.0+45-79d91dec+20210625-1618"
    ```

1. Scope the following settings for every sbt project where you have protobuf
   files in `src/main/protobuf`.

    ```scala
    PB.targets in Compile := Seq(
     scalapb.gen(grpc = false) -> (sourceManaged in Compile).value / "scalapb",
     monix.grpc.codegen.GrpcCodeGenerator -> (sourceManaged in Compile).value / "scalapb"
    )
    ```

1. Add `scalapb-runtime-grpc` runtime to your library dependencies so that the
   generated code works.

    ```scala
    libraryDependencies ++= Seq(
        "com.thesamet.scalapb" %% "scalapb-runtime-grpc" % scalapb.compiler.Version.scalapbVersion
    )
    ```

1. If you do not already have `io.grpc.grpc-netty` in your classpath, add it so
   that you can use a concrete grpc-java channel and server APIs.

    ```scala
    libraryDependencies ++= Seq(
        "io.grpc" % "grpc-netty" % "1.39.0"
    )
    ```

1. After that, compiling and running your application will trigger the protobuf
   code generation and the generated code will be accessible from your classpath.
   The following protobuf code:

    ```protobuf
    syntax = "proto3";

    package demo;

    message Request {
        string hello = 1;
    }

    message Response {
        string world = 1;
    }

    service TestService {
        rpc Unary(Request) returns (Response);
    }
    ```

    will generate an abstract trait `demo.testservice.TestServiceApi` that you
    can implement and pass to the grpc-java API.
