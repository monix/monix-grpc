package monix.grpc

import protocbridge.{Artifact, SandboxedJvmGenerator}
import scalapb.GeneratorOption
import scalapb.GeneratorOption.FlatPackage

/**
  * Copyright (C) 08.11.21 - REstore NV
  */

package object codegen {
  val SandboxedGenerator = SandboxedJvmGenerator.forModule(
    "scala",
    Artifact(
      "me.vican.jorge",
      "monix-grpc-codegen_2.12",
      BuildInfo.version
    ),
    "scalapb.ScalaPbCodeGenerator$",
    GrpcCodeGenerator.suggestedDependencies
  )

  def apply(options: Set[GeneratorOption]): (SandboxedJvmGenerator, Seq[String]) =
    (
      SandboxedGenerator,
      options.map(_.toString).toSeq
    )

  def apply(options: GeneratorOption*): (SandboxedJvmGenerator, Seq[String]) =
    apply(options.toSet)

  def apply(): (SandboxedJvmGenerator, Seq[String]) = apply()

  def apply(
      flatPackage: Boolean = false
    ): (SandboxedJvmGenerator, Seq[String]) = {
    val optionsBuilder = Set.newBuilder[GeneratorOption]
    if (flatPackage) {
      optionsBuilder += FlatPackage
    }
    apply(optionsBuilder.result())
  }
}
