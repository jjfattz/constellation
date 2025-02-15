package org.constellation.infrastructure.endpoints

import cats.effect.Concurrent
import cats.syntax.all._
import io.circe.syntax._
import org.constellation.domain.trust.TrustData
import org.constellation.p2p.{Cluster, JoinedHeight, PeerUnregister, SetNodeStatus}
import org.constellation.schema.{Id, NodeState}
import org.constellation.trust.TrustManager
import org.http4s.HttpRoutes
import org.http4s.circe._
import org.http4s.dsl.Http4sDsl
import org.constellation.p2p.Cluster.ClusterNode._
import SetNodeStatus._
import JoinedHeight._
import PeerUnregister._
import TrustData._
import Id._
import org.constellation.domain.cluster.{ClusterStorageAlgebra, NodeStorageAlgebra}
import org.constellation.schema.observation.ObservationEvent

class ClusterEndpoints[F[_]](implicit F: Concurrent[F]) extends Http4sDsl[F] {

  def publicEndpoints(cluster: Cluster[F], trustManager: TrustManager[F]) =
    infoEndpoint(cluster) <+> trustEndpoint(trustManager)

  def peerEndpoints(cluster: Cluster[F], clusterStorage: ClusterStorageAlgebra[F], trustManager: TrustManager[F]) =
    infoEndpoint(cluster) <+>
      setNodeStatusEndpoint(cluster, clusterStorage) <+>
      setJoiningHeightEndpoint(clusterStorage) <+>
      deregisterEndpoint(cluster) <+>
      trustEndpoint(trustManager)

  private def infoEndpoint(cluster: Cluster[F]): HttpRoutes[F] =
    HttpRoutes.of[F] {
      case GET -> Root / "cluster" / "info" =>
        cluster.clusterNodes().map(_.asJson).flatMap(Ok(_))
    }

  private def setNodeStatusEndpoint(cluster: Cluster[F], clusterStorage: ClusterStorageAlgebra[F]): HttpRoutes[F] =
    HttpRoutes.of[F] {
      case req @ POST -> Root / "status" =>
        (for {
          sns <- req.decodeJson[SetNodeStatus]
          _ <- if (sns.nodeStatus == NodeState.Offline) {
            cluster.markOfflinePeer(sns.id)
          } else {
            clusterStorage.setNodeState(sns.id, sns.nodeStatus)
          }
        } yield ()) >> Ok()
    }

  private def setJoiningHeightEndpoint(clusterStorage: ClusterStorageAlgebra[F]): HttpRoutes[F] = HttpRoutes.of[F] {
    case req @ POST -> Root / "joinedHeight" =>
      (for {
        joiningHeight <- req.decodeJson[JoinedHeight]
        _ <- clusterStorage.updateJoinedHeight(joiningHeight)
      } yield ()) >> Ok()
  }

  private def deregisterEndpoint(cluster: Cluster[F]): HttpRoutes[F] = HttpRoutes.of[F] {
    case req @ POST -> Root / "deregister" =>
      (for {
        peerUnregister <- req.decodeJson[PeerUnregister]
        _ <- cluster.deregister(peerUnregister)
      } yield ()) >> Ok()
  }

  private def trustEndpoint(trustManager: TrustManager[F]): HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> Root / "trust" =>
      trustManager.getPredictedReputation.flatMap { predicted =>
        if (predicted.isEmpty) trustManager.getStoredReputation.map(TrustData(_))
        else TrustData(predicted).pure[F]
      }.map(_.asJson).flatMap(Ok(_))
  }
}

object ClusterEndpoints {

  def publicEndpoints[F[_]: Concurrent](cluster: Cluster[F], trustManager: TrustManager[F]): HttpRoutes[F] =
    new ClusterEndpoints[F].publicEndpoints(cluster, trustManager)

  def peerEndpoints[F[_]: Concurrent](
    cluster: Cluster[F],
    clusterStorage: ClusterStorageAlgebra[F],
    trustManager: TrustManager[F]
  ): HttpRoutes[F] =
    new ClusterEndpoints[F].peerEndpoints(cluster, clusterStorage, trustManager)

}
