package monix.grpc.runtime.server

import io.grpc
import monix.eval.Task
import monix.execution.{AsyncVar, BufferCapacity}
import monix.reactive.Observable

/**
 * Defines a SAM trait that allows implementors to pick which server call
 * options should be configured per grpc method instead of uniformly setting
 * the same options for all implemented methods per service.
 */
abstract class PerMethodServerCallOptions extends (
  grpc.MethodDescriptor[_, _] => Task[ServerCallOptions]
)

/**
 * Defines user-defined configurations for a given server call.
 *
 * Note that this call only keeps track of user-defined values and thus a
 * `None` value for a configuration value is no guarantee that the server
 * call will not have a non-null configuration as grpc-java determines the
 * default values of server call options. If you are not obtaining the
 * expected behavior of a configuration, check the grpc-java defaults.
 */
final class ServerCallOptions private (
    val compressor: Option[grpc.Compressor],
    val bufferSize: Option[Int],
    val enabledMessageCompression: Option[Boolean]
) {

  /**
   * Sets the compressor that the grpc server supports for this server call.
   * Whether the compressor is used or not will depend on the client-server
   * negotiation that happens at the beginning of a call. If both parties
   * support the same algorithm, then this compressor will be honored.
   *
   * Note that by default, all grpc servers support `gzip`.
   */
  def withCompression(compressor: grpc.Compressor): ServerCallOptions =
    new ServerCallOptions(Some(compressor), bufferSize, enabledMessageCompression)

  /**
   * Disables compression for this server call to override the gzip algorithm
   * supported by default by all grpc servers.
   */
  def withNoCompression: ServerCallOptions = {
    val compressor = grpc.Codec.Identity.NONE
    new ServerCallOptions(Some(compressor), bufferSize, enabledMessageCompression)
  }

  /**
   * Sets the size for the internal buffer that will store client messages as
   * long as there is space available. It is recommended that the buffer size
   * has a value that is a power of 2 or it gets rounded to one.
   */
  def withBufferSize(bufferSize: Int): ServerCallOptions = {
    if (bufferSize <= 0) new ServerCallOptions(compressor, None, enabledMessageCompression)
    else new ServerCallOptions(compressor, Some(bufferSize), enabledMessageCompression)
  }

  /**
   * Disables buffering for client messages. Consequently, client messages
   * will only be accepted as soon as the server processes the last message
   * sent by the client. That is, messages are processed one by one.
   */
  def withNoBuffering: ServerCallOptions =
    new ServerCallOptions(compressor, None, enabledMessageCompression)

  /**
   * Enables per-message compression, if an encoding type has been negotiated.
   * If no message encoding has been negotiated, this is a no-op. By default
   * per-message compression is enabled by grpc-java, but may not have any
   * effect if compression is not enabled on the call.
   */
  def withPerMessageCompression: ServerCallOptions =
    new ServerCallOptions(compressor, bufferSize, Some(true))

  /**
   * Disables per-message compression, if an encoding type has been negotiated.
   * If no message encoding has been negotiated, this is a no-op. By default
   * per-message compression is enabled by grpc-java, but may not have any
   * effect if compression is not enabled on the call.
   */
  def withNoPerMessageCompression: ServerCallOptions =
    new ServerCallOptions(compressor, bufferSize, Some(false))
}

object ServerCallOptions {
  def apply(): ServerCallOptions = {
    val bufferSize = monix.execution.internal.Platform.recommendedBufferChunkSize
    // By default, grpc enables message compression and uses gzip compression
    // even if the values we pass here are `None`, keep that in mind
    new ServerCallOptions(None, Some(bufferSize), None) } }
