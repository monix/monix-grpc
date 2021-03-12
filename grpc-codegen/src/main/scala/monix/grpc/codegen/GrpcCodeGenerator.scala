package monix.grpc.codegen
import scalapb.options.Scalapb
import com.google.protobuf.Descriptors.FileDescriptor
import com.google.protobuf.ExtensionRegistry
import com.google.protobuf.compiler.PluginProtos.CodeGeneratorResponse
import protocbridge.Artifact
import protocgen.{CodeGenApp, CodeGenRequest, CodeGenResponse}
import scalapb.compiler.{DescriptorImplicits, FunctionalPrinter, ProtobufGenerator}

import monix.grpc.codegen.build.BuildInfo

case class CodeGenParams(serviceSuffix: String = "GrpcService")

object GrpcCodeGenerator extends CodeGenApp {
  override def registerExtensions(registry: ExtensionRegistry): Unit =
    Scalapb.registerAllExtensions(registry)

  override def suggestedDependencies: Seq[Artifact] = Seq(
    Artifact("me.vican.jorge", "monix-grpc-runtime", BuildInfo.version, crossVersion = true)
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
    import scala.jdk.CollectionConverters._
    file.getServices.asScala.map { service =>
      import implicits.{FileDescriptorPimp, ServiceDescriptorPimp}

      val p = new GrpcServicePrinter(service, params.serviceSuffix, implicits)
      val code = p.printService(FunctionalPrinter()).result()
      val fileName = s"${file.scalaDirectory}/${service.name}${params.serviceSuffix}.scala"

      CodeGeneratorResponse.File.newBuilder().setName(fileName).setContent(code).build()
    }.toList
  }
}
