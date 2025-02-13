package org.constellation.infrastructure.endpoints

import cats.data.Validated.{Invalid, Valid}
import cats.effect.{Concurrent, ContextShift}
import cats.syntax.all._
import io.chrisdavenport.log4cats.SelfAwareStructuredLogger
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import io.circe.Encoder
import io.circe.generic.semiauto._
import io.circe.syntax._
import org.constellation.collection.MapUtils._
import org.constellation.domain.cluster.NodeStorageAlgebra
import org.constellation.domain.redownload.{RedownloadService, RedownloadStorageAlgebra}
import org.constellation.domain.snapshot.SnapshotStorageAlgebra
import org.constellation.domain.storage.LocalFileStorage
import org.constellation.gossip.snapshot.SnapshotProposalGossipService
import org.constellation.gossip.state.GossipMessage
import org.constellation.gossip.validation._
import org.constellation.p2p.Cluster
import org.constellation.schema.Id._
import org.constellation.schema.signature.Signed
import org.constellation.schema.snapshot.{
  LatestMajorityHeight,
  SnapshotInfo,
  SnapshotProposal,
  SnapshotProposalPayload,
  StoredSnapshot
}
import org.constellation.schema.{Id, NodeState}
import org.constellation.serialization.KryoSerializer
import org.constellation.session.Registration.`X-Id`
import org.constellation.storage.SnapshotService
import org.constellation.util.Metrics
import org.http4s.circe._
import org.http4s.dsl.Http4sDsl
import org.http4s.{HttpRoutes, Response}

import scala.collection.SortedMap

class SnapshotEndpoints[F[_]](implicit F: Concurrent[F], C: ContextShift[F]) extends Http4sDsl[F] {

  implicit val smEncoder: Encoder[SortedMap[Id, Double]] =
    Encoder.encodeMap[Id, Double].contramap[SortedMap[Id, Double]](_.toMap)

  private val logger: SelfAwareStructuredLogger[F] = Slf4jLogger.getLogger[F]

  def publicEndpoints(
    selfId: Id,
    snapshotStorage: LocalFileStorage[F, StoredSnapshot],
    snapshotService: SnapshotService[F],
    redownloadStorage: RedownloadStorageAlgebra[F]
  ) =
    getStoredSnapshotsEndpoint(snapshotStorage) <+>
      getCreatedSnapshotsEndpoint(redownloadStorage) <+>
      getAcceptedSnapshotsEndpoint(redownloadStorage) <+>
      getPeerProposals(selfId, redownloadStorage) <+>
      queryPeerProposals(selfId, redownloadStorage) <+>
      getNextSnapshotHeight(selfId, snapshotService) <+>
      getLatestMajorityHeight(redownloadStorage) <+>
      getLatestMajorityState(redownloadStorage) <+>
      getTotalSupply(snapshotService)

  def peerEndpoints(
    nodeId: Id,
    snapshotStorage: LocalFileStorage[F, StoredSnapshot],
    snapshotInfoStorage: LocalFileStorage[F, SnapshotInfo],
    snapshotService: SnapshotService[F],
    nodeStorage: NodeStorageAlgebra[F],
    redownloadStorage: RedownloadStorageAlgebra[F],
    snapshotProposalGossipService: SnapshotProposalGossipService[F],
    messageValidator: MessageValidator,
    metrics: Metrics
  ) =
    getStoredSnapshotsEndpoint(snapshotStorage) <+>
      getStoredSnapshotByHash(snapshotStorage) <+>
      getCreatedSnapshotsEndpoint(redownloadStorage) <+>
      getAcceptedSnapshotsEndpoint(redownloadStorage) <+>
      getPeerProposals(nodeId, redownloadStorage) <+>
      queryPeerProposals(nodeId, redownloadStorage) <+>
      getNextSnapshotHeight(nodeId, snapshotService) <+>
      getSnapshotInfo(snapshotService, nodeStorage) <+>
      getSnapshotInfoByHash(snapshotInfoStorage) <+>
      getLatestMajorityHeight(redownloadStorage) <+>
      postSnapshotProposal(snapshotProposalGossipService, redownloadStorage, messageValidator, metrics)

  def ownerEndpoints(
    nodeId: Id,
    snapshotStorage: LocalFileStorage[F, StoredSnapshot],
    redownloadStorage: RedownloadStorageAlgebra[F]
  ) =
    getStoredSnapshotsEndpoint(snapshotStorage) <+>
      getStoredSnapshotByHash(snapshotStorage) <+>
      getCreatedSnapshotsEndpoint(redownloadStorage) <+>
      getAcceptedSnapshotsEndpoint(redownloadStorage) <+>
      getPeerProposals(nodeId, redownloadStorage) <+>
      queryPeerProposals(nodeId, redownloadStorage) <+>
      getLatestMajorityHeight(redownloadStorage)

  private def getStoredSnapshotsEndpoint(snapshotStorage: LocalFileStorage[F, StoredSnapshot]): HttpRoutes[F] =
    HttpRoutes.of[F] {
      case GET -> Root / "snapshot" / "stored" =>
        snapshotStorage.list().rethrowT.map(_.asJson).flatMap(Ok(_))
    }

  private def getStoredSnapshotByHash(snapshotStorage: LocalFileStorage[F, StoredSnapshot]): HttpRoutes[F] =
    HttpRoutes.of[F] {
      case GET -> Root / "snapshot" / "stored" / hash => {
        val getSnapshot = for {
          exists <- snapshotStorage.exists(hash)
          bytes <- if (exists) {
            snapshotStorage.readBytes(hash).rethrowT.map(_.some)
          } else none[Array[Byte]].pure[F]
        } yield bytes

        getSnapshot.flatMap {
          case Some(snapshot) => Ok(snapshot)
          case None           => NotFound()
        }
      }
    }

  private def getCreatedSnapshotsEndpoint(redownloadStorage: RedownloadStorageAlgebra[F]): HttpRoutes[F] =
    HttpRoutes.of[F] {
      case GET -> Root / "snapshot" / "created" =>
        redownloadStorage.getCreatedSnapshots.map(_.asJson).flatMap(Ok(_))
    }

  private def getAcceptedSnapshotsEndpoint(redownloadStorage: RedownloadStorageAlgebra[F]): HttpRoutes[F] =
    HttpRoutes.of[F] {
      case GET -> Root / "snapshot" / "accepted" =>
        redownloadStorage.getAcceptedSnapshots.map(_.asJson).flatMap(Ok(_))
    }

  private def getPeerProposals(ownId: Id, redownloadStorage: RedownloadStorageAlgebra[F]): HttpRoutes[F] =
    HttpRoutes.of[F] {
      case GET -> Root / "peer" / hex / "snapshot" / "created" =>
        val peerId = Id(hex)
        val peerProposals =
          if (peerId == ownId)
            redownloadStorage.getCreatedSnapshots
          else
            redownloadStorage.getPeerProposals(peerId).map(_.getOrElse(Map.empty))

        peerProposals.map(_.asJson).flatMap(Ok(_))
    }

  private def queryPeerProposals(ownId: Id, redownloadStorage: RedownloadStorageAlgebra[F]): HttpRoutes[F] =
    HttpRoutes.of[F] {
      case req @ POST -> Root / "snapshot" / "proposal" / "_query" =>
        for {
          query <- req.decodeJson[List[(Id, Long)]]
          result <- query.traverse {
            case (`ownId`, height) =>
              redownloadStorage.getCreatedSnapshots.map(_.get(height))
            case (peerId, height) =>
              redownloadStorage.getPeerProposals(peerId).map(_.flatMap(_.get(height)))
          }
          resp <- Ok(result.asJson)
        } yield resp
    }

  implicit val idLongEncoder: Encoder[(Id, Long)] = deriveEncoder

  private def getNextSnapshotHeight(nodeId: Id, snapshotService: SnapshotService[F]): HttpRoutes[F] =
    HttpRoutes.of[F] {
      case GET -> Root / "snapshot" / "nextHeight" =>
        snapshotService.getNextHeightInterval.map((nodeId, _)).map(_.asJson).flatMap(Ok(_))
    }

  private def getSnapshotInfo(snapshotService: SnapshotService[F], nodeStorage: NodeStorageAlgebra[F]): HttpRoutes[F] =
    HttpRoutes.of[F] {
      case GET -> Root / "snapshot" / "info" => {
        val getSnapshotInfo = snapshotService.getSnapshotInfo().flatMap { si =>
          F.delay(KryoSerializer.serializeAnyRef(si))
        }

        val result = nodeStorage.getNodeState
          .map(NodeState.canActAsRedownloadSource)
          .ifM(
            getSnapshotInfo.map(_.some),
            none[Array[Byte]].pure[F]
          )

        result.flatMap {
          case Some(snapshotInfo) => Ok(snapshotInfo)
          case None               => ServiceUnavailable()
        }
      }
    }

  private def getSnapshotInfoByHash(snapshotInfoStorage: LocalFileStorage[F, SnapshotInfo]): HttpRoutes[F] =
    HttpRoutes.of[F] {
      case GET -> Root / "snapshot" / "info" / hash => {
        val getSnapshotInfo = for {
          exists <- snapshotInfoStorage.exists(hash)
          bytes <- if (exists) {
            snapshotInfoStorage.readBytes(hash).rethrowT.map(_.some)
          } else none[Array[Byte]].pure[F]
        } yield bytes

        getSnapshotInfo.flatMap {
          case Some(snapshotInfo) => Ok(snapshotInfo)
          case None               => NotFound()
        }
      }
    }

  private def getLatestMajorityHeight(redownloadStorage: RedownloadStorageAlgebra[F]): HttpRoutes[F] =
    HttpRoutes.of[F] {
      case GET -> Root / "latestMajorityHeight" =>
        redownloadStorage.getMajorityRange
          .map(LatestMajorityHeight(_))
          .map(_.asJson)
          .flatMap(Ok(_))
    }

  private[endpoints] def postSnapshotProposal(
    snapshotProposalGossipService: SnapshotProposalGossipService[F],
    redownloadStorage: RedownloadStorageAlgebra[F],
    messageValidator: MessageValidator,
    metrics: Metrics
  ): HttpRoutes[F] =
    HttpRoutes.of[F] {
      case req @ POST -> Root / "peer" / "snapshot" / "created" =>
        for {
          message <- req.as[GossipMessage[SnapshotProposalPayload]]
          payload = message.payload
          senderId = req.headers.get(`X-Id`).map(_.value).map(Id(_)).get

          res <- messageValidator.validateForForward(message, senderId) match {
            case Invalid(EndOfCycle) => snapshotProposalGossipService.finishCycle(message) >> Ok("end of a loop")
            case Invalid(IncorrectReceiverId(_, _)) | Invalid(PathDoesNotStartAndEndWithOrigin) =>
              BadRequest("incorrect receiver or path is not a loop")
            case Invalid(IncorrectSenderId(_)) => Response[F](status = Unauthorized).pure[F]
            case Invalid(e)                    => logger.error(e)(e.getMessage) >> InternalServerError()
            case Valid(_) =>
              val processProposalAsync = F.start(
                C.shift >>
                  redownloadStorage.persistPeerProposal(payload.proposal) >>
                  redownloadStorage.replaceRemoteFilterData(message.origin, payload.filterData) >>
                  updatePeerProposalMetric(metrics, payload.proposal) >>
                  snapshotProposalGossipService.spread(message)
              )

              payload.proposal.validSignature
                .pure[F]
                .ifM(
                  processProposalAsync >> Ok(),
                  BadRequest("snapshot proposal signature validation failed")
                )
          }
        } yield res
    }

  private def updatePeerProposalMetric(metrics: Metrics, proposal: Signed[SnapshotProposal]): F[Unit] =
    metrics.updateMetricAsync(
      "snapshot_lastPeerProposalHeight",
      proposal.value.height,
      Seq(("peerId", proposal.signature.id.hex))
    )

  private def getLatestMajorityState(redownloadStorage: RedownloadStorageAlgebra[F]): HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> Root / "majority" / "state" =>
      redownloadStorage.getLastMajorityState
        .map(_.asJson)
        .flatMap(Ok(_))
  }

  private def getTotalSupply(snapshotService: SnapshotService[F]): HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> Root / "total-supply" =>
      snapshotService
        .getTotalSupply()
        .map(_.asJson)
        .flatMap(Ok(_))
  }
}

object SnapshotEndpoints {

  def publicEndpoints[F[_]: Concurrent: ContextShift](
    nodeId: Id,
    snapshotStorage: LocalFileStorage[F, StoredSnapshot],
    snapshotService: SnapshotService[F],
    redownloadStorage: RedownloadStorageAlgebra[F]
  ): HttpRoutes[F] =
    new SnapshotEndpoints[F]().publicEndpoints(nodeId, snapshotStorage, snapshotService, redownloadStorage)

  def peerEndpoints[F[_]: Concurrent: ContextShift](
    nodeId: Id,
    snapshotStorage: LocalFileStorage[F, StoredSnapshot],
    snapshotInfoStorage: LocalFileStorage[F, SnapshotInfo],
    snapshotService: SnapshotService[F],
    nodeStorage: NodeStorageAlgebra[F],
    redownloadStorage: RedownloadStorageAlgebra[F],
    snapshotProposalGossipService: SnapshotProposalGossipService[F],
    messageValidator: MessageValidator,
    metrics: Metrics
  ): HttpRoutes[F] =
    new SnapshotEndpoints[F]()
      .peerEndpoints(
        nodeId,
        snapshotStorage,
        snapshotInfoStorage,
        snapshotService,
        nodeStorage,
        redownloadStorage,
        snapshotProposalGossipService,
        messageValidator,
        metrics
      )

  def ownerEndpoints[F[_]: Concurrent: ContextShift](
    nodeId: Id,
    snapshotStorage: LocalFileStorage[F, StoredSnapshot],
    redownloadStorage: RedownloadStorageAlgebra[F]
  ): HttpRoutes[F] =
    new SnapshotEndpoints[F]()
      .ownerEndpoints(nodeId, snapshotStorage, redownloadStorage)
}
