package org.bitcoins.core.protocol.ln.node

import org.bitcoins.crypto.{ECPublicKey, Factory, NetworkElement}
import scodec.bits.ByteVector

/** `NodeId` is simply a wrapper for
  * [[org.bitcoins.crypto.ECPublicKey ECPublicKey]]. This public key needs to be
  * a 33 byte compressed secp256k1 public key.
  */
case class NodeId(pubKey: ECPublicKey) extends NetworkElement {

  override def toString: String = pubKey.hex

  override def bytes: ByteVector = pubKey.bytes
}

object NodeId extends Factory[NodeId] {

  def fromPubKey(pubKey: ECPublicKey): NodeId = {
    NodeId(pubKey)
  }

  override def fromBytes(bytes: ByteVector): NodeId = {
    val pubKey = ECPublicKey.fromBytes(bytes)
    fromPubKey(pubKey)
  }
}
