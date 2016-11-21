package org.bitcoins.core.protocol.transaction

import org.bitcoins.core.protocol.NetworkElement
import org.bitcoins.core.serializers.transaction.RawTransactionWitnessParser
import org.bitcoins.core.util.{BitcoinSUtil, Factory}

/**
  * Created by chris on 11/21/16.
  * The witness data for [[org.bitcoins.core.protocol.script.ScriptSignature]] in this transaction
  * [[https://github.com/bitcoin/bitcoin/blob/b4e4ba475a5679e09f279aaf2a83dcf93c632bdb/src/primitives/transaction.h#L232-L268]]
  */
sealed trait TransactionWitness extends NetworkElement {
  def witnesses: Seq[TransactionInputWitness]

  override def hex = RawTransactionWitnessParser.write(this)
}

object TransactionWitness {
  private case class TransactionWitnessImpl(witnesses: Seq[TransactionInputWitness]) extends TransactionWitness

  def apply(witnesses: Seq[TransactionInputWitness]): TransactionWitness = TransactionWitnessImpl(witnesses)

  def fromBytes(bytes: Seq[Byte], numInputs: Int): TransactionWitness = RawTransactionWitnessParser.read(bytes,numInputs)

  def apply(bytes: Seq[Byte], numInputs: Int): TransactionWitness = fromBytes(bytes,numInputs)

  def apply(hex: String, numInputs: Int): TransactionWitness = fromBytes(BitcoinSUtil.decodeHex(hex),numInputs)
}