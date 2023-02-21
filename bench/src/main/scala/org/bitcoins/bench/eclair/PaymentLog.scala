package org.bitcoins.bench.eclair

import org.bitcoins.commons.jsonmodels.clightning.CLightningJsonModels.CLightningLookupInvoiceResult
import org.bitcoins.commons.jsonmodels.eclair.WebSocketEvent
import org.bitcoins.commons.jsonmodels.eclair.WebSocketEvent.{
  PaymentFailed,
  PaymentReceived,
  PaymentSent
}
import org.bitcoins.core.seqUtil
import org.bitcoins.crypto.Sha256Digest

import java.io.File
import java.nio.file.{Files, StandardOpenOption}
import java.util.concurrent.ConcurrentHashMap
import java.util.function.BiFunction
import scala.concurrent.Promise
import scala.jdk.CollectionConverters.IterableHasAsJava

object PaymentLog {

  sealed trait Event {
    def completedAt: Long
    def label: String
  }

  case class WSEvent(e: WebSocketEvent) extends Event {

    override def completedAt: Long = e match {
      case PaymentReceived(_, parts) =>
        seqUtil(parts).maxByOption(_.timestamp) match {
          case Some(part) =>
            part.timestamp.toEpochMilli
          case None =>
            throw new RuntimeException(
              s"PaymentReceived but with no parts, got $e")
        }
      case PaymentFailed(_, _, _, timestamp) => timestamp.toEpochMilli
      case _: WebSocketEvent =>
        throw new RuntimeException("Can't extract a timestamp")
    }

    override def label: String = e.getClass.getName.split('$').last
  }

  case class InvoiceResult(ir: CLightningLookupInvoiceResult) extends Event {
    override val completedAt: Long = System.currentTimeMillis()

    override def label: String = ir.status.getClass.getName.split('$').last
  }

  case class Error(ex: Throwable) extends Event {
    override val completedAt: Long = System.currentTimeMillis()

    override def label: String = "error"
  }

  val promises =
    new ConcurrentHashMap[Sha256Digest, Promise[Unit]]()

  def logPaymentHash(paymentHash: Sha256Digest) = {
    promises.putIfAbsent(paymentHash, Promise())
  }

  def removePaymentHash(paymentHash: Sha256Digest) = {
    promises.remove(paymentHash)
  }

  def logWSEvent(event: WebSocketEvent) = {
    val hash: Sha256Digest = event match {
      case PaymentReceived(paymentHash, _) =>
        paymentHash
      case PaymentSent(_, paymentHash, _, _) => paymentHash
      case PaymentFailed(_, paymentHash, _, _) =>
        paymentHash
      case _: WebSocketEvent =>
        throw new RuntimeException("Can't extract payment hash")
    }

    logEvent(WSEvent(event), hash)
  }

  private def logEvent(event: Event, hash: Sha256Digest) = {
    promises.compute(
      hash,
      new BiFunction[Sha256Digest, Promise[Unit], Promise[Unit]] {
        override def apply(
            hash: Sha256Digest,
            old: Promise[Unit]): Promise[Unit] = {
          val promise = if (old == null) {
            Promise[Unit]()
          } else {
            old
          }
          event match {
            case Error(ex) => promise.failure(ex)
            case _         => promise.success(())
          }
          promise
        }
      }
    )
  }

  def logInvoiceResult(result: CLightningLookupInvoiceResult) = {
    val hash = result.payment_hash
    logEvent(InvoiceResult(result), hash)
  }

  def logError(hash: Sha256Digest, ex: Throwable) = {
    logEvent(Error(ex), hash)
  }

  private val logPath = new File("benchmark.csv").toPath
  private var last = System.currentTimeMillis()
  private var settledCount = 0
  private var totalTime = 0L
  private val statBlockSize = 1000

  def logSettled(duration: Long) = synchronized {
    if (settledCount > 0 && settledCount % statBlockSize == 0) {
      val now = System.currentTimeMillis()
      val tps = statBlockSize.toDouble / ((now - last).toDouble / 1000)
      val latency = (totalTime.toDouble / 1000) / statBlockSize.toDouble
      writeString(s"$settledCount,$tps,$latency")
      last = System.currentTimeMillis()
      totalTime = 0
    }
    totalTime += duration
    settledCount += 1
  }

  def writeString(s: String): Unit = try {
    val _ = Files.write(logPath,
                        Vector(s).asJava,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.APPEND)
  } catch {
    case ex: Throwable => ex.printStackTrace()
  }

}
