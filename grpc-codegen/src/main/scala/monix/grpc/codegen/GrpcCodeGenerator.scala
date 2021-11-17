package monix.grpc.codegen
import com.google.protobuf.Descriptors.FileDescriptor
import com.google.protobuf.ExtensionRegistry
import com.google.protobuf.compiler.PluginProtos.CodeGeneratorResponse
import protocbridge.Artifact
import protocgen.{CodeGenApp, CodeGenRequest, CodeGenResponse}
import scalapb.compiler.{DescriptorImplicits, FunctionalPrinter, GeneratorParams, ProtobufGenerator}
import scalapb.options.Scalapb
import monix.grpc.codegen.build.BuildInfo

case class CodeGenParams(serviceSuffix: String = "Api", disableMetaData: Boolean = false)

object CodeGenParams {
  def fromString(params: String): (CodeGenParams, String) =
    params
      .split(',')
      .map(_.trim)
      .filter(_.nonEmpty)
      .foldLeft[(CodeGenParams, String)]((CodeGenParams(), "")) {
        case ((params, unknownParameters), str) =>
          str match {
            case "disable_metadata" => (params.copy(disableMetaData = true), str)
            case other => (params, unknownParameters + "," + other)
          }
      }
}

object GrpcCodeGenerator extends CodeGenApp {
  override def registerExtensions(registry: ExtensionRegistry): Unit =
    Scalapb.registerAllExtensions(registry)

  override def suggestedDependencies: Seq[Artifact] = Seq(
    Artifact("me.vican.jorge", "monix-grpc-runtime", BuildInfo.version, crossVersion = true)
  )

  override def process(request: CodeGenRequest): CodeGenResponse = {
    val (codeGenParams, others) = CodeGenParams.fromString(request.parameter)
    ProtobufGenerator.parseParameters(others) match {
      case Right(params) =>
        val implicits = DescriptorImplicits.fromCodeGenRequest(params, request)
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
      import implicits._

      val p = new GrpcServicePrinter(
        file,
        service,
        params.serviceSuffix,
        params.disableMetaData,
        implicits
      )
      val code = p.printService(FunctionalPrinter()).result()
      val fileName = s"${file.scalaDirectory}/${service.name}${params.serviceSuffix}.scala"

      CodeGeneratorResponse.File.newBuilder().setName(fileName).setContent(code).build()
    }.toList
  }
}
