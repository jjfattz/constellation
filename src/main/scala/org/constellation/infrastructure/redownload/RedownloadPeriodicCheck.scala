package org.constellation.infrastructure.redownload

import cats.effect.IO
import org.constellation.DAO
import org.constellation.domain.redownload.RedownloadService
import org.constellation.util.Logging.logThread
import org.constellation.util.PeriodicIO

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

class RedownloadPeriodicCheck(
  periodSeconds: Int = 30,
  unboundedExecutionContext: ExecutionContext,
  redownloadService: RedownloadService[IO]
) extends PeriodicIO("RedownloadPeriodicCheck", unboundedExecutionContext) {

  private def triggerRedownloadCheck(): IO[Unit] =
    for {
      _ <- redownloadService.checkForAlignmentWithMajoritySnapshot()
    } yield ()

  override def trigger(): IO[Unit] = logThread(triggerRedownloadCheck(), "triggerRedownloadCheck", logger)

  schedule(periodSeconds seconds)

}
