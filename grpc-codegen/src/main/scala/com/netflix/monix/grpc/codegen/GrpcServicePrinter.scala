package com.netflix.monix.grpc.codegen

import scalapb.compiler.FunctionalPrinter.PrinterEndo
import com.google.protobuf.Descriptors.{MethodDescriptor, ServiceDescriptor}
import scalapb.compiler.{DescriptorImplicits, FunctionalPrinter, StreamType}

class GrpcServicePrinter(
    service: ServiceDescriptor,
    serviceSuffix: String,
    implicits: DescriptorImplicits
) {
  import implicits._
  object defs {
    private val monixPkg = "_root_.monix"
    private val grpcPkg = "_root_.io.grpc"
    private val thisPkg = "_root_.com.netflix.monix.grpc.runtime"

    val Ctx = "Ctx"
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

  private[this] val serviceName = service.name
  private[this] val serviceNameMonix = s"$serviceName$serviceSuffix"
  private[this] val servicePkgName = service.getFile.scalaPackage.fullName

  def printService(printer: FunctionalPrinter): FunctionalPrinter = {
    printer
      .add(s"package $servicePkgName", "")
      .add(s"trait $serviceNameMonix[${defs.Ctx}] {")
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
      .add("}")
  }

  private def generateClientStub: PrinterEndo = p => {
    p.add(
        s"def stub(channel: ${defs.Channel}, callOptions: ${defs.CallOptions} = ${defs.CallOptions}.DEFAULT)(implicit scheduler: ${defs.Scheduler}): $serviceNameMonix[${defs.Metadata}] = {"
      )
      .indent
      .add(s"client[${defs.Metadata}](channel, identity, _ => callOptions)")
      .outdent
      .add("}")
  }

  private def generateClientDefinition: PrinterEndo = p => {
    def methodImpl(method: MethodDescriptor) = { (p: FunctionalPrinter) =>
      val deferOwner =
        if (!method.isServerStreaming) defs.Task
        else s"${defs.ObservableOps}"
      p.add(
          serviceMethodSignature(method) + s" = ${deferOwner}.deferAction { implicit scheduler =>"
        )
        .indent
        .add(
          s"val call = ${defs.ClientCall}(channel, ${method.grpcDescriptor.fullName}, processOpts(${defs.CallOptions}.DEFAULT))"
        )
        .add(s"call.${handleMethod(method)}(request, processCtx(ctx))")
        .outdent
        .add("}")
    }

    p.add(
        s"def client[${defs.Ctx}](channel: ${defs.Channel}, processCtx: ${defs.Ctx} => ${defs.Metadata}, processOpts: ${defs.CallOptions} => ${defs.CallOptions} = identity)(implicit scheduler: ${defs.Scheduler}): $serviceNameMonix[${defs.Ctx}] = new $serviceNameMonix[${defs.Ctx}] {"
      )
      .indent
      .call(service.methods.map(methodImpl): _*)
      .outdent
      .add("}")
  }

  private def generateBindService: PrinterEndo = p => {
    p.add(
        s"def bindService(impl: $serviceNameMonix[${defs.Metadata}])(implicit scheduler: ${defs.Scheduler}): ${defs.ServerServiceDefinition} = {"
      )
      .indent
      .add(
        s"service[${defs.Metadata}](impl, metadata => Right[${defs.Error}, ${defs.Metadata}](metadata))"
      )
      .outdent
      .add("}")
  }

  private def generateServerDefinition: PrinterEndo = p => {
    def serviceBindingImplementation(method: MethodDescriptor): PrinterEndo = { p =>
      val inType = method.inputType.scalaType
      val outType = method.outputType.scalaType
      val descriptor = method.grpcDescriptor.fullName
      val handler = s"${defs.ServerCallHandlers}.${handleMethod(method)}[$inType, $outType]"

      val preprocessTask =
        if (!method.isServerStreaming) "makeCtxOrFail(m)"
        else s"${defs.Observable}.fromTask(makeCtxOrFail(m))"

      p.add(
        s".addMethod($descriptor, $handler((r, m) => $preprocessTask.flatMap(impl.${method.name}(r, _))))"
      )
    }

    p.add(
        s"def service[${defs.Ctx}](impl: $serviceNameMonix[${defs.Ctx}], makeCtx: ${defs.Metadata} => Either[${defs.Error}, ${defs.Ctx}])(implicit scheduler: ${defs.Scheduler}): ${defs.ServerServiceDefinition} = {"
      )
      .indent
      .newline
      .add(
        s"val makeCtxOrFail: ${defs.Metadata} => ${defs.Task}[${defs.Ctx}] = ${defs.Task}.fromEither(makeCtx(_).leftMap[Throwable](${defs.FailedPrecondition}.withDescription(_).asRuntimeException()))"
      )
      .newline
      .add(s"${defs.ServerServiceDefinition}")
      .indent
      .add(s".builder(${service.grpcDescriptor.fullName})")
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
    val ctx = s"ctx: ${defs.Ctx}"
    val scalaInType = method.inputType.scalaType
    val scalaOutType = method.outputType.scalaType

    s"def ${method.name}" + (method.streamType match {
      case StreamType.Unary =>
        s"(request: $scalaInType, $ctx): ${defs.Task}[$scalaOutType]"
      case StreamType.ClientStreaming =>
        s"(request: ${defs.Observable}[$scalaInType], $ctx): ${defs.Task}[$scalaOutType]"
      case StreamType.ServerStreaming =>
        s"(request: $scalaInType, $ctx): ${defs.Observable}[$scalaOutType]"
      case StreamType.Bidirectional =>
        s"(request: ${defs.Observable}[$scalaInType], $ctx): ${defs.Observable}[$scalaOutType]"
    })
  }

}
