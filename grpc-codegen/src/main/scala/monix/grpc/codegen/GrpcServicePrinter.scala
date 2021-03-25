package monix.grpc.codegen

import scalapb.compiler.FunctionalPrinter.PrinterEndo
import com.google.protobuf.Descriptors.{MethodDescriptor, ServiceDescriptor}
import scalapb.compiler.{DescriptorImplicits, FunctionalPrinter, NameUtils, PrinterEndo, StreamType}

class GrpcServicePrinter(
    service: ServiceDescriptor,
    serviceSuffix: String,
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
    val ObservableOps = s"$thisPkg.utils.ObservableOps"

    val Channel = s"$grpcPkg.Channel"
    val Metadata = s"$grpcPkg.Metadata"
    val CallOptions = s"$grpcPkg.CallOptions"
    val FailedPrecondition = s"$grpcPkg.Status.FAILED_PRECONDITION"
    val ServerServiceDefinition = s"$grpcPkg.ServerServiceDefinition"
  }

  private def companionObject(self: ServiceDescriptor): ScalaName =
    self.getFile.scalaPackage / (self.getName + serviceSuffix)

  private def grpcDescriptor(self: ServiceDescriptor): ScalaName = companionObject(self) / "SERVICE"

  private def grpcDescriptor(method: MethodDescriptor): ScalaName =
    companionObject(method.getService) / s"METHOD_${NameUtils.toAllCaps(method.getName)}"

  private[this] val serverCalls = "_root_.io.grpc.stub.ServerCalls"

  private[this] def methodDescriptor(method: MethodDescriptor) = PrinterEndo { p =>
    def marshaller(t: MethodDescriptorPimp#MethodTypeWrapper) =
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

    p.add(
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
  }

  private[this] def serviceDescriptor(service: ServiceDescriptor) = {
    val grpcServiceDescriptor = "_root_.io.grpc.ServiceDescriptor"

    PrinterEndo(
      _.add(s"val ${service.grpcDescriptor.nameSymbol}: $grpcServiceDescriptor =").indent
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
        .newline
    )
  }

  private[this] val serviceName = service.name
  private[this] val serviceNameMonix = s"$serviceName$serviceSuffix"
  private[this] val servicePkgName = service.getFile.scalaPackage.fullName

  def printService(printer: FunctionalPrinter): FunctionalPrinter = {
    printer
      .add(s"package $servicePkgName", "")
      .add(s"trait $serviceNameMonix {")
      .indent
      .seq(service.methods.map(serviceMethodSignature))
      .outdent
      .add("}")
      .newline
      .add(s"object $serviceNameMonix {")
      .indent
      .newline
      .call(generateClientStub)
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

  private def generateClientStub: PrinterEndo = p => {
    p.add(
      s"def stub(channel: ${defs.Channel}, callOptions: ${defs.CallOptions} /*= ${defs.CallOptions}.DEFAULT*/)(implicit scheduler: ${defs.Scheduler}): $serviceNameMonix = {"
    ).indent
      .add(s"client(channel, _ => callOptions)")
      .outdent
      .add("}")
  }

  private def generateClientDefinition: PrinterEndo = p => {
    def methodImpl(method: MethodDescriptor) = { (p: FunctionalPrinter) =>
      val deferOwner =
        if (method.isServerStreaming) s"${defs.ObservableOps}"
        else defs.Task
      p.add(
        serviceMethodSignature(method) + s" = ${deferOwner}.deferAction { implicit scheduler =>"
      ).indent
        .add(
          s"val call = ${defs.ClientCall}(channel, ${grpcDescriptor(method).fullName}, processOpts(${defs.CallOptions}.DEFAULT))"
        )
        .add(s"call.${handleMethod(method)}(request, metadata)")
        .outdent
        .add("}")
    }

    p.add(
      s"def client(channel: ${defs.Channel}, processOpts: ${defs.CallOptions} => ${defs.CallOptions} = identity)(implicit scheduler: ${defs.Scheduler}): $serviceNameMonix = new $serviceNameMonix {"
    ).indent
      .call(service.methods.map(methodImpl): _*)
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
      val handler = s"${defs.ServerCallHandlers}.${handleMethod(method)}[$inType, $outType]"

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
    val metadata = s"metadata: ${defs.Metadata} = new ${defs.Metadata}()"
    val scalaInType = method.inputType.scalaType
    val scalaOutType = method.outputType.scalaType

    s"def ${method.name}" + (method.streamType match {
      case StreamType.Unary =>
        s"(request: $scalaInType, $metadata): ${defs.Task}[$scalaOutType]"
      case StreamType.ClientStreaming =>
        s"(request: ${defs.Observable}[$scalaInType], $metadata): ${defs.Task}[$scalaOutType]"
      case StreamType.ServerStreaming =>
        s"(request: $scalaInType, $metadata): ${defs.Observable}[$scalaOutType]"
      case StreamType.Bidirectional =>
        s"(request: ${defs.Observable}[$scalaInType], $metadata): ${defs.Observable}[$scalaOutType]"
    })
  }

}
