package encry.modifiers.mempool

import encry.modifiers.mempool.directive.Directive
import encry.modifiers.state.box.proof.Proof
import encry.modifiers.state.box.proposition.EncryProposition
import encry.modifiers.state.box.{AssetBox, EncryBaseBox}
import encry.settings.{Algos, Constants}
import encrywm.lang.backend.env.ESEnvConvertable
import io.circe.Encoder
import scorex.core.ModifierId
import scorex.core.transaction.Transaction
import scorex.core.transaction.box.Box.Amount
import scorex.crypto.hash.Digest32

import scala.util.Try

trait EncryBaseTransaction extends Transaction[EncryProposition]
  with ModifierWithSizeLimit with ESEnvConvertable {

  val txHash: Digest32

  lazy val dataToSign: Array[Byte] = txHash

  val semanticValidity: Try[Unit]

  override lazy val id: ModifierId = ModifierId @@ txHash

  val fee: Long

  val timestamp: Long

  val unlockers: IndexedSeq[Unlocker]

  val directives: IndexedSeq[Directive]

  val defaultProofOpt: Option[Proof]

  lazy val newBoxes: Traversable[EncryBaseBox] =
    directives.flatMap(_.boxes(txHash))

  lazy val minimalFee: Amount = Constants.FeeMinAmount +
    directives.map(_.cost).sum + (Constants.PersistentByteCost * length)

  override def toString: String = s"EncryTransaction(id=${Algos.encode(id)}, fee=$fee, inputs=${unlockers.map(u => Algos.encode(u.boxId))})"

  // Shadowed.
  override lazy val messageToSign: Array[Byte] = Array.fill(32)(1.toByte)
}

object EncryBaseTransaction {

  type TxTypeId = Byte
  type Nonce = Long

  case class TransactionValidationException(s: String) extends Exception(s)

  implicit val jsonEncoder: Encoder[EncryBaseTransaction] = {
    case tx: EncryTransaction => EncryTransaction.jsonEncoder(tx)
  }
}
