package com.twitter.finagle.kestrel

import com.twitter.concurrent.{Broker, Offer}
import com.twitter.conversions.time._
import com.twitter.finagle._
import com.twitter.finagle.builder._
import com.twitter.finagle.kestrel.protocol.{Response, Command}
import com.twitter.finagle.stats.{Gauge, NullStatsReceiver, StatsReceiver}
import com.twitter.finagle.thrift.{ClientId, ThriftClientRequest}
import com.twitter.finagle.util.DefaultLogger
import com.twitter.util._
import _root_.java.net.SocketAddress
import _root_.java.util.concurrent.atomic.AtomicInteger
import _root_.java.{util => ju}
import scala.collection.JavaConversions._
import scala.collection.mutable
import scala.util.control.NonFatal

/**
 * Indicates that all [[com.twitter.finagle.kestrel.ReadHandle ReadHandles]]
 * that are backing a given [[com.twitter.finagle.kestrel.MultiReader]] have
 * died.
 */
object AllHandlesDiedException extends Exception

private[finagle] object MultiReaderHelper {

  private[finagle] val logger = DefaultLogger

  private[finagle] def merge(
    readHandles: Var[Try[Set[ReadHandle]]],
    trackOutstandingRequests: Boolean = false,
    statsReceiver: StatsReceiver = NullStatsReceiver
  ): ReadHandle = {
    val error = new Broker[Throwable]
    val messages = new Broker[ReadMessage]
    val close = new Broker[Unit]
    val clusterUpdate = new Broker[Set[ReadHandle]]

    // number of read handles
    @volatile var numReadHandles = 0
    val numReadHandlesGauge = statsReceiver.addGauge("num_read_handles") {
      numReadHandles
    }

    // outstanding reads
    val outstandingReads = new AtomicInteger(0)
    val outstandingReadsGauge = statsReceiver.addGauge("outstanding_reads") {
      outstandingReads.get
    }

    // counters
    val msgStatsReceiver = statsReceiver.scope("messages")
    val receivedCounter = msgStatsReceiver.counter("received")
    val ackCounter = msgStatsReceiver.counter("ack")
    val abortCounter = msgStatsReceiver.counter("abort")

    def trackMessage(msg: ReadMessage): ReadMessage = {
      if (trackOutstandingRequests) {
        receivedCounter.incr()
        outstandingReads.incrementAndGet()
        ReadMessage(
          msg.bytes,
          msg.ack.map { v =>
            ackCounter.incr()
            outstandingReads.decrementAndGet()
            v
          },
          msg.abort.map { v =>
            abortCounter.incr()
            outstandingReads.decrementAndGet()
            v
          }
        )
      } else {
        msg
      }
    }

    def exposeNumReadHandles(handles: Set[ReadHandle]) {
      numReadHandles = handles.size
    }

    def onClose(handles: Set[ReadHandle]) {
      handles foreach { _.close() }
      error ! ReadClosedException
    }

    def loop(handles: Set[ReadHandle]) {
      if (handles.isEmpty) {
        error ! AllHandlesDiedException
        return
      }

      val queues = handles.map { _.messages }.toSeq
      val errors = handles.map { h =>
        h.error map { e =>
          logger.warning(s"Read handle ${_root_.java.lang.System.identityHashCode(h)} " +
            s"encountered exception : ${e.getMessage}")
          h
        }
      }.toSeq

      // We sequence here to ensure that `close` gets priority over reads.
      Offer.prioritize(
        close.recv { _ => onClose(handles) },
        Offer.choose(queues:_*) { m =>
          messages ! trackMessage(m)
          loop(handles)
        },
        Offer.choose(errors:_*) { h =>
          logger.info(s"Closed read handle ${_root_.java.lang.System.identityHashCode(h)} due to " +
            s"it encountered error")
          h.close()
          val newHandles = handles - h
          exposeNumReadHandles(newHandles)
          loop(newHandles)
        },
        clusterUpdate.recv { newHandles =>
          // Close any handles that exist in old set but not the new one.
          (handles &~ newHandles) foreach { h =>
            logger.info(s"Closed read handle ${_root_.java.lang.System.identityHashCode(h)} due " +
              s"to its host disappeared")
            h.close()
          }
          exposeNumReadHandles(newHandles)
          loop(newHandles)
        }
      ).sync()
    }

    // Wait until the ReadHandles set is populated before initializing.
    val readHandlesPopulatedFuture = readHandles.changes.collect[Try[Set[ReadHandle]]] {
      case r@Return(x) if x.nonEmpty => r
    }.toFuture()

    val closeWitness: Future[Closable] = readHandlesPopulatedFuture flatMap {
      // Flatten the Future[Try[T]] to Future[T].
      Future.const
    } map { handles =>
      // Once the cluster is non-empty, start looping and observing updates.
      exposeNumReadHandles(handles)
      loop(handles)

      // Send cluster updates on the appropriate broker.
      val witness = Witness { tsr: Try[Set[ReadHandle]] =>
        synchronized {
          tsr match {
            case Return(newHandles) => clusterUpdate !! newHandles
            case Throw(t) => error !! t
          }
        }
      }

      readHandles.changes.register(witness)
    }

    val closeHandleOf: Offer[Unit] = close.send(()) map { _ =>
      closeWitness onSuccess { _.close() }
    }

    def createReadHandle(
      _messages: Offer[ReadMessage],
      _error: Offer[Throwable],
      _closeHandleOf: Offer[Unit],
      _numReadHandlesGauge: Gauge,
      _outstandingReadsGauge: Gauge
    ): ReadHandle = new ReadHandle {
      val messages = _messages
      val error = _error
      def close() = _closeHandleOf.sync()

      // keep gauge references here since addGauge are weekly referenced.
      val numReadHandlesGauge = _numReadHandlesGauge
      val outstandingReadsGauge = _outstandingReadsGauge
    }

    createReadHandle(messages.recv, error.recv, closeHandleOf, numReadHandlesGauge, outstandingReadsGauge)
  }
}

/**
 * Read from multiple clients in round-robin fashion, "grabby hands"
 * style using Kestrel's memcache protocol.  The load balancing is simple,
 * and falls out naturally from the user of the {{Offer}} mechanism: When
 * there are multiple available messages, round-robin across them.  Otherwise,
 * wait for the first message to arrive.
 *
 * Example with a custom client builder:
 * {{{
 *   val readHandle =
 *     MultiReaderMemcache("/the/path", "the-queue")
 *       .clientBuilder(
 *         ClientBuilder()      /* etc... but do not set hosts or build */
 *           .stack(Kestrel.client
 *             .withSession.acquisitionTimeout(1.minute)
 *             .withRequestTimeout(1.minute)
 *             .withSessionPool.maxValue(1)))
 *       .retryBackoffs(/* Stream[Duration], Timer; optional */)
 *       .build()
 * }}}
 */
object MultiReaderMemcache {
  /**
   * Create a new MultiReader which dispatches requests to `dest` using the memcache protocol.
   *
   * @param dest the name of the destination which requests are dispatched to.
   *             See [[https://twitter.github.io/finagle/guide/Names.html Names]] for more detail.
   * @param queueName the name of the queue to read from
   *
   * TODO: `dest` is eagerly resolved at client creation time, so name resolution does not
   * behave dynamically with respect to local dtabs (unlike
   * [[com.twitter.finagle.factory.BindingFactory]]. In practice this is not a problem since
   * ReadHandle is not on the request path. Weights are discarded.
   */
  def apply(dest: String, queueName: String): MultiReaderBuilderMemcache =
    apply(Resolver.eval(dest), queueName)

  def apply(dest: Name, queueName: String): MultiReaderBuilderMemcache = {
    dest match {
      case Name.Bound(va) => apply(va, queueName)
      case Name.Path(path) => apply(Namer.resolve(path), queueName)
    }
  }

  def apply(va: Var[Addr], queueName: String): MultiReaderBuilderMemcache = {
    val config = MultiReaderConfig[Command, Response](va, queueName)
    new MultiReaderBuilderMemcache(config)
  }
}

/**
 * Read from multiple clients in round-robin fashion, "grabby hands"
 * style using Kestrel's memcache protocol.  The load balancing is simple,
 * and falls out naturally from the user of the {{Offer}} mechanism: When
 * there are multiple available messages, round-robin across them.  Otherwise,
 * wait for the first message to arrive.
 *
 * Example with a custom client builder:
 * {{{
 *   val readHandle =
 *     MultiReaderThriftMux("/the/path", "the-queue")
 *       .clientBuilder(
 *         ClientBuilder()
 *           .stack(ThriftMux.client
 *             .withClientId(ClientId("myClientName"))
 *             .withSession.acquisitionTimeout(1.minute)
 *             .withRequestTimeout(1.minute))
 *       .retryBackoffs(/* Stream[Duration], Timer; optional */)
 *       .build()
 * }}}
 *
 * Example without a customer client builder so clientId passed to apply
 * {{{
 *   val name: com.twitter.finagle.Name = Resolver.eval(...)
 *   val va: Var[Addr] = name.bind()
 *   val readHandle =
 *     MultiReaderThriftMux(va, "the-queue", ClientId("myClientName"))
 *       .retryBackoffs(/* Stream[Duration], Timer; optional */)
 *       .build()
 * }}}
 */
object MultiReaderThriftMux {
  /**
   * Create a new MultiReader which dispatches requests to `dest` using the thriftmux protocol.
   *
   * @param dest the name of the destination which requests are dispatched to.
   *             See [[https://twitter.github.io/finagle/guide/Names.html Names]] for more detail.
   * @param queueName the name of the queue to read from
   * @param clientId the clientid to be used
   */
  def apply(dest: String, queueName: String, clientId: Option[ClientId]): MultiReaderBuilderThriftMux =
    apply(Resolver.eval(dest), queueName, clientId)

  /**
   * Used to create a thriftmux based MultiReader with a ClientId when a custom
   * client builder will not be used.  If a custom client builder will be
   * used then it is more reasonable to use the version of apply that does
   * not take a ClientId or else the client id will need to be passed to
   * both apply and the codec in clientBuilder.
   *
   * @param dest a [[com.twitter.finagle.Name]] representing the Kestrel
   * endpoints to connect to
   * @param queueName the name of the queue to read from
   * @param clientId the clientid to be used
   *
   * @return A MultiReaderBuilderThriftMux
   */
  def apply(dest: Name, queueName: String, clientId: Option[ClientId]): MultiReaderBuilderThriftMux = {
    dest match {
      case Name.Bound(va) => apply(va, queueName, clientId)
      case Name.Path(path) => apply(Namer.resolve(path), queueName, clientId)
    }
  }

  /**
   * Used to create a thriftmux based MultiReader with a ClientId when a custom
   * client builder will not be used.  If a custom client builder will be
   * used then it is more reasonable to use the version of apply that does
   * not take a ClientId or else the client id will need to be passed to
   * both apply and the codec in clientBuilder.
   *
   * @param va endpoints for Kestrel
   * @param queueName the name of the queue to read from
   * @param clientId the clientid to be used
   *
   * @return A MultiReaderBuilderThriftMux
   */
  def apply(
    va: Var[Addr],
    queueName: String,
    clientId: Option[ClientId]
  ): MultiReaderBuilderThriftMux = {
    val config = MultiReaderConfig[ThriftClientRequest, Array[Byte]](va, queueName, clientId)
    new MultiReaderBuilderThriftMux(config)
  }

  /**
   * Used to create a thriftmux based MultiReader when a ClientId will neither
   * not be provided or will be provided to the codec was part of creating
   * a custom client builder.
   * This is provided as a separate method for Java compatability.
   *
   * @param va endpoints for Kestrel
   * @param queueName the name of the queue to read from
   *
   * @return A MultiReaderBuilderThriftMux
   */
  def apply(va: Var[Addr], queueName: String): MultiReaderBuilderThriftMux = {
    this(va, queueName, None)
  }
}

object MultiReaderThrift {
  /**
   * Create a new MultiReader which dispatches requests to `dest` using the thrift protocol.
   *
   * @param dest the name of the destination which requests are dispatched to.
   *             See [[https://twitter.github.io/finagle/guide/Names.html Names]] for more detail.
   * @param queueName the name of the queue to read from
   * @param clientId the clientid to be used
   *
   * TODO: `dest` is eagerly resolved at client creation time, so name resolution does not
   * behave dynamically with respect to local dtabs (unlike
   * [[com.twitter.finagle.factory.BindingFactory]]. In practice this is not a problem since
   * ReadHandle is not on the request path. Weights are discarded.
   */
  def apply(dest: String, queueName: String, clientId: Option[ClientId]): MultiReaderBuilderThrift =
    apply(Resolver.eval(dest), queueName, clientId)

  /**
   * Used to create a thrift based MultiReader with a ClientId when a custom
   * client builder will not be used.  If a custom client builder will be
   * used then it is more reasonable to use the version of apply that does
   * not take a ClientId or else the client id will need to be passed to
   * both apply and the codec in clientBuilder.
   *
   * @param dest a [[com.twitter.finagle.Name]] representing the Kestrel
   * endpoints to connect to
   * @param queueName the name of the queue to read from
   * @param clientId the clientid to be used
   *
   * @return A MultiReaderBuilderThrift
   */
  def apply(dest: Name, queueName: String, clientId: Option[ClientId]): MultiReaderBuilderThrift = {
    dest match {
      case Name.Bound(va) => apply(va, queueName, clientId)
      case Name.Path(path) => apply(Namer.resolve(path), queueName, clientId)
    }
  }

  /**
   * Used to create a thrift based MultiReader with a ClientId when a custom
   * client builder will not be used.  If a custom client builder will be
   * used then it is more reasonable to use the version of apply that does
   * not take a ClientId or else the client id will need to be passed to
   * both apply and the codec in clientBuilder.
   *
   * @param va endpoints for Kestrel
   * @param queueName the name of the queue to read from
   * @param clientId the clientid to be used
   *
   * @return A MultiReaderBuilderThrift
   */
  def apply(
    va: Var[Addr],
    queueName: String,
    clientId: Option[ClientId]
  ): MultiReaderBuilderThrift = {
    val config = MultiReaderConfig[ThriftClientRequest, Array[Byte]](va, queueName, clientId)
    new MultiReaderBuilderThrift(config)
  }

  /**
   * Used to create a thrift based MultiReader when a ClientId will neither
   * not be provided or will be provided to the codec was part of creating
   * a custom client builder.
   * This is provided as a separate method for Java compatability.
   *
   * @param va endpoints for Kestrel
   * @param queueName the name of the queue to read from
   *
   * @return A MultiReaderBuilderThrift
   */
  def apply(va: Var[Addr], queueName: String): MultiReaderBuilderThrift = {
    this(va, queueName, None)
  }
}

/**
 * Read from multiple clients in round-robin fashion, "grabby hands"
 * style.  The load balancing is simple, and falls out naturally from
 * the user of the {{Offer}} mechanism: When there are multiple
 * available messages, round-robin across them.  Otherwise, wait for
 * the first message to arrive.
 *
 * Var[Addr] example:
 * {{{
 *   val name: com.twitter.finagle.Name = Resolver.eval(...)
 *   val va: Var[Addr] = name.bind()
 *   val readHandle =
 *     MultiReader(va, "the-queue")
 *       .clientBuilder(
 *         ClientBuilder()      /* etc... but do not set hosts or build */
 *           .stack(Kestrel.client
 *             .withSession.acquisitionTimeout(1.minute)
 *             .withRequestTimeout(1.minute)
 *             .withSessionPool.maxSize(1)))
 *       .retryBackoffs(/* Stream[Duration], Timer; optional */)
 *       .build()
 * }}}
 */
@deprecated("Use MultiReaderMemcache or MultiReaderThriftMux instead", "6.15.1")
object MultiReader {
  /**
   * Create a Kestrel memcache protocol based builder
   */
  @deprecated("Use MultiReaderMemcache.apply instead", "6.15.1")
  def apply(va: Var[Addr], queueName: String): ClusterMultiReaderBuilder = {
    val config = ClusterMultiReaderConfig(va, queueName)
    new ClusterMultiReaderBuilder(config)
  }

  @deprecated("Use Var[Addr]-based `apply` method", "6.8.2")
  def apply(cluster: Cluster[SocketAddress], queueName: String): ClusterMultiReaderBuilder = {
    val Name.Bound(va) = Name.fromGroup(Group.fromCluster(cluster))
    apply(va, queueName)
  }

  @deprecated("Use Var[Addr]-based `apply` method", "6.8.2")
  def apply(clients: Seq[Client], queueName: String): ReadHandle =
    apply(clients map { _.readReliably(queueName) })

  /**
   * A java friendly interface: we use scala's implicit conversions to
   * feed in a {{java.util.Iterator<ReadHandle>}}
   */
  @deprecated("Use Var[Addr]-based `apply` method", "6.8.2")
  def apply(handles: ju.Iterator[ReadHandle]): ReadHandle =
    MultiReaderHelper.merge(Var.value(Return(handles.toSet)))


  @deprecated("Use Var[Addr]-based `apply` method", "6.8.2")
  def apply(handles: Seq[ReadHandle]): ReadHandle =
    MultiReaderHelper.merge(Var.value(Return(handles.toSet)))

  @deprecated("Use Var[Addr]-based `apply` method", "6.8.2")
  def newBuilder(cluster: Cluster[SocketAddress], queueName: String) = apply(cluster, queueName)

  @deprecated("Use Var[Addr]-based `apply` method", "6.8.2")
  def merge(readHandleCluster: Cluster[ReadHandle]): ReadHandle = {
    val varTrySet = Group.fromCluster(readHandleCluster).set map { Try(_) }
    MultiReaderHelper.merge(varTrySet)
  }
}

/**
 * Multi reader configuration settings
 */
final case class MultiReaderConfig[Req, Rep] private[kestrel](
  private val _va: Var[Addr],
  private val _queueName: String,
  private val _clientId: Option[ClientId] = None,
  private val _txnAbortTimeout: Duration = Duration.Top,
  private val _clientBuilder:
    Option[ClientBuilder[Req, Rep, Nothing, ClientConfig.Yes, ClientConfig.Yes]] = None,
  private val _timer: Option[Timer] = None,
  private val _retryBackoffs: Option[() => Stream[Duration]] = None,
  private val _trackOutstandingRequests: Boolean = false,
  private val _statsReceiver: StatsReceiver = NullStatsReceiver) {

  // Delegators to make a friendly public API
  val va = _va
  val queueName = _queueName
  val clientBuilder = _clientBuilder
  val timer = _timer
  val retryBackoffs = _retryBackoffs
  val clientId = _clientId
  val txnAbortTimeout = _txnAbortTimeout
  val trackOutstandingRequests = _trackOutstandingRequests
  val statsReceiver = _statsReceiver
}

@deprecated("Use MultiReaderConfig[Req, Rep] instead", "6.15.1")
final case class ClusterMultiReaderConfig private[kestrel](
  private val _va: Var[Addr],
  private val _queueName: String,
  private val _clientBuilder:
    Option[ClientBuilder[Command, Response, Nothing, ClientConfig.Yes, ClientConfig.Yes]] = None,
  private val _timer: Option[Timer] = None,
  private val _retryBackoffs: Option[() => Stream[Duration]] = None) {

  // Delegators to make a friendly public API
  val va = _va
  val queueName = _queueName
  val clientBuilder = _clientBuilder
  val timer = _timer
  val retryBackoffs = _retryBackoffs

  /**
   * Convert to MultiReaderConfig[Command, Response] during deprecation
   */
  def toMultiReaderConfig: MultiReaderConfig[Command, Response] = {
    MultiReaderConfig[Command, Response](
      this.va,
      this.queueName,
      None,
      Duration.Top,
      this.clientBuilder,
      this.timer,
      this.retryBackoffs)
  }
}

/**
 * Factory for [[com.twitter.finagle.kestrel.ReadHandle]] instances.
 */
abstract class MultiReaderBuilder[Req, Rep, Builder] private[kestrel](
  config: MultiReaderConfig[Req, Rep]) {
  type ClientBuilderBase = ClientBuilder[Req, Rep, Nothing, ClientConfig.Yes, ClientConfig.Yes]

  private[this] val logger = DefaultLogger

  protected[kestrel] def copy(config: MultiReaderConfig[Req, Rep]): Builder

  protected[kestrel] def withConfig(
      f: MultiReaderConfig[Req, Rep] => MultiReaderConfig[Req, Rep]): Builder = {
    copy(f(config))
  }

  protected[kestrel] def defaultClientBuilder: ClientBuilderBase

  protected[kestrel] def createClient(factory: ServiceFactory[Req, Rep]): Client

  /**
   * Specify the ClientBuilder used to generate client objects. <b>Do not</b> specify the
   * hosts or cluster on the given ClientBuilder, and <b>do not</b> invoke <code>build()</code>
   * on it. You must specify a codec and host
   * connection limit, however.
   */
  def clientBuilder(clientBuilder: ClientBuilderBase): Builder =
    withConfig(_.copy(_clientBuilder = Some(clientBuilder)))

  /**
   * Specify the stream of Durations and Timer used for retry backoffs.
   */
  def retryBackoffs(backoffs: () => Stream[Duration], timer: Timer): Builder =
    withConfig(_.copy(_retryBackoffs = Some(backoffs), _timer = Some(timer)))

  /**
   * Specify the clientId to use, if applicable, for the default builder.
   * If the default client builder is override using {{clientBuilder}} then
   * this clientId has not effect.
   */
  def clientId(clientId: ClientId): Builder =
    withConfig(_.copy(_clientId = Some(clientId)))

  /**
   * Specify whether to track outstanding requests.
   *
   * @param trackOutstandingRequests
   *          flag to track outstanding requests.
   *
   * @return multi reader builder
   */
  def trackOutstandingRequests(trackOutstandingRequests: Boolean): Builder =
    withConfig(_.copy(_trackOutstandingRequests = trackOutstandingRequests))

  /**
   * Specify the statsReceiver to use to expose multi reader stats.
   *
   * @param statsReceiver
   *          stats receiver
   *
   * @return multi reader builder
   */
  def statsReceiver(statsReceiver: StatsReceiver): Builder =
    withConfig(_.copy(_statsReceiver = statsReceiver))

  private[this] def buildReadHandleVar(): Var[Try[Set[ReadHandle]]] = {
    val baseClientBuilder = config.clientBuilder match {
      case Some(clientBuilder) => clientBuilder
      case None => defaultClientBuilder
    }

    // Use a mutable Map so that we can modify it in-place on cluster change.
    val currentHandles = mutable.Map.empty[Address, ReadHandle]

    val event = config.va.changes map {
      case Addr.Bound(addrs, _) => {
        (currentHandles.keySet &~ addrs) foreach { addr =>
          logger.info(s"Host ${addr} left for reading queue ${config.queueName}")
        }
        val newHandles = (addrs &~ currentHandles.keySet) map { addr =>
          val factory = baseClientBuilder
            .addrs(addr)
            .buildFactory()

          val client = createClient(factory)

          val handle = (config.retryBackoffs, config.timer) match {
            case (Some(backoffs), Some(timer)) =>
              client.readReliably(config.queueName, timer, backoffs())
            case _ => client.readReliably(config.queueName)
          }

          handle.error foreach { case NonFatal(cause) =>
            logger.warning(s"Closing service factory for address: ${addr}")
            factory.close()
          }

          logger.info(s"Host ${addr} joined for reading ${config.queueName} " +
            s"(handle = ${_root_.java.lang.System.identityHashCode(handle)}).")

          (addr, handle)
        }

        synchronized {
          currentHandles.retain { case (addr, _) => addrs.contains(addr) }
          currentHandles ++= newHandles
        }

        Return(currentHandles.values.toSet)
      }

      case Addr.Neg =>
        logger.info(s"Address could not be bound while trying to read from ${config.queueName}")
        currentHandles.clear()
        Return(currentHandles.values.toSet)

      case Addr.Pending =>
        // If resolution goes back to pending, it can mean there is a
        // transient problem with service discovery. Keep the existing
        // set.
        logger.info(s"Pending name resolution for reading from ${config.queueName}")
        Return(currentHandles.values.toSet)

      case Addr.Failed(t) => Throw(t)
    }

    Var(Return(Set.empty), event)
  }

  /**
   * Constructs a merged ReadHandle over the members of the configured cluster.
   * The handle is updated as members are added or removed.
   */
  def build(): ReadHandle = MultiReaderHelper.merge(buildReadHandleVar(),
    config.trackOutstandingRequests,
    config.statsReceiver.scope("multireader").scope(config.queueName))
}

abstract class MultiReaderBuilderMemcacheBase[Builder] private[kestrel](
    config: MultiReaderConfig[Command, Response])
  extends MultiReaderBuilder[Command, Response, Builder](config) {
  type MemcacheClientBuilder =
    ClientBuilder[Command, Response, Nothing, ClientConfig.Yes, ClientConfig.Yes]

  protected[kestrel] def defaultClientBuilder: MemcacheClientBuilder =
    ClientBuilder()
      .stack(Kestrel.client
        .withSession.acquisitionTimeout(1.minute)
        .withRequestTimeout(1.minute))

  protected[kestrel] def createClient(factory: ServiceFactory[Command, Response]): Client =
    Client(factory)
}

@deprecated("Use MultiReaderBuilderMemcache instead", "6.15.1")
class ClusterMultiReaderBuilder private[kestrel](config: ClusterMultiReaderConfig)
  extends MultiReaderBuilderMemcacheBase[ClusterMultiReaderBuilder](config.toMultiReaderConfig) {

  private def this(config: MultiReaderConfig[Command, Response]) = this(
    ClusterMultiReaderConfig(config.va, config.queueName, config.clientBuilder, config.timer))

  protected[kestrel] def copy(
      config: MultiReaderConfig[Command, Response]): ClusterMultiReaderBuilder =
    new ClusterMultiReaderBuilder(config)

  protected[kestrel] def copy(config: ClusterMultiReaderConfig): ClusterMultiReaderBuilder =
    new ClusterMultiReaderBuilder(config)

  protected[kestrel] def withConfig(
      f: ClusterMultiReaderConfig => ClusterMultiReaderConfig): ClusterMultiReaderBuilder = {
    copy(f(config))
  }
}

/**
 * Factory for [[com.twitter.finagle.kestrel.ReadHandle]] instances using
 * Kestrel's memcache protocol.
 */
class MultiReaderBuilderMemcache private[kestrel](config: MultiReaderConfig[Command, Response])
  extends MultiReaderBuilderMemcacheBase[MultiReaderBuilderMemcache](config) {

  protected[kestrel] def copy(
      config: MultiReaderConfig[Command, Response]): MultiReaderBuilderMemcache =
    new MultiReaderBuilderMemcache(config)
}



/**
 * Factory for [[com.twitter.finagle.kestrel.ReadHandle]] instances using
 * Kestrel's thriftmux protocol.
 */
class MultiReaderBuilderThriftMux private[kestrel](
    config: MultiReaderConfig[ThriftClientRequest, Array[Byte]])
  extends MultiReaderBuilder[ThriftClientRequest, Array[Byte], MultiReaderBuilderThriftMux](config) {
  type ThriftMuxClientBuilder =
    ClientBuilder[ThriftClientRequest, Array[Byte], Nothing, ClientConfig.Yes, ClientConfig.Yes]

  protected[kestrel] def copy(
      config: MultiReaderConfig[ThriftClientRequest, Array[Byte]]): MultiReaderBuilderThriftMux =
    new MultiReaderBuilderThriftMux(config)

  protected[kestrel] def defaultClientBuilder: ThriftMuxClientBuilder = {
    val stackClient = config.clientId match {
      case Some(id) => ThriftMux.client.withClientId(id)
      case None => ThriftMux.client
    }

    ClientBuilder()
      .stack(stackClient
        .withSession.acquisitionTimeout(1.minute)
        .withRequestTimeout(1.minute))
  }

  protected[kestrel] def createClient(
      factory: ServiceFactory[ThriftClientRequest, Array[Byte]]): Client =
    Client.makeThrift(factory, config.txnAbortTimeout)

  /**
   * While reading items, an open transaction will be auto aborted if not confirmed by the client within the specified
   * timeout.
   */
  def txnAbortTimeout(txnAbortTimeout: Duration) =
    withConfig(_.copy(_txnAbortTimeout = txnAbortTimeout))
}

/**
 * Factory for [[com.twitter.finagle.kestrel.ReadHandle]] instances using
 * Kestrel's thriftmux protocol.
 */
class MultiReaderBuilderThrift private[kestrel](
  config: MultiReaderConfig[ThriftClientRequest, Array[Byte]])
  extends MultiReaderBuilder[ThriftClientRequest, Array[Byte], MultiReaderBuilderThrift](config) {
  type ThriftClientBuilder =
    ClientBuilder[ThriftClientRequest, Array[Byte], Nothing, ClientConfig.Yes, ClientConfig.Yes]

  protected[kestrel] def copy(
    config: MultiReaderConfig[ThriftClientRequest, Array[Byte]]): MultiReaderBuilderThrift =
    new MultiReaderBuilderThrift(config)

  protected[kestrel] def defaultClientBuilder: ThriftClientBuilder = {
    val stackClient = config.clientId match {
      case Some(id) => Thrift.client.withClientId(id)
      case None => Thrift.client
    }

    ClientBuilder()
      .stack(stackClient
        .withSession.acquisitionTimeout(1.minute)
        .withRequestTimeout(1.minute)
        .withSessionPool.maxSize(1))
  }

  protected[kestrel] def createClient(
      factory: ServiceFactory[ThriftClientRequest, Array[Byte]]): Client =
    Client.makeThrift(factory, config.txnAbortTimeout)

  /**
   * While reading items, an open transaction will be auto aborted if not confirmed by the client within the specified
   * timeout.
   */
  def txnAbortTimeout(txnAbortTimeout: Duration) =
    withConfig(_.copy(_txnAbortTimeout = txnAbortTimeout))
}
