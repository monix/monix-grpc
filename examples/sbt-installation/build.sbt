lazy val root = project
  .settings(
    Compile / PB.targets := Seq(
      // Sets up the scalapb
      scalapb.gen(grpc = true) -> (Compile / sourceManaged).value / "scalapb",
      // Adds Monix code generator to generate code to same scalapb directory
      monix.grpc.codegen.GrpcCodeGenerator -> (Compile / sourceManaged).value / "scalapb"
    ),

    libraryDependencies ++= Seq(
        // Needed to be able to run a simple grpc application
        "io.grpc" % "grpc-netty" % "1.39.0",
        // Adds scalapb runtime to library dependencies so that generated code works 
        "com.thesamet.scalapb" %% "scalapb-runtime-grpc" % scalapb.compiler.Version.scalapbVersion
    )
  )
