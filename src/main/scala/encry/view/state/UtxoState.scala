package encry.view.state

import java.io.File

import akka.actor.ActorRef
import com.google.common.primitives.{Ints, Longs}
import encry.consensus.emission.TokenSupplyController
import encry.modifiers.EncryPersistentModifier
import encry.modifiers.history.ADProofs
import encry.modifiers.history.block.EncryBlock
import encry.modifiers.history.block.header.EncryBlockHeader
import encry.modifiers.mempool.EncryBaseTransaction
import encry.modifiers.mempool.EncryBaseTransaction.TransactionValidationException
import encry.modifiers.state.StateModifierDeserializer
import encry.modifiers.state.box._
import encry.modifiers.state.box.proposition.HeightProposition
import encry.settings.Algos.HF
import encry.settings.{Algos, Constants}
import encry.utils.BalanceCalculator
import encry.view.history.Height
import io.iohk.iodb.{ByteArrayWrapper, LSMStore, Store}
import scorex.core.NodeViewHolder.ReceivableMessages.LocallyGeneratedModifier
import scorex.core.VersionTag
import scorex.core.transaction.box.Box.Amount
import scorex.core.utils.ScorexLogging
import scorex.crypto.authds.avltree.batch._
import scorex.crypto.authds.{ADDigest, ADKey, ADValue, SerializedAdProof}
import scorex.crypto.hash.Digest32

import scala.util.{Failure, Success, Try}

class UtxoState(override val version: VersionTag,
                override val height: Height,
                override val stateStore: Store,
                val lastBlockTimestamp: Long,
                nodeViewHolderRef: Option[ActorRef])
  extends EncryState[UtxoState] with UtxoStateReader with TransactionValidator {

  import UtxoState.metadata

  override def maxRollbackDepth: Int = 10

  private def onAdProofGenerated(proof: ADProofs): Unit = {
    if (nodeViewHolderRef.isEmpty) log.warn("Got proof while nodeViewHolderRef is empty")
    nodeViewHolderRef.foreach(_ ! LocallyGeneratedModifier(proof))
  }

  private[state] def applyTransactions(txs: Seq[EncryBaseTransaction],
                                       expectedDigest: ADDigest): Try[Unit] = Try {
    txs.foreach(tx => validate(tx).map { _ =>
      extractStateChanges(tx).operations.map(ADProofs.toModification)
        .foldLeft[Try[Option[ADValue]]](Success(None)) { case (t, m) =>
        t.flatMap(_ => persistentProver.performOneOperation(m))
      }.get
    }.orElse(throw TransactionValidationException(s"$tx validation failed.")))

    // Checks whether the outcoming result is the same as expected.
    if (!expectedDigest.sameElements(persistentProver.digest))
      throw new Exception(s"Digest after txs application is wrong. ${Algos.encode(expectedDigest)} expected, " +
        s"${Algos.encode(persistentProver.digest)} given")
  }

  /** State transition function `APPLY(S,TX) -> S'`. */
  override def applyModifier(mod: EncryPersistentModifier): Try[UtxoState] = mod match {

    case block: EncryBlock =>
      log.debug(s"Applying block with header ${block.header.encodedId} to UtxoState with " +
        s"root hash ${Algos.encode(rootHash)} at height $height")

      applyTransactions(block.payload.transactions, block.header.stateRoot).map { _ =>
        val meta: Seq[(Array[Byte], Array[Byte])] =
          metadata(VersionTag @@ block.id, block.header.stateRoot, Height @@ block.header.height, block.header.timestamp)
        val proofBytes: SerializedAdProof = persistentProver.generateProofAndUpdateStorage(meta)
        val proofHash: Digest32 = ADProofs.proofDigest(proofBytes)

        if (block.adProofsOpt.isEmpty) onAdProofGenerated(ADProofs(block.header.id, proofBytes))
        log.info(s"Valid modifier ${block.encodedId} with header ${block.header.encodedId} applied to UtxoState with " +
          s"root hash ${Algos.encode(rootHash)}")

        if (!stateStore.get(ByteArrayWrapper(block.id)).exists(_.data sameElements block.header.stateRoot))
          throw new Exception("Storage kept roothash is not equal to the declared one.")
        else if (!stateStore.rollbackVersions().exists(_.data sameElements block.header.stateRoot))
          throw new Exception("Unable to apply modification properly.")
        else if (!(block.header.adProofsRoot sameElements proofHash))
          throw new Exception("Calculated proofHash is not equal to the declared one.")
        else if (!(block.header.stateRoot sameElements persistentProver.digest))
          throw new Exception("Calculated stateRoot is not equal to the declared one.")

        new UtxoState(VersionTag @@ block.id, Height @@ block.header.height, stateStore, lastBlockTimestamp, nodeViewHolderRef)
      }.recoverWith[UtxoState] { case e =>
        log.warn(s"Failed to apply block with header ${block.header.encodedId} to UTXOState with root" +
          s" ${Algos.encode(rootHash)}: ", e)
        Failure(e)
      }

    case header: EncryBlockHeader =>
      Success(new UtxoState(VersionTag @@ header.id, height, stateStore, lastBlockTimestamp, nodeViewHolderRef))

    case _ => Failure(new Exception("Got Modifier of unknown type."))
  }

  def proofsForTransactions(txs: Seq[EncryBaseTransaction]): Try[(SerializedAdProof, ADDigest)] = {
    log.debug(s"Generating proof for ${txs.length} transactions ...")
    val rootHash: ADDigest = persistentProver.digest
    if (txs.isEmpty) Failure(new Exception("Got empty transaction sequence"))
    else if (!storage.version.exists(_.sameElements(rootHash))) Failure(new Exception(s"Invalid storage version: ${storage.version.map(Algos.encode)} != ${Algos.encode(rootHash)}"))
    else persistentProver.avlProver.generateProofForOperations(extractStateChanges(txs).operations.map(ADProofs.toModification))
  }

  override def rollbackTo(version: VersionTag): Try[UtxoState] = {
    val prover: PersistentBatchAVLProver[Digest32, HF] = persistentProver
    log.info(s"Rollback UtxoState to version ${Algos.encoder.encode(version)}")
    stateStore.get(ByteArrayWrapper(version)) match {
      case Some(v) =>
        val rollbackResult: Try[UtxoState] = prover.rollback(ADDigest @@ v.data).map { _ =>
          val stateHeight: Int = stateStore.get(ByteArrayWrapper(UtxoState.bestHeightKey))
            .map(d => Ints.fromByteArray(d.data)).getOrElse(Constants.Chain.GenesisHeight)
          new UtxoState(version, Height @@ stateHeight, stateStore, lastBlockTimestamp, nodeViewHolderRef) {
            override protected lazy val persistentProver: PersistentBatchAVLProver[Digest32, Algos.HF] = prover
          }
        }
        stateStore.clean(Constants.DefaultKeepVersions)
        rollbackResult
      case None =>
        Failure(new Error(s"Unable to get root hash at version ${Algos.encoder.encode(version)}"))
    }
  }

  override def rollbackVersions: Iterable[VersionTag] =
    persistentProver.storage.rollbackVersions.map(v =>
      VersionTag @@ stateStore.get(ByteArrayWrapper(Algos.hash(v))).get.data)

  override lazy val rootHash: ADDigest = persistentProver.digest

  /**
    * Carries out an exhaustive validation of the given transaction.
    *
    * Transaction validation algorithm:
    * 0. Check semantic validity of transaction
    * For each box referenced in transaction:
    * 1. Check if box is in the state
    * 2. Parse box from the state storage
    * 3. Try to unlock the box, providing appropriate context and proof
    * For all asset types:
    * 4. Make sure inputs.sum >= outputs.sum
    */
  override def validate(tx: EncryBaseTransaction): Try[Unit] =
    tx.semanticValidity.map { _: Unit =>

      implicit val context: Context = Context(tx, height, lastBlockTimestamp, rootHash)

      if (tx.fee < tx.minimalFee && !tx.isCoinbase) throw TransactionValidationException(s"Low fee in $tx")

      val bxs: IndexedSeq[EncryBaseBox] = tx.unlockers.flatMap(u => persistentProver.unauthenticatedLookup(u.boxId)
        .map(bytes => StateModifierDeserializer.parseBytes(bytes, u.boxId.head))
        .map(t => t.toOption -> u.proofOpt)).foldLeft(IndexedSeq[EncryBaseBox]()) { case (acc, (bxOpt, proofOpt)) =>
        (bxOpt, proofOpt, tx.defaultProofOpt) match {
          // If `proofOpt` from unlocker is `None` then `defaultProofOpt` is used.
          case (Some(bx), Some(proof), _) if bx.proposition.unlockTry(proof).isSuccess => acc :+ bx
          case (Some(bx), _, Some(defaultProof)) if bx.proposition.unlockTry(defaultProof).isSuccess => acc :+ bx
          case _ => throw TransactionValidationException(s"Failed to spend some boxes referenced in $tx")
        }
      }

      val validBalance: Boolean = {
        val debitB: Map[ADKey, Amount] = BalanceCalculator.balanceSheet(bxs, excludeCoinbase = false)
        val creditB: Map[ADKey, Amount] = BalanceCalculator.balanceSheet(tx.newBoxes)
        creditB.forall { case (id, amount) => debitB.getOrElse(id, 0L) >= amount }
      }

      if (!validBalance) throw TransactionValidationException(s"Non-positive balance in $tx")
    }
}

object UtxoState extends ScorexLogging {

  private val bestVersionKey: Digest32 = Algos.hash("best_state_version")

  private val bestHeightKey: Digest32 = Algos.hash("state_height")

  private val lastBlockTimeKey: Digest32 = Algos.hash("last_block_timestamp")

  def create(stateDir: File, nodeViewHolderRef: Option[ActorRef]): UtxoState = {
    val stateStore: LSMStore = new LSMStore(stateDir, keepVersions = Constants.DefaultKeepVersions)
    val stateVersion: Array[Byte] = stateStore.get(ByteArrayWrapper(bestVersionKey))
      .map(_.data).getOrElse(EncryState.genesisStateVersion)
    val stateHeight: Int = stateStore.get(ByteArrayWrapper(bestHeightKey))
      .map(d => Ints.fromByteArray(d.data)).getOrElse(Constants.Chain.PreGenesisHeight)
    val lastBlockTimestamp: Amount = stateStore.get(ByteArrayWrapper(lastBlockTimeKey))
      .map(d => Longs.fromByteArray(d.data)).getOrElse(0L)
    new UtxoState(VersionTag @@ stateVersion, Height @@ stateHeight, stateStore, lastBlockTimestamp, nodeViewHolderRef)
  }

  private def metadata(modId: VersionTag, stateRoot: ADDigest,
                       height: Height, blockTimestamp: Long): Seq[(Array[Byte], Array[Byte])] = {
    val idStateDigestIdxElem: (Array[Byte], Array[Byte]) = modId -> stateRoot
    val stateDigestIdIdxElem: (Digest32, VersionTag) = Algos.hash(stateRoot) -> modId
    val bestVersion: (Digest32, VersionTag) = bestVersionKey -> modId
    val stateHeight: (Digest32, Array[Byte]) = bestHeightKey -> Ints.toByteArray(height)
    val lastBlockTimestamp: (Digest32, Array[Byte]) = lastBlockTimeKey -> Longs.toByteArray(blockTimestamp)

    Seq(idStateDigestIdxElem, stateDigestIdIdxElem, bestVersion, stateHeight, lastBlockTimestamp)
  }

  def fromBoxHolder(bh: BoxHolder, stateDir: File, nodeViewHolderRef: Option[ActorRef]): UtxoState = {
    val p: BatchAVLProver[Digest32, HF] = new BatchAVLProver[Digest32, Algos.HF](keyLength = EncryBox.BoxIdSize, valueLengthOpt = None)
    bh.sortedBoxes.foreach(b => p.performOneOperation(Insert(b.id, ADValue @@ b.bytes)).ensuring(_.isSuccess))

    val stateStore: LSMStore = new LSMStore(stateDir, keepVersions = Constants.DefaultKeepVersions)

    log.info(s"Generating UTXO State with ${bh.boxes.size} boxes")

    new UtxoState(EncryState.genesisStateVersion, Constants.Chain.PreGenesisHeight, stateStore, 0L, nodeViewHolderRef) {
      override protected lazy val persistentProver: PersistentBatchAVLProver[Digest32, Algos.HF] =
        PersistentBatchAVLProver.create(
          p, storage, metadata(EncryState.genesisStateVersion, p.digest, Constants.Chain.PreGenesisHeight, 0L), paranoidChecks = true
        ).get.ensuring(_.digest sameElements storage.version.get)
    }
  }

  /** Controls token supply for particular `height` */
  def supplyBoxesAt(height: Height, seed: Long): CoinbaseBox = {
    val supplyAmount: Long = TokenSupplyController.supplyAt(height)
    CoinbaseBox(HeightProposition(Height @@ (height + Constants.Chain.CoinbaseHeightLock)),
      seed * height, supplyAmount)
  }
}
