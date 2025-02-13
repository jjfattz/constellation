package org.constellation.infrastructure.endpoints

import cats.data.Validated.{Invalid, Valid}
import cats.effect.{Concurrent, ContextShift}
import cats.syntax.all._
import io.chrisdavenport.log4cats.SelfAwareStructuredLogger
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import io.circe.syntax._
import org.constellation.checkpoint.CheckpointService
import org.constellation.domain.checkpointBlock.CheckpointStorageAlgebra
import org.constellation.domain.genesis.GenesisStorageAlgebra
import org.constellation.gossip.checkpoint.CheckpointBlockGossipService
import org.constellation.gossip.state.GossipMessage
import org.constellation.gossip.validation._
import org.constellation.schema.checkpoint.CheckpointBlockPayload
import org.constellation.schema.signature.SignatureRequest
import org.constellation.schema.signature.SignatureRequest._
import org.constellation.schema.signature.SignatureResponse._
import org.constellation.schema.{Height, Id}
import org.constellation.session.Registration.`X-Id`
import org.constellation.storage.SnapshotService
import org.constellation.util.Metrics
import org.http4s.circe._
import org.http4s.dsl.Http4sDsl
import org.http4s.{HttpRoutes, Response}

class CheckpointEndpoints[F[_]](implicit F: Concurrent[F], C: ContextShift[F]) extends Http4sDsl[F] {

  val logger: SelfAwareStructuredLogger[F] = Slf4jLogger.getLogger[F]

  def peerEndpoints(
    genesisStorage: GenesisStorageAlgebra[F],
    checkpointService: CheckpointService[F],
    metrics: Metrics,
    snapshotService: SnapshotService[F],
    checkpointBlockGossipService: CheckpointBlockGossipService[F],
    messageValidator: MessageValidator,
    checkpointStorage: CheckpointStorageAlgebra[F]
  ) =
    genesisEndpoint(genesisStorage) <+>
      getCheckpointEndpoint(checkpointStorage) <+>
      requestBlockSignatureEndpoint(checkpointService) <+>
      postFinishedCheckpoint(
        checkpointBlockGossipService,
        messageValidator,
        snapshotService,
        checkpointService,
        checkpointStorage
      ) <+>
      getAcceptedHashEndpoint(checkpointStorage) <+>
      getAcceptedAtHeightEndpoint(checkpointStorage)

  private def requestBlockSignatureEndpoint(
    checkpointService: CheckpointService[F]
  ): HttpRoutes[F] = HttpRoutes.of[F] {
    case req @ POST -> Root / "request" / "signature" =>
      (for {
        sigRequest <- req.decodeJson[SignatureRequest]
        response <- checkpointService.handleSignatureRequest(sigRequest)
      } yield response.asJson).flatMap(Ok(_))
  }

  private[endpoints] def postFinishedCheckpoint(
    checkpointBlockGossipService: CheckpointBlockGossipService[F],
    messageValidator: MessageValidator,
    snapshotService: SnapshotService[F],
    checkpointService: CheckpointService[F],
    checkpointStorage: CheckpointStorageAlgebra[F]
  ): HttpRoutes[F] =
    HttpRoutes.of[F] {
      case req @ POST -> Root / "peer" / "checkpoint" / "finished" =>
        for {
          message <- req.as[GossipMessage[CheckpointBlockPayload]]
          payload = message.payload
          senderId = req.headers.get(`X-Id`).map(_.value).map(Id(_)).get

          res <- messageValidator.validateForForward(message, senderId) match {
            case Invalid(EndOfCycle) => checkpointBlockGossipService.finishCycle(message) >> Ok("end of a loop")
            case Invalid(IncorrectReceiverId(_, _)) | Invalid(PathDoesNotStartAndEndWithOrigin) =>
              BadRequest("incorrect receiver or path is not a loop")
            case Invalid(IncorrectSenderId(_)) => Response[F](status = Unauthorized).pure[F]
            case Invalid(e)                    => logger.error(e)(e.getMessage) >> InternalServerError()
            case Valid(_) =>
              val accept = checkpointService.addToAcceptance(payload.block)
              val processFinishedCheckpointAsync = F.start(
                C.shift >>
                  snapshotService.getNextHeightInterval.flatMap { nextHeight =>
                    (nextHeight, payload.block.checkpointCacheData.height) match {
                      case (2, _)                                           => accept
                      case (nextHeight, Height(min, _)) if nextHeight > min => F.unit
                      case (_, _)                                           => accept
                    }
                  } >>
                  checkpointBlockGossipService.spread(message)
              )

              processFinishedCheckpointAsync >> Ok()
          }
        } yield res
    }

  def publicEndpoints(checkpointStorage: CheckpointStorageAlgebra[F], genesisStorage: GenesisStorageAlgebra[F]) =
    genesisEndpoint(genesisStorage) <+> getCheckpointEndpoint(checkpointStorage)

  private def genesisEndpoint(genesisStorage: GenesisStorageAlgebra[F]): HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> Root / "genesis" =>
      genesisStorage.getGenesisObservation >>= { go =>
        Ok(go.asJson)
      }
  }

  private def getCheckpointEndpoint(checkpointStorage: CheckpointStorageAlgebra[F]): HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> Root / "checkpoint" / soeHash => checkpointStorage.getCheckpoint(soeHash).map(_.asJson).flatMap(Ok(_))
  }

  private def getAcceptedHashEndpoint(checkpointStorage: CheckpointStorageAlgebra[F]): HttpRoutes[F] =
    HttpRoutes.of[F] {
      case GET -> Root / "checkpoint" / "accepted" / "_hash" =>
        checkpointStorage.getAcceptedHash.map(_.asJson).flatMap(Ok(_))
    }

  private def getAcceptedAtHeightEndpoint(checkpointStorage: CheckpointStorageAlgebra[F]): HttpRoutes[F] =
    HttpRoutes.of[F] {
      case GET -> Root / "checkpoint" / "accepted" / LongVar(height) =>
        checkpointStorage.getAcceptedAtHeight(height).map(_.asJson).flatMap(Ok(_))
    }

}

object CheckpointEndpoints {

  def publicEndpoints[F[_]: Concurrent: ContextShift](
    checkpointStorage: CheckpointStorageAlgebra[F],
    genesisStorage: GenesisStorageAlgebra[F]
  ): HttpRoutes[F] = new CheckpointEndpoints[F]().publicEndpoints(checkpointStorage, genesisStorage)

  def peerEndpoints[F[_]: Concurrent: ContextShift](
    genesisStorage: GenesisStorageAlgebra[F],
    checkpointService: CheckpointService[F],
    metrics: Metrics,
    snapshotService: SnapshotService[F],
    checkpointBlockGossipService: CheckpointBlockGossipService[F],
    messageValidator: MessageValidator,
    checkpointStorage: CheckpointStorageAlgebra[F]
  ): HttpRoutes[F] =
    new CheckpointEndpoints[F]()
      .peerEndpoints(
        genesisStorage,
        checkpointService,
        metrics,
        snapshotService,
        checkpointBlockGossipService,
        messageValidator,
        checkpointStorage
      )
}
