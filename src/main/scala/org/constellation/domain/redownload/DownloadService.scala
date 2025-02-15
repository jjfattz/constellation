package org.constellation.domain.redownload

import cats.effect.{Blocker, Concurrent, ContextShift, LiftIO}
import cats.syntax.all._
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import org.constellation.DAO
import org.constellation.checkpoint.{CheckpointService, TopologicalSort}
import org.constellation.domain.checkpointBlock.CheckpointStorageAlgebra
import org.constellation.domain.cluster.{BroadcastService, ClusterStorageAlgebra, NodeStorageAlgebra}
import org.constellation.genesis.Genesis
import org.constellation.infrastructure.p2p.{ClientInterpreter, PeerResponse}
import org.constellation.p2p.Cluster
import org.constellation.schema.NodeState
import org.constellation.schema.snapshot.{SnapshotInfo, StoredSnapshot}
import org.constellation.serialization.KryoSerializer
import org.constellation.storage.SnapshotService
import org.constellation.util.Metrics

import scala.concurrent.ExecutionContext

class DownloadService[F[_]](
  redownloadService: RedownloadService[F],
  redownloadStorage: RedownloadStorageAlgebra[F],
  nodeStorage: NodeStorageAlgebra[F],
  clusterStorage: ClusterStorageAlgebra[F],
  checkpointService: CheckpointService[F],
  checkpointStorage: CheckpointStorageAlgebra[F],
  apiClient: ClientInterpreter[F],
  broadcastService: BroadcastService[F],
  snapshotService: SnapshotService[F],
  genesis: Genesis[F],
  metrics: Metrics,
  boundedExecutionContext: ExecutionContext,
  unboundedBlocker: Blocker
)(
  implicit F: Concurrent[F],
  C: ContextShift[F]
) {

  private val logger = Slf4jLogger.getLogger[F]

  def download(joiningHeight: Long): F[Unit] = {
    val wrappedDownload = for {
      _ <- downloadAndAcceptGenesis()
      _ <- redownloadService.fetchAndUpdatePeersProposals()
      _ <- redownloadService.checkForAlignmentWithMajoritySnapshot(joiningHeight = Some(joiningHeight))
      majorityState <- redownloadStorage.getLastMajorityState
      _ <- if (majorityState.isEmpty)
        fetchAndPersistBlocks()
      else F.unit
      _ <- broadcastService.compareAndSet(NodeState.validDuringDownload, NodeState.Ready)
      _ <- metrics.incrementMetricAsync("downloadFinished_total")
    } yield ()

    (broadcastService.compareAndSet(NodeState.initial, NodeState.ReadyForDownload).flatMap { state =>
      if (state.isNewSet) {
        wrappedDownload.handleErrorWith { error =>
          logger.error(error)(s"Error during download process") >>
            broadcastService.compareAndSet(NodeState.validDuringDownload, NodeState.PendingDownload).void >>
            error.raiseError[F, Unit]
        }
      } else F.unit
    }) >> redownloadService.acceptCheckpointBlocks().rethrowT
  }

  private[redownload] def fetchAndPersistBlocks(): F[Unit] =
    for {
      peers <- clusterStorage.getPeers.map(_.values.toList)
      readyPeers = peers.filter(p => NodeState.canActAsDownloadSource(p.peerMetadata.nodeState))
      clients = readyPeers.map(_.peerMetadata.toPeerClientMetadata).toSet
      _ <- redownloadService.useRandomClient(clients) { client =>
        for {
          snapshotInfoFromMemPool <- redownloadService
            .fetchSnapshotInfo(client)
            .flatMap { snapshotInfo =>
              C.evalOn(boundedExecutionContext)(F.delay {
                KryoSerializer.deserializeCast[SnapshotInfo](snapshotInfo)
              })
            }

          _ <- snapshotService.setSnapshot(snapshotInfoFromMemPool)
        } yield ()
      }
    } yield ()

  private[redownload] def downloadAndAcceptGenesis(): F[Unit] =
    for {
      _ <- logger.debug("Downloading and accepting genesis.")
      _ <- broadcastService
        .broadcast(PeerResponse.run(apiClient.checkpoint.getGenesis(), unboundedBlocker))
        .map(_.values.flatMap(_.toOption))
        .map(_.find(_.nonEmpty).flatten.get)
        .flatMap { go =>
          ContextShift[F].evalOn(boundedExecutionContext)(genesis.acceptGenesis(go))
        }
    } yield ()
}

object DownloadService {

  def apply[F[_]: Concurrent: ContextShift](
    redownloadService: RedownloadService[F],
    redownloadStorage: RedownloadStorageAlgebra[F],
    nodeStorage: NodeStorageAlgebra[F],
    clusterStorage: ClusterStorageAlgebra[F],
    checkpointService: CheckpointService[F],
    checkpointStorage: CheckpointStorageAlgebra[F],
    apiClient: ClientInterpreter[F],
    broadcastService: BroadcastService[F],
    snapshotService: SnapshotService[F],
    genesis: Genesis[F],
    metrics: Metrics,
    boundedExecutionContext: ExecutionContext,
    unboundedBlocker: Blocker
  ): DownloadService[F] =
    new DownloadService[F](
      redownloadService,
      redownloadStorage,
      nodeStorage,
      clusterStorage,
      checkpointService,
      checkpointStorage,
      apiClient,
      broadcastService,
      snapshotService,
      genesis,
      metrics,
      boundedExecutionContext,
      unboundedBlocker
    )
}
