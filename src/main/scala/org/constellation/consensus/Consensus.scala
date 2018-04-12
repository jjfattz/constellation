package org.constellation.consensus

import java.security.{KeyPair, PrivateKey, PublicKey}

import akka.actor.{Actor, ActorLogging, ActorRef}
import org.constellation.consensus.Consensus._
import org.constellation.p2p.PeerToPeer.{GetPeerActorRefs, GetPeers, Peers}
import org.constellation.primitives.{Block, Transaction}
import org.constellation.state.ChainStateManager.{AddBlock, BlockAddedToChain, CreateBlockProposal}
import org.constellation.wallet.KeyUtils
import akka.pattern.ask
import akka.util.Timeout
import org.constellation.state.{ChainStateManager, MemPoolManager}

import scala.collection.immutable.HashMap
import scala.concurrent.duration._
import scala.collection.mutable
import scala.concurrent.Await
import scala.concurrent.duration.Duration

/*

  Consensus Flow (temporary):

  - a consensus round starts

  - we grab the latest set of transactions up to some limit

  - we send each "facilitator" every set of transactions

  - on the node we keep track of a map of facilitator ref to set of transactions

  - we take a union of the transactions

  - we create a block

  - we send the block to each of the nodes again

  - we keep track of the map of facilitator to blocks

  - once we have all of them, we compare the blocks and if they are all the same then we add the block to our local chain

  - we remove the transactions in the block from our mem pool

  - start the process again

  - if they are not the same then we reset the state and start the process again

 */
object Consensus {

  // Commands
  case class GetMemPool(replyTo: ActorRef, round: Long)
  case class CheckConsensusResult(round: Long)

  case class GenerateGenesisBlock(selfRemoteActorRef: ActorRef)
  case class EnableConsensus()

  // Events
  case class ProposedBlockUpdated(block: Block)
  case class MemPoolUpdated(transactions: Seq[Transaction], round: Long)
  case class PeerMemPoolUpdated(transactions: Seq[Transaction], peer: ActorRef, round: Long)
  case class PeerProposedBlock(block: Block, peer: ActorRef)

  // Methods

  def getFacilitators(previousBlock: Block): Set[ActorRef] = {
    // TODO: here is where we need to grab our random sampling fancy function

    previousBlock.clusterParticipants
  }

  def isFacilitator(facilitators: Set[ActorRef], self: ActorRef): Boolean = {
    facilitators.contains(self)
  }

  def getConsensusBlock(peerBlockProposals: HashMap[Long, HashMap[ActorRef, Block]],
                        currentFacilitators: Set[ActorRef], round: Long): Option[Block] = {

    var consensusBlock: Option[Block] = None

    if (!peerBlockProposals.contains(round)) {
      return consensusBlock
    }

    val facilitatorsWithoutBlockProposals = currentFacilitators.filter(f => {
      !peerBlockProposals(round).contains(f)
    })

    if (facilitatorsWithoutBlockProposals.isEmpty) {

      val blocks = peerBlockProposals(round).values

      // TODO: update to be from a threshold not all
      val allBlocksInConsensus = blocks.toList.distinct.length == 1

      if (allBlocksInConsensus) {
        consensusBlock = Some(blocks.head)
      }
    }

    consensusBlock
  }

  def notifyFacilitators(previousBlock: Block, self: ActorRef, fx: ActorRef => Unit): Boolean = {
    val facilitators: Set[ActorRef] = getFacilitators(previousBlock)

    // make sure that we are a facilitator
    if (!isFacilitator(facilitators, self)) {
      return false
    }

    facilitators.foreach(fx)

    true
  }

  def notifyFacilitatorsOfBlockProposal(previousBlock: Block, proposedBlock: Block, self: ActorRef): Boolean = {
    notifyFacilitators(previousBlock, self, (f) => {
      f ! PeerProposedBlock(proposedBlock, self)
    })
  }

  def notifyFacilitatorsOfPeerMemPoolUpdated(previousBlock: Block, self: ActorRef,
                                             transactions: Seq[Transaction], round: Long): Boolean = {
    // Send all of the facilitators our current memPoolState
    Consensus.notifyFacilitators(previousBlock, self, (p) => {
      p ! PeerMemPoolUpdated(transactions, self, round)
    })
  }

  def handleBlockAddedToChain(consensusRoundState: ConsensusRoundState,
                              latestBlock: Block,
                              memPoolManager: ActorRef,
                              self: ActorRef): ConsensusRoundState = {

    val peerMemPoolCache = consensusRoundState.copy().peerMemPools.filter(f => f._1 > latestBlock.round)
    val peerBlockProposalsCache= consensusRoundState.copy().peerBlockProposals.filter(f => f._1 > latestBlock.round)

    val updatedState = consensusRoundState.copy(proposedBlock = None,
      previousBlock = Some(latestBlock),
      currentFacilitators = Consensus.getFacilitators(latestBlock),
      peerMemPools = peerMemPoolCache,
      peerBlockProposals = peerBlockProposalsCache)

    if (updatedState.enabled) {
      // If we are a facilitator this round then begin consensus
      if (Consensus.isFacilitator(updatedState.currentFacilitators, updatedState.selfPeerToPeerRef.get)) {
        memPoolManager ! GetMemPool(self, latestBlock.round + 1)
      }
    }

    updatedState
  }

  // TODO: need to revisit, useful for getting the initial seeded actor refs into the block for now
  def generateGenesisBlock(selfRemoteActorRef: ActorRef, consensusRoundState: ConsensusRoundState,
                           chainStateManager: ActorRef, sender: ActorRef, replyTo: ActorRef)(implicit timeout: Timeout): ConsensusRoundState = {
      val updatedState = consensusRoundState.copy(selfPeerToPeerRef = Some(selfRemoteActorRef))

      // TODO: revist this
      val seedPeerRefs =
        Await.result(updatedState.selfPeerToPeerRef.get ? GetPeerActorRefs, 5.seconds)
          .asInstanceOf[Set[ActorRef]]

      // TODO: add correct genesis block, temporary for testing
      val genesisBlock = Block("tempGenesisParentHash", 0, "tempSig",
        seedPeerRefs.+(updatedState.selfPeerToPeerRef.get), 0, Seq())

      chainStateManager ! AddBlock(genesisBlock, replyTo)

      sender ! genesisBlock

      updatedState
  }

  def enableConsensus(consensusRoundState: ConsensusRoundState, memPoolManager: ActorRef, self: ActorRef): ConsensusRoundState = {
    val updatedState = consensusRoundState.copy(enabled = true)

    // If we are a facilitator this round then begin consensus
    if (Consensus.isFacilitator(consensusRoundState.currentFacilitators,
      consensusRoundState.selfPeerToPeerRef.get)) {
      memPoolManager ! GetMemPool(self, 0L)
    }

    updatedState
  }

  def handleProposedBlockUpdated(consensusRoundState: ConsensusRoundState, block: Block): ConsensusRoundState = {
    val updatedState = consensusRoundState.copy(proposedBlock = Some(block))

    val previousBlock: Option[Block] = updatedState.previousBlock
    val proposedBlock: Option[Block] = updatedState.proposedBlock

    if (proposedBlock.isDefined && previousBlock.isDefined) {
      Consensus.notifyFacilitatorsOfBlockProposal(previousBlock.get,
        proposedBlock.get,
        updatedState.selfPeerToPeerRef.get)
    }

    updatedState
  }

  def checkConsensusResult(consensusRoundState: ConsensusRoundState, round: Long, chainStateManager: ActorRef, self: ActorRef) = {
    val consensusBlock =
      Consensus.getConsensusBlock(consensusRoundState.peerBlockProposals,
                                  consensusRoundState.currentFacilitators,
                                  round)

    if (consensusBlock.isDefined) {
      chainStateManager ! AddBlock(consensusBlock.get, self)
    }
  }

  def handlePeerMemPoolUpdated(consensusRoundState: ConsensusRoundState, round: Long, peer: ActorRef,
                               transactions: Seq[Transaction], chainStateManager: ActorRef, replyTo: ActorRef): ConsensusRoundState = {
    val peerMemPools =
      consensusRoundState.peerMemPools +
        (round -> (consensusRoundState.peerMemPools.getOrElse(round, HashMap()) + (peer -> transactions)))

    val updatedState = consensusRoundState.copy(peerMemPools = peerMemPools)

    // check if we have enough mem pools to create a block
    val facilitatorsWithoutMemPools = updatedState.currentFacilitators.filter(f => {
      !updatedState.peerMemPools(round).contains(f)
    })

    if (facilitatorsWithoutMemPools.isEmpty) {
      chainStateManager ! CreateBlockProposal(updatedState.peerMemPools(round), round, replyTo)
    }

    updatedState
  }

  def handlePeerProposedBlock(consensusRoundState: ConsensusRoundState,
                              replyTo: ActorRef, block: Block, peer: ActorRef): ConsensusRoundState = {
    val peerBlockProposals =
      consensusRoundState.peerBlockProposals +
        (block.round -> (consensusRoundState.peerBlockProposals.getOrElse(block.round, HashMap()) + (peer -> block)))

    val updatedState = consensusRoundState.copy(peerBlockProposals = peerBlockProposals)

    replyTo ! CheckConsensusResult(block.round)

    updatedState
  }

  case class ConsensusRoundState(selfPeerToPeerRef: Option[ActorRef] = None,
                                 enabled: Boolean = false,
                                 proposedBlock: Option[Block] = None,
                                 previousBlock: Option[Block] = None,
                                 currentFacilitators: Set[ActorRef] = Set(),
                                 peerMemPools: HashMap[Long, HashMap[ActorRef, Seq[Transaction]]] = HashMap(0L -> HashMap()),
                                 peerBlockProposals: HashMap[Long, HashMap[ActorRef, Block]] = HashMap(0L -> HashMap()))

}

class Consensus(memPoolManager: ActorRef, chainManager: ActorRef, keyPair: KeyPair)
               (implicit timeout: Timeout) extends Actor with ActorLogging {

  var consensusRoundState: ConsensusRoundState = ConsensusRoundState()

  override def receive: Receive = {
    case GenerateGenesisBlock(selfRemoteActorRef) =>
      log.debug(s"generate genesis block = $consensusRoundState")

      consensusRoundState = generateGenesisBlock(selfRemoteActorRef, consensusRoundState, chainManager, sender, self)

    case EnableConsensus() =>
      log.debug(s"enable consensus request = $consensusRoundState")

      consensusRoundState = enableConsensus(consensusRoundState, memPoolManager, self)

    case BlockAddedToChain(latestBlock) =>
      log.debug(s"block added to chain = $latestBlock")

      consensusRoundState = handleBlockAddedToChain(consensusRoundState, latestBlock, memPoolManager, self)

    case ProposedBlockUpdated(block) =>
      log.debug(s"self proposed block updated = $block")

      consensusRoundState = handleProposedBlockUpdated(consensusRoundState, block)

    case MemPoolUpdated(transactions, round) =>
      notifyFacilitatorsOfPeerMemPoolUpdated(consensusRoundState.previousBlock.get,
        consensusRoundState.selfPeerToPeerRef.get, transactions, round)

    case CheckConsensusResult(round) =>
      log.debug(s"check consensus result = $round")

      checkConsensusResult(consensusRoundState, round, chainManager, self)

    case PeerMemPoolUpdated(transactions, peer, round) =>
      log.debug(s"peer mem pool updated = $transactions, $peer, $round")

      consensusRoundState = handlePeerMemPoolUpdated(consensusRoundState, round, peer, transactions, chainManager, self)

    case PeerProposedBlock(block, peer) =>
      log.debug(s"peer proposed block = $block, $peer")

      consensusRoundState = handlePeerProposedBlock(consensusRoundState, self, block, peer)

  }

}