package monix.grpc.codegen

import scalapb.compiler.FunctionalPrinter.PrinterEndo
import com.google.protobuf.Descriptors.{FileDescriptor, MethodDescriptor, ServiceDescriptor}
import scalapb.compiler.{DescriptorImplicits, FunctionalPrinter, NameUtils, PrinterEndo, StreamType}

import scala.jdk.CollectionConverters._

class GrpcServicePrinter(
    file: FileDescriptor,
    service: ServiceDescriptor,
    serviceSuffix: String,
    disableMetaData: Boolean,
    implicits: DescriptorImplicits
) {
  import implicits._
  object defs {
    private val monixPkg = "_root_.monix"
    private val grpcPkg = "_root_.io.grpc"
    private val thisPkg = "_root_.monix.grpc.runtime"

    val Error = "String"

    val Task = s"$monixPkg.eval.Task"
    val Observable = s"$monixPkg.reactive.Observable"
    val Scheduler = s"$monixPkg.execution.Scheduler"

    val ClientCall = s"$thisPkg.client.ClientCall"
    val ServerCallHandlers = s"$thisPkg.server.ServerCallHandlers"
    val Channel = s"$grpcPkg.Channel"
    val Metadata = s"$grpcPkg.Metadata"
    val CallOptions = s"$grpcPkg.CallOptions"
    val FailedPrecondition = s"$grpcPkg.Status.FAILED_PRECONDITION"
    val ServerServiceDefinition = s"$grpcPkg.ServerServiceDefinition"

    val serverHandlesModifier = if (disableMetaData) "WithoutMetaData" else ""
  }

  private def companionObject(self: ServiceDescriptor): ScalaName =
    self.getFile.scalaPackage / (self.getName + serviceSuffix)

  private def grpcDescriptor(self: ServiceDescriptor): ScalaName = companionObject(self) / "SERVICE"

  private def grpcDescriptor(method: MethodDescriptor): ScalaName =
    companionObject(method.getService) / s"METHOD_${NameUtils.toAllCaps(method.getName)}"

  private[this] val serverCalls = "_root_.io.grpc.stub.ServerCalls"

  private[this] def methodDescriptor(method: MethodDescriptor) = PrinterEndo { p =>
    def marshaller(t: ExtendedMethodDescriptor#MethodTypeWrapper) =
      if (t.customScalaType.isDefined)
        s"_root_.scalapb.grpc.Marshaller.forTypeMappedType[${t.baseScalaType}, ${t.scalaType}]"
      else
        s"_root_.scalapb.grpc.Marshaller.forMessage[${t.scalaType}]"

    val methodType = method.streamType match {
      case StreamType.Unary => "UNARY"
      case StreamType.ClientStreaming => "CLIENT_STREAMING"
      case StreamType.ServerStreaming => "SERVER_STREAMING"
      case StreamType.Bidirectional => "BIDI_STREAMING"
    }

    val grpcMethodDescriptor = "_root_.io.grpc.MethodDescriptor"

    p.indent
      .add(
        s"""${method.deprecatedAnnotation}val ${method.grpcDescriptor.nameSymbol}: $grpcMethodDescriptor[${method.inputType.scalaType}, ${method.outputType.scalaType}] =
           |  $grpcMethodDescriptor.newBuilder()
           |    .setType($grpcMethodDescriptor.MethodType.$methodType)
           |    .setFullMethodName($grpcMethodDescriptor.generateFullMethodName("${service.getFullName}", "${method.getName}"))
           |    .setSampledToLocalTracing(true)
           |    .setRequestMarshaller(${marshaller(method.inputType)})
           |    .setResponseMarshaller(${marshaller(method.outputType)})
           |    .setSchemaDescriptor(_root_.scalapb.grpc.ConcreteProtoMethodDescriptorSupplier.fromMethodDescriptor(${method.javaDescriptorSource}))
           |    .build()
           |""".stripMargin
      )
      .outdent
  }

  private[this] def serviceDescriptor(service: ServiceDescriptor) = {
    val grpcServiceDescriptor = "_root_.io.grpc.ServiceDescriptor"

    PrinterEndo(
      _.indent
        .add(s"val ${service.grpcDescriptor.nameSymbol}: $grpcServiceDescriptor =")
        .indent
        .add(s"""$grpcServiceDescriptor.newBuilder("${service.getFullName}")""")
        .indent
        .add(
          s""".setSchemaDescriptor(new _root_.scalapb.grpc.ConcreteProtoFileDescriptorSupplier(${service.getFile.fileDescriptorObject.fullName}.javaDescriptor))"""
        )
        .print(service.methods) { case (p, method) =>
          p.add(s".addMethod(${method.grpcDescriptor.nameSymbol})")
        }
        .add(".build()")
        .outdent
        .outdent
        .outdent
        .newline
    )
  }

  private[this] val serviceName = service.name
  private[this] val serviceNameMonix = s"$serviceName$serviceSuffix"
  private[this] val servicePkgName = service.getFile.scalaPackage.fullName

  def printService(printer: FunctionalPrinter): FunctionalPrinter = {
    printer
      .add(s"package $servicePkgName", "")
      .print(file.scalaOptions.getImportList.asScala) { case (printer, i) =>
        printer.add(s"import $i")
      }
      .add(s"trait $serviceNameMonix {")
      .indent
      .seq(service.methods.map(serviceMethodSignature))
      .outdent
      .add("}")
      .newline
      .add(s"object $serviceNameMonix {")
      .indent
      .newline
      .call(generateClientDefinition)
      .newline
      .call(generateBindService)
      .newline
      .call(generateServerDefinition)
      .outdent
      .call(service.methods.map(methodDescriptor): _*)
      .call(serviceDescriptor(service))
      .add("}")
  }

  private def generateClientDefinition: PrinterEndo = p => {
    def methodImpl(method: MethodDescriptor) = { (p: FunctionalPrinter) =>
      val defer =
        if (method.isServerStreaming) s"${defs.Observable}.fromTask(callOptions).flatMap{ opts =>"
        else "callOptions.flatMap{ opts =>"
      p.add(
        serviceMethodSignature(method) + s" = $defer"
      ).indent
        .add(s"${defs.ClientCall}(channel, ${grpcDescriptor(method).fullName}, opts)")
        .indent
        .add(s".${handleMethod(method)}(request ${if (!disableMetaData) ", metadata" else ""})")
        .outdent
        .outdent
        .add("}")
    }

    val stubClassName = "Stub"
    p
      .add(
        s"def stub(channel: ${defs.Channel})(implicit scheduler: ${defs.Scheduler}): $stubClassName = new $stubClassName(channel)"
      )
      .newline
      .add(
        s"final class $stubClassName(channel: ${defs.Channel}, callOptions: ${defs.Task}[${defs.CallOptions}] = ${defs.Task}(${defs.CallOptions}.DEFAULT))(implicit scheduler: ${defs.Scheduler}) extends $serviceNameMonix with _root_.monix.grpc.runtime.client.ClientCallOptionsApi[$stubClassName]{"
      )
      .indent
      .call(service.methods.map(methodImpl): _*)
      .add(
        s"override protected def mapCallOptions(f: ${defs.CallOptions} => ${defs.Task}[${defs.CallOptions}]): $stubClassName = new $stubClassName(channel, callOptions.flatMap(f))"
      )
      .outdent
      .add("}")
  }

  private def generateBindService: PrinterEndo = p => {
    p.add(
      s"def bindService(impl: $serviceNameMonix)(implicit scheduler: ${defs.Scheduler}): ${defs.ServerServiceDefinition} = {"
    ).indent
      .add(
        s"service(impl)"
      )
      .outdent
      .add("}")
  }

  private def generateServerDefinition: PrinterEndo = p => {
    def serviceBindingImplementation(method: MethodDescriptor): PrinterEndo = { p =>
      val inType = method.inputType.scalaType
      val outType = method.outputType.scalaType
      val descriptor = grpcDescriptor(method).fullName
      val handler =
        s"${defs.ServerCallHandlers}.${handleMethod(method)}${defs.serverHandlesModifier}[$inType, $outType]"

      p.add(
        s".addMethod($descriptor, $handler(impl.${method.name}))"
      )
    }

    p.add(
      s"def service(impl: $serviceNameMonix)(implicit scheduler: ${defs.Scheduler}): ${defs.ServerServiceDefinition} = {"
    ).indent
      .newline
      .newline
      .add(s"${defs.ServerServiceDefinition}")
      .indent
      .add(s".builder(${grpcDescriptor(service).fullName})")
      .call(service.methods.map(serviceBindingImplementation): _*)
      .add(".build()")
      .outdent
      .outdent
      .add("}")
  }

  private def handleMethod(method: MethodDescriptor) = {
    method.streamType match {
      case StreamType.Unary => "unaryToUnaryCall"
      case StreamType.ClientStreaming => "streamingToUnaryCall"
      case StreamType.ServerStreaming => "unaryToStreamingCall"
      case StreamType.Bidirectional => "streamingToStreamingCall"
    }
  }

  private def serviceMethodSignature(method: MethodDescriptor): String = {
    val metadata =
      if (!disableMetaData) s", metadata: ${defs.Metadata} = new ${defs.Metadata}()" else ""
    val scalaInType = method.inputType.scalaType
    val scalaOutType = method.outputType.scalaType

    s"def ${method.name}" + (method.streamType match {
      case StreamType.Unary =>
        s"(request: $scalaInType $metadata): ${defs.Task}[$scalaOutType]"
      case StreamType.ClientStreaming =>
        s"(request: ${defs.Observable}[$scalaInType] $metadata): ${defs.Task}[$scalaOutType]"
      case StreamType.ServerStreaming =>
        s"(request: $scalaInType $metadata): ${defs.Observable}[$scalaOutType]"
      case StreamType.Bidirectional =>
        s"(request: ${defs.Observable}[$scalaInType] $metadata): ${defs.Observable}[$scalaOutType]"
    })
  }

}
