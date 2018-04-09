package encry.modifiers.state

import encry.modifiers.InstanceFactory
import encry.modifiers.state.box.PubKeyInfoBoxSerializer
import org.scalatest.FunSuite

class PubKeyInfoBoxSerializerTest extends FunSuite with InstanceFactory {

  test("toBytes & parseBytes") {

    val bx = pubKeyInfoBox

    val bxSerialized = bx.bytes

    val bxDeserialized = PubKeyInfoBoxSerializer.parseBytes(bxSerialized)

    assert(bxDeserialized.isSuccess, "Deserialization failed.")

    assert(bx.bytes sameElements bxDeserialized.get.bytes, "Id mismatch.")
  }
}
