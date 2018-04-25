package encry.modifiers.mempool.directive

import scorex.core.serialization.Serializer

import scala.util.{Failure, Try}

object DirectiveSerializer extends Serializer[Directive] {

  override def toBytes(obj: Directive): Array[Byte] = obj match {
    case cd: CoinbaseDirective =>
      CoinbaseDirective.TypeId +: CoinbaseDirectiveSerializer.toBytes(cd)
    case td: TransferDirective =>
      TransferDirective.TypeId +: TransferDirectiveSerializer.toBytes(td)
    case aid: AssetIssuingDirective =>
      AssetIssuingDirective.TypeId +: AssetIssuingDirectiveSerializer.toBytes(aid)
    case sad: ScriptedAssetDirective =>
      ScriptedAssetDirective.TypeId +: ScriptedAssetDirectiveSerializer.toBytes(sad)
    case comd: CommitmentDirective =>
      CommitmentDirective.TypeId +: CommitmentDirectiveSerializer.toBytes(comd)
    case m => throw new Error(s"Serialization of unknown directive type: $m")
  }

  override def parseBytes(bytes: Array[Byte]): Try[Directive] = Try(bytes.head).flatMap {
    case CoinbaseDirective.`TypeId` => CoinbaseDirectiveSerializer.parseBytes(bytes.tail)
    case TransferDirective.`TypeId` => TransferDirectiveSerializer.parseBytes(bytes.tail)
    case ScriptedAssetDirective.`TypeId` => ScriptedAssetDirectiveSerializer.parseBytes(bytes.tail)
    case AssetIssuingDirective.`TypeId` => AssetIssuingDirectiveSerializer.parseBytes(bytes.tail)
    case CommitmentDirective.`TypeId` => CommitmentDirectiveSerializer.parseBytes(bytes.tail)
    case t => Failure(new Error(s"Got unknown typeId: $t"))
  }
}
