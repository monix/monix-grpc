package com.netflix.monix.grpc.codegen

import protocbridge.ProtocCodeGenerator

import scalapb.options.compiler.Scalapb
import scalapb.compiler.DescriptorImplicits
import scalapb.compiler.FunctionalPrinter
import scalapb.compiler.GeneratorException
import scalapb.compiler.GeneratorParams

import com.google.protobuf.ExtensionRegistry
import com.google.protobuf.Descriptors.FileDescriptor
import com.google.protobuf.compiler.PluginProtos.{CodeGeneratorRequest, CodeGeneratorResponse}

import scala.jdk.CollectionConverters.ListHasAsScala
import scala.jdk.CollectionConverters.BufferHasAsJava

case class CodeGenParams(serviceSuffix: String = "GrpcService")

object GrpcCodeGenerator extends ProtocCodeGenerator {

  override def run(req: Array[Byte]): Array[Byte] = {
    println("Running monix grpc service code generator...")
    val registry = ExtensionRegistry.newInstance()
    Scalapb.registerAllExtensions(registry)
    val request = CodeGeneratorRequest.parseFrom(req, registry)
    generateCodeFrom(request).toByteArray
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
      println(s"Writing generated service file to $fileName")

      CodeGeneratorResponse.File
        .newBuilder()
        .setName(fileName)
        .setContent(code)
        .build()
    }.toList
  }

  def generateCodeFrom(request: CodeGeneratorRequest): CodeGeneratorResponse = {
    lazy val filesByName =
      request.getProtoFileList.asScala.foldLeft(Map.empty[String, FileDescriptor]) {
        case (acc, fp) =>
          val deps = fp.getDependencyList.asScala.map(acc)
          acc + (fp.getName -> FileDescriptor.buildFrom(fp, deps.toArray))
      }

    val b = CodeGeneratorResponse.newBuilder
    parseParams(request.getParameter()) match {
      case Right((params, codeGenParams)) =>
        try {
          val implicits = new DescriptorImplicits(params, filesByName.values.toVector)
          val genFiles = request.getFileToGenerateList.asScala.map(filesByName)
          val srvFiles = genFiles.flatMap(generateServiceFiles(_, codeGenParams, implicits))
          b.addAllFile(srvFiles.asJava)
        } catch {
          case e: GeneratorException =>
            b.setError(e.message)
        }

      case Left(error) =>
        b.setError(error)
    }

    b.build()
  }

  private def parseParams(params: String): Either[String, (GeneratorParams, CodeGenParams)] = {
    for {
      paramsAndUnparsed <- GeneratorParams.fromStringCollectUnrecognized(params)
      params = paramsAndUnparsed._1
      unparsed = paramsAndUnparsed._2
      suffix <- unparsed
        .map(_.split("=", 2).toList)
        .foldLeft[Either[String, CodeGenParams]](Right(CodeGenParams())) {
          case (Right(params), "serviceSuffix" :: suffix :: Nil) =>
            Right(params.copy(serviceSuffix = suffix))
          case (Right(_), xs) => Left(s"Unrecognized parameter: $xs")
          case (Left(e), _) => Left(e)
        }
    } yield (params, suffix)
  }
}
