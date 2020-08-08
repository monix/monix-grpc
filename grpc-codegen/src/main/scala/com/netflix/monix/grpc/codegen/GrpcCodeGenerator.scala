package com.netflix.monix.grpc.codegen

import scalapb.options.compiler.Scalapb
import scalapb.compiler.ProtobufGenerator
import scalapb.compiler.DescriptorImplicits
import scalapb.compiler.FunctionalPrinter

import com.google.protobuf.ExtensionRegistry
import com.google.protobuf.Descriptors.FileDescriptor
import com.google.protobuf.compiler.PluginProtos.CodeGeneratorResponse

import scala.jdk.CollectionConverters.ListHasAsScala

import protocgen.CodeGenApp
import protocbridge.Artifact
import protocgen.CodeGenRequest
import protocgen.CodeGenResponse

case class CodeGenParams(serviceSuffix: String = "GrpcService")

object GrpcCodeGenerator extends CodeGenApp {
  override def registerExtensions(registry: ExtensionRegistry): Unit =
    Scalapb.registerAllExtensions(registry)

  override def suggestedDependencies: Seq[Artifact] = Seq(
    Artifact("com.netflix.monix.grpc", "grpc-runtime", currentVersion, crossVersion = false)
  )

  override def process(request: CodeGenRequest): CodeGenResponse = {
    ProtobufGenerator.parseParameters(request.parameter) match {
      case Right(params) =>
        val codeGenParams = CodeGenParams()
        val implicits = new DescriptorImplicits(params, request.allProtos)
        val filesWithServices = request.filesToGenerate.collect {
          case file if !file.getServices().isEmpty() => file
        }

        val generated = filesWithServices.flatMap(generateServiceFiles(_, codeGenParams, implicits))
        CodeGenResponse.succeed(generated)

      case Left(error) => CodeGenResponse.fail(error)
    }
  }

  def generateServiceFiles(
      file: FileDescriptor,
      params: CodeGenParams,
      implicits: DescriptorImplicits
  ): Seq[CodeGeneratorResponse.File] = {
    file.getServices.asScala.map { service =>
      import implicits.{ServiceDescriptorPimp, FileDescriptorPimp}

      val p = new GrpcServicePrinter(service, params.serviceSuffix, implicits)
      val code = p.printService(FunctionalPrinter()).result()
      val fileName = s"${file.scalaDirectory}/${service.name}${params.serviceSuffix}.scala"

      CodeGeneratorResponse.File.newBuilder().setName(fileName).setContent(code).build()
    }.toList
  }

  private lazy val currentVersion: String = {
    val props = new java.util.Properties()
    val is = getClass.getResourceAsStream("/version.properties")
    if (is == null) "0.0.0-UNRELEASED"
    else {
      props.load(is)
      Option(props.getProperty("version"))
        .filter(_ != "unspecified")
        .getOrElse("0.0.0-UNRELEASED")
    }
  }
}
