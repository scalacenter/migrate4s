package fix

import scala.collection.immutable.SortedMap
import com.geirsson.Future
import com.geirsson.{ fastmath, mutable }
import com.geirsson.mutable.{ CoolBuffer, unsafe }
import com.geirsson.mutable.unsafe.CoolMap

object ReplaceSymbol {
  Future.successful(1 + 2)
  fastmath.sqrt(9)
  List(1).tail.unsafeMkString
  List(1).tail.unsafeMkString("blah")
  "blah".substringFrom(1)
  "blah".substringBetween(1, 2)
  val u: unsafe.CoolMap[Int, Int] = CoolMap.empty[Int, Int]
  val v: SortedMap[Int, Int] = com.geirsson.immutable.SortedMap.empty[Int, Int]
  val x: CoolBuffer[Int] = CoolBuffer.empty[Int]
  val y: mutable.CoolBuffer[Int] = mutable.CoolBuffer.empty[Int]
  val z: com.geirsson.mutable.CoolBuffer[Int] =
    com.geirsson.mutable.CoolBuffer.empty[Int]
}