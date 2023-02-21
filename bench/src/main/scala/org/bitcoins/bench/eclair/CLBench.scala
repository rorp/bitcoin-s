package org.bitcoins.bench.eclair

import akka.actor.ActorSystem
import com.bitcoins.clightning.rpc.CLightningRpcClient
import org.bitcoins.commons.jsonmodels.eclair.PaymentId
import org.bitcoins.core.currency.CurrencyUnit
import org.bitcoins.core.protocol.ln.channel.FundedChannelId
import org.bitcoins.core.protocol.ln.currency._
import org.bitcoins.core.protocol.ln.node.NodeId
import org.bitcoins.eclair.rpc.api.EclairApi
import org.bitcoins.eclair.rpc.client.EclairRpcClient
import org.bitcoins.rpc.client.common.BitcoindRpcClient
import org.bitcoins.testkit.async.TestAsyncUtil
import org.bitcoins.testkit.clightning.CLightningRpcTestUtil.awaitInSync
import org.bitcoins.testkit.clightning.{
  CLightningRpcTestClient,
  CLightningRpcTestUtil
}
import org.bitcoins.testkit.eclair.rpc.EclairRpcTestUtil
import org.bitcoins.testkit.util.EclairRpcTestClient

import java.nio.file.Path
import java.util.UUID
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.ConcurrentMapHasAsScala
import scala.util.{Failure, Success}

/** This test spins up one test node and [[NetworkSize]] sender nodes, which open channels with the test one.
  * Then each sender node sends [[PaymentCount]] payments to the test node one by one. For each payment the
  * test node generates an invoice and the send node pays it using `sendtonode` API call.
  *
  * The test keeps track of times when a payment was initiated, when the payment ID was received,
  * and when the corresponding web socket event was received. It writes all results into [[OutputFileName]]
  * in CSV format.
  */
object CLBench extends App with EclairRpcTestUtil {

  import PaymentLog._

  implicit val system: ActorSystem = ActorSystem()
  import system.dispatcher

  // put compiled test jar files into binaries/eclair/${version} directory

  // None means current release
  val TestEclairVersion = Option.empty[String]
  val TestEclairCommit = Option.empty[String]
  //  val TestEclairVersion = Option("0.3.3-SNAPSHOT")
  //  val TestEclairCommit = Option("84825ff")
  val SenderEclairVersion = Option.empty[String]
  val SenderEclairCommit = Option.empty[String]

  val NetworkSize = 10
  val PaymentCount = 10001
  val ChannelAmount = 10000000000L.msats
  val PaymentAmount = 1000.msats
  val OutputFileName = "test.csv"
  val LogbackXml = None // Some("~/logback.xml")

  // don't forget to recreate `eclair` Postgres database before starting a new test
  val CustomConfigMap: Map[String, String] = Map(
//    "eclair.file-backup.enabled" -> "false",
//    "eclair.db.driver" -> "postgres"
//    "eclair.db.psql.pool.max-size" -> 12,
//    "eclair.db.psql.lock-type" -> "none"
//    "eclair.db.psql.lock-type" -> "optimistic"
//    "eclair.db.psql.lock-type" -> "exclusive"
  )

  object Progress {
    private var count = 0
    private var percentage = 0

    def inc(): Unit =
      synchronized {
        count += 1
        val newPercentage = count * 100 / (NetworkSize * PaymentCount)
        if (newPercentage % 10 == 0 && newPercentage != percentage) {
          percentage = newPercentage
          print(s"$percentage% ")
        }
      }
  }

  def sendPayments(
      network: Network,
      amount: MilliSatoshis,
      count: Int): Future[Vector[PaymentId]] =
    for {
      _ <- network.testNode.getInfo
      paymentIds <- Future.sequence(network.networkEclairNodes.map { node =>
        1.to(count).foldLeft(Future.successful(Vector.empty[PaymentId])) {
          (accF, _) =>
            for {
              acc <- accF
              label = UUID.randomUUID().toString
              invoiceResult <-
                network.testNode.createInvoice(
                  amount.toSatoshis,
                  label,
                  "test " + System.currentTimeMillis(),
                  60)
              invoice = invoiceResult.bolt11
              paymentHash = invoice.lnTags.paymentHash.hash
              _ = logPaymentHash(paymentHash)
              p = promises.get(paymentHash)
              start = System.currentTimeMillis()
              id <- node.payInvoice(invoice)
              _ = network.testNode.waitInvoice(label).onComplete {
                case Success(result)    => logInvoiceResult(result)
                case Failure(exception) => logError(paymentHash, exception)
              }
              _ <- p.future
              _ = logSettled(System.currentTimeMillis() - start)
              _ = removePaymentHash(paymentHash)
            } yield {
              Progress.inc()
              acc :+ id
            }
        }
      })
    } yield paymentIds.flatten

  def runTests(network: Network): Future[Unit] = {
    println("Setting up the test network")
    println(
      s"Set up $NetworkSize nodes, that will send $PaymentCount payments to the test node each")
    println(s"Test node data directory: ${network.testNode.instance.datadir}")
    println("Testing...")
    for {
      _ <- sendPayments(network, PaymentAmount, PaymentCount)
      _ <-
        TestAsyncUtil
          .retryUntilSatisfied(condition =
                                 promises.asScala.values.forall(_.isCompleted),
                               interval = 1.second,
                               maxTries = 100)
          .recover { case ex: Throwable => ex.printStackTrace() }
      _ = println("\nDone!")
    } yield ()
  }

  case class Network(
      bitcoind: BitcoindRpcClient,
      testNode: CLightningRpcClient,
      networkEclairNodes: Vector[EclairRpcClient],
      channelIds: Vector[FundedChannelId]) {

    def shutdown(): Future[Unit] =
      for {
        _ <- Future.sequence(networkEclairNodes.map(_.stop()))
        _ <- testNode.stop()
        _ <- bitcoind.stop()
      } yield ()
  }

  object Network {

    def start(
        networkSize: Int,
        channelAmount: MilliSatoshis,
        logbackXml: Option[String],
        testNodeConfigOverrides: Map[String, String] = Map.empty,
        networkNodeConfigOverrides: Map[String, String] = Map.empty,
        clBinaryDirectory: Path = CLightningRpcTestClient.sbtBinaryDirectory,
        eclairBinaryDirectory: Path = EclairRpcTestClient.sbtBinaryDirectory)(
        implicit system: ActorSystem): Future[Network] = {
      import system.dispatcher
      for {
        bitcoind <- startedBitcoindRpcClient()
        _ = {
          EclairRpcTestUtil.customConfigMap = testNodeConfigOverrides
        }
        testCLInstance = CLightningRpcTestUtil.cLightingInstance(bitcoind)

        testCLNode = new CLightningRpcClient(
          testCLInstance,
          CLightningRpcTestClient.getBinary(None, clBinaryDirectory).get)
        _ <- testCLNode.start()
        _ <- awaitInSync(testCLNode, bitcoind)
        _ = {
          EclairRpcTestUtil.customConfigMap = networkNodeConfigOverrides
        }
        networkEclairInstances =
          1
            .to(networkSize)
            .toVector
            .map(_ =>
              EclairRpcTestUtil.eclairInstance(bitcoind,
                                               logbackXml = logbackXml))
        networkEclairNodes: Seq[EclairRpcClient] = networkEclairInstances.map(
          new EclairRpcClient(
            _,
            EclairRpcTestClient.getBinary(None, None, eclairBinaryDirectory)))
        _ <- Future.sequence(networkEclairNodes.map(_.start()))
        _ <- Future.sequence(
          networkEclairNodes.map(awaitEclairInSync(_, bitcoind)))
        _ <- Future.sequence(
          networkEclairNodes.map(connectLNNodes(_, testCLNode)))
        channelIds <- networkEclairNodes.foldLeft(
          Future.successful(Vector.empty[(FundedChannelId, NodeId)])) {
          (accF, node) =>
            for {
              acc <- accF
              nodeId <- node.getInfo.map(_.nodeId)
              channelId <- openCLChannel(
                n1 = node,
                n2 = testCLNode,
                amt = channelAmount.toSatoshis,
                pushMSat = MilliSatoshis(channelAmount.toLong / 2))
            } yield acc :+ (channelId, nodeId)
        }
        _ <-
          Future.sequence(
            channelIds.map(x => awaitUntilChannelActive(testCLNode, x._2)))
      } yield Network(bitcoind,
                      testCLNode,
                      networkEclairNodes.toVector,
                      channelIds.map(_._1).toVector)
    }

  }

  private val DEFAULT_CHANNEL_MSAT_AMT = MilliSatoshis(500000000L)

  def openCLChannel(
      n1: EclairRpcClient,
      n2: CLightningRpcClient,
      amt: CurrencyUnit = DEFAULT_CHANNEL_MSAT_AMT.toSatoshis,
      pushMSat: MilliSatoshis = MilliSatoshis(
        DEFAULT_CHANNEL_MSAT_AMT.toLong / 2))(implicit
      system: ActorSystem): Future[FundedChannelId] = {

    val bitcoindRpcClient = getBitcoindRpc(n1)

    val n1NodeIdF = n1.nodeId()
    val n2NodeIdF = n2.nodeId

    val nodeIdsF: Future[(NodeId, NodeId)] = {
      n1NodeIdF.flatMap(n1 => n2NodeIdF.map(n2 => (n1, n2)))
    }

    val fundedChannelIdF: Future[FundedChannelId] = {
      nodeIdsF.flatMap { case (nodeId1, nodeId2) =>
        logger.debug(
          s"Opening a channel from ${nodeId1} -> ${nodeId2} with amount ${amt}")
        n1.open(nodeId = nodeId2,
                funding = amt,
                pushMsat = Some(pushMSat),
                feerateSatPerByte = None,
                channelFlags = None,
                openTimeout = None)
      }
    }

    val gen = for {
      _ <- fundedChannelIdF
      address <- bitcoindRpcClient.getNewAddress
      blocks <- bitcoindRpcClient.generateToAddress(6, address)
    } yield blocks

    val openedF = {
      gen.flatMap { _ =>
        fundedChannelIdF.flatMap { fcid =>
          val chanOpenF = awaitChannelOpened(n1, fcid)
          chanOpenF.map(_ => fcid)
        }
      }
    }

    openedF.flatMap { case _ =>
      nodeIdsF.map { case (nodeId1, nodeId2) =>
        logger.debug(
          s"Channel successfully opened ${nodeId1} -> ${nodeId2} with amount $amt")
      }
    }

    openedF
  }

  def connectLNNodes(client: EclairApi, otherClient: CLightningRpcClient)(
      implicit system: ActorSystem): Future[Unit] = {
    implicit val dispatcher = system.dispatcher
    val infoF = otherClient.getInfo
    val nodeIdF = infoF.map(_.id)
    val connection: Future[Unit] = infoF.flatMap { info =>
      client.connect(info.id,
                     info.binding.head.`address`,
                     info.binding.head.port)
    }

    def isConnected(): Future[Boolean] = {

      nodeIdF.flatMap { nodeId =>
        connection.flatMap { _ =>
          val connected: Future[Boolean] = client.isConnected(nodeId)
          connected
        }
      }
    }

    logger.debug(s"Awaiting connection between clients")
    val connected = TestAsyncUtil.retryUntilSatisfiedF(conditionF =
                                                         () => isConnected(),
                                                       interval = 1.second)

    connected.map(_ => logger.debug(s"Successfully connected two clients"))

    connected

  }

  private def awaitUntilChannelActive(
      client: CLightningRpcClient,
      destination: NodeId): Future[Unit] = {
    def isActive: Future[Boolean] = {
      client.listChannels().map(_.exists(_.destination == destination))
    }

    TestAsyncUtil.retryUntilSatisfiedF(conditionF = () => isActive,
                                       interval = 1.seconds)
  }

  val res: Future[Unit] = for {
//    cl <- CLightningRpcClient
//    clientA <- CLightningRpcTestClient.fromSbtDownload(Some(bitcoind))
    network <- Network.start(
      NetworkSize,
      ChannelAmount,
      LogbackXml,
      testNodeConfigOverrides = CustomConfigMap
    )
    _ <- runTests(network).recoverWith { case e: Throwable =>
      e.printStackTrace()
      Future.successful(())
    }
    _ <- network.shutdown()
  } yield {}

  res.onComplete { e =>
    e match {
      case Success(_)  => ()
      case Failure(ex) => ex.printStackTrace()
    }
    sys.exit()
  }
}
