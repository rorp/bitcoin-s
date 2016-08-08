package org.bitcoins.core.consensus

import org.bitcoins.core.crypto.DoubleSha256Digest
import org.bitcoins.core.protocol.blockchain.Block
import org.bitcoins.core.protocol.transaction.Transaction
import org.bitcoins.core.util._

import scala.annotation.tailrec

/**
  * Created by chris on 5/24/16.
  * This trait contains all functionality related to computing merkle trees
  * Mimics this functionality inside of bitcoin core
  * https://github.com/bitcoin/bitcoin/blob/master/src/consensus/merkle.cpp
  */
trait Merkle extends BitcoinSLogger {

  type MerkleTree = BinaryTree[DoubleSha256Digest]
  /**
    * Computes the merkle root for the given block
    * @param block the given block that needs the merkle root computed
    * @return the hash representing the merkle root for this block
    */
  def computeBlockMerkleRoot(block : Block) : DoubleSha256Digest = computeMerkleRoot(block.transactions)

  /**
    * Computes the merkle root for the given sequence of transactions
    * @param transactions the list of transactions whose merkle root needs to be computed
    * @return the merkle root for the sequence of transactions
    */
  def computeMerkleRoot(transactions : Seq[Transaction]) : DoubleSha256Digest = transactions match {
    case Nil => throw new IllegalArgumentException("We cannot have zero transactions in the block. There always should be ATLEAST one - the coinbase tx")
    case h :: Nil => h.txId
    case h :: t =>
      val leafs = transactions.map(tx => Leaf(tx.txId))
      val merkleTree = build(leafs,Nil)
      merkleTree.value.get
  }

  @tailrec
  final def build(subTrees: Seq[MerkleTree], accum: Seq[MerkleTree]): MerkleTree = subTrees match {
    case Nil =>
      if (accum.size == 1) accum.head
      else if (accum.isEmpty) throw new IllegalArgumentException("Should never have sub tree size of zero, this implies there was zero hashes given")
      else build(accum.reverse, Nil)
    case h :: h1 :: t =>
      logger.debug("Computing the merkle tree for two sub merkle trees")
      logger.debug("Subtrees: " + subTrees)
      val newTree = computeTree(h,h1)
      logger.debug("newTree: " + newTree)
      logger.debug("new subtree seq: " + t)
      build(t, newTree +: accum)
    case h :: t =>
      logger.debug("Computing the merkle tree for one sub merkle tree - this means duplicating the subtree")
      logger.debug("Subtrees: " + subTrees)
      //means that we have an odd amount of txids, this means we duplicate the last hash in the tree
      val newTree = computeTree(h,h)
      logger.debug("newTree: " + newTree)
      logger.debug("new subtree seq: " + t)
      build(t, newTree +: accum)
  }


  /** Computes the merkle tree of two sub merkle trees */
  def computeTree(tree1: MerkleTree, tree2: MerkleTree): MerkleTree = {
    val bytes = tree1.value.get.bytes ++ tree2.value.get.bytes
    val hash = CryptoUtil.doubleSHA256(bytes)
    Node(hash,tree1,tree2)
  }
}

object Merkle extends Merkle

