package org.bitcoins.tor

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.settings.{
  ClientConnectionSettings,
  ConnectionPoolSettings
}
import akka.testkit.{ImplicitSender, TestKit}
import org.scalatest.funsuite.AnyFunSuiteLike
import org.scalatest.{BeforeAndAfterAll, TestSuite}

import java.net.InetSocketAddress
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

class Socks5ClientTransportSpec
    extends TestKit(ActorSystem("test"))
    with TestSuite
    with BeforeAndAfterAll
    with AnyFunSuiteLike
    with ImplicitSender {

  test("connect to real daemon") {

    val proxyParams = Socks5ProxyParams(
      address = InetSocketAddress.createUnresolved("127.0.0.1", 9050),
      credentials_opt = None,
      randomizeCredentials = true,
      useForTor = true,
      useForIPv4 = true,
      useForIPv6 = true
    )

    val socks5ClientTransport = new Socks5ClientTransport(proxyParams)

    val clientConnectionSettings =
      ClientConnectionSettings(system).withTransport(socks5ClientTransport)

    val settings = ConnectionPoolSettings(system).withConnectionSettings(
      clientConnectionSettings)

    val responseF = Http().singleRequest(
      HttpRequest(uri = "http://test.oracle.suredbits.com/v1/attestations"),
      settings = settings)

    val response = Await.result(responseF, 10.seconds)

    // Should be a redirect to the HTTPS endpoint
    assert(response.status.intValue() == 301)
    assert(
      response.headers
        .find(_.lowercaseName() == "location")
        .map(_.value())
        .contains("https://test.oracle.suredbits.com/v1/attestations"))
  }

}
