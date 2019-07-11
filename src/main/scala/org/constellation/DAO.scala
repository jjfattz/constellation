package org.constellation

import java.util.concurrent.TimeUnit

import akka.pattern.ask
import akka.stream.ActorMaterializer
import akka.util.Timeout
import better.files.File
import cats.effect.concurrent.Semaphore
import cats.effect.{Concurrent, ContextShift, IO}
import com.typesafe.scalalogging.StrictLogging
import constellation._
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import org.constellation.crypto.SimpleWalletLike
import org.constellation.datastore.swaydb.SwayDBDatastore
import org.constellation.p2p.{Cluster, DownloadProcess, SnapshotsDownloader, SnapshotsProcessor}
import org.constellation.primitives.Schema.NodeState.NodeState
import org.constellation.primitives.Schema.NodeType.NodeType
import org.constellation.primitives.Schema._
import org.constellation.primitives._
import org.constellation.primitives.concurrency.SingleRef
import org.constellation.storage._
import org.constellation.storage.transactions.TransactionGossiping
import org.constellation.util.{HealthChecker, HostPort, SnapshotWatcher}

class DAO() extends NodeData with EdgeDAO with SimpleWalletLike with StrictLogging {

  var initialNodeConfig: NodeConfig = _
  @volatile var nodeConfig: NodeConfig = _

  var actorMaterializer: ActorMaterializer = _

  var transactionAcceptedAfterDownload: Long = 0L
  var downloadFinishedTime: Long = System.currentTimeMillis()

  val channelStorage: ChannelStorage = ChannelStorage(this)

  def preventLocalhostAsPeer: Boolean = !nodeConfig.allowLocalhostPeers

  def idDir = File(s"tmp/${id.medium}")

  def dbPath: File = {
    val f = File(s"tmp/${id.medium}/db")
    f.createDirectoryIfNotExists()
    f
  }

  def snapshotHashes: Seq[String] =
    snapshotPath.list.toSeq.map { _.name }

  def peersInfoPath: File = {
    val f = File(s"tmp/${id.medium}/peers")
    f
  }

  def seedsPath: File = {
    val f = File(s"tmp/${id.medium}/seeds")
    f
  }

  @volatile var nodeState: NodeState = NodeState.PendingDownload // TODO: wkoszycki make atomic

  @volatile var nodeType: NodeType = NodeType.Full

  lazy val messageService: MessageService[IO] = {
    implicit val daoImpl: DAO = this
    new MessageService[IO]()
  }

  implicit val unsafeLogger = Slf4jLogger.getLogger[IO]

  val snapshotBroadcastService: SnapshotBroadcastService[IO] = {
    val snapshotProcessor =
      new SnapshotsProcessor(SnapshotsDownloader.downloadSnapshotByDistance)(this, ConstellationExecutionContext.global)
    val downloadProcess = new DownloadProcess(snapshotProcessor)(this, ConstellationExecutionContext.global)
    new SnapshotBroadcastService[IO](new HealthChecker[IO](this, downloadProcess), this)
  }

  val snapshotWatcher = new SnapshotWatcher(snapshotBroadcastService)

  def setNodeState(
    nodeState_ : NodeState
  ): Unit = {
    nodeState = nodeState_
    metrics.updateMetric("nodeState", nodeState.toString)
  }

  def peerHostPort = HostPort(nodeConfig.hostName, nodeConfig.peerHttpPort)

  def initialize(
    nodeConfigInit: NodeConfig = NodeConfig()
  )(implicit materialize: ActorMaterializer = null): Unit = {
    initialNodeConfig = nodeConfigInit
    nodeConfig = nodeConfigInit
    actorMaterializer = materialize
    standardTimeout = Timeout(nodeConfig.defaultTimeoutSeconds, TimeUnit.SECONDS)

    if (nodeConfig.cliConfig.startOfflineMode) {
      nodeState = NodeState.Offline
    }

    if (nodeConfig.isLightNode) {
      nodeType = NodeType.Light
    }

    idDir.createDirectoryIfNotExists(createParents = true)
    messageHashStore = SwayDBDatastore.duplicateCheckStore(this, "message_hash_store")
    checkpointHashStore = SwayDBDatastore.duplicateCheckStore(this, "checkpoint_hash_store")

    rateLimiting = new RateLimiting[IO]

    transactionService = new TransactionService[IO](this)
    transactionGossiping = new TransactionGossiping[IO](transactionService, processingConfig.txGossipingFanout, this)
    checkpointService = new CheckpointService[IO](
      this,
      transactionService,
      messageService,
      notificationService,
      concurrentTipService,
      rateLimiting
    )
    cluster = new Cluster[IO](() => metrics)
    addressService = new AddressService[IO]()(Concurrent(ConstellationConcurrentEffect.edge), () => metrics)

    snapshotService = SnapshotService[IO](
      concurrentTipService,
      addressService,
      checkpointService,
      messageService,
      transactionService,
      rateLimiting,
      snapshotBroadcastService,
      this
    )

    transactionGenerator = TransactionGenerator[IO](addressService, transactionGossiping, transactionService, this)
  }

  implicit val context: ContextShift[IO] = ConstellationContextShift.global

  lazy val concurrentTipService: ConcurrentTipService[IO] = new ConcurrentTipService[IO](
    processingConfig.maxActiveTipsAllowedInMemory,
    processingConfig.maxWidth,
    processingConfig.maxTipUsage,
    processingConfig.numFacilitatorPeers,
    processingConfig.minPeerTimeAddedSeconds,
    this,
    consensusManager
  )

  def pullTips(
    readyFacilitators: Map[Id, PeerData]
  ): Option[PulledTips] =
    concurrentTipService.pull(readyFacilitators)(this.metrics).unsafeRunSync()

  def peerInfo: IO[Map[Id, PeerData]] = IO.async { cb =>
    import scala.util.{Failure, Success}

    (peerManager ? GetPeerInfo)
      .mapTo[Map[Id, PeerData]]
      .onComplete {
        case Success(peerInfo) => cb(Right(peerInfo))
        case Failure(error)    => cb(Left(error))
      }(ConstellationExecutionContext.edge)
  }

  private def eqNodeType(nodeType: NodeType)(m: (Id, PeerData)) = m._2.peerMetadata.nodeType == nodeType
  private def isNodeReady(m: (Id, PeerData)) = m._2.peerMetadata.nodeState == NodeState.Ready

  def peerInfo(nodeType: NodeType): IO[Map[Id, PeerData]] =
    peerInfo.map(_.filter(eqNodeType(nodeType)))

  def readyPeers: IO[Map[Id, PeerData]] =
    peerInfo.map(_.filter(isNodeReady))

  def readyPeers(nodeType: NodeType): IO[Map[Id, PeerData]] =
    readyPeers.map(_.filter(eqNodeType(nodeType)))

  def readyFacilitatorsAsync: IO[Map[Id, PeerData]] =
    readyPeers(NodeType.Full).map(_.filter {
      case (_, pd) =>
        pd.peerMetadata.timeAdded < (System
          .currentTimeMillis() - processingConfig.minPeerTimeAddedSeconds * 1000)
    })
}
