package org.bitcoins.server.util

import org.apache.pekko.http.scaladsl.Http
import org.bitcoins.commons.util.BitcoinSLogger

import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}

case class ServerBindings(
    httpServer: Http.ServerBinding,
    webSocketServerOpt: Option[Http.ServerBinding]
) extends BitcoinSLogger {

  private val terminateTimeout = 5.seconds

  def stop()(implicit ec: ExecutionContext): Future[Unit] = {
    val stopHttp = httpServer.terminate(terminateTimeout)
    val stopWsFOpt = webSocketServerOpt.map(_.terminate(terminateTimeout))
    for {
      _ <- stopHttp
      _ <- stopWsFOpt match {
        case Some(doneF) =>
          doneF
        case None =>
          Future.unit
      }
    } yield {
      logger.info(s"ServerBindings stopped")
      ()
    }
  }
}
