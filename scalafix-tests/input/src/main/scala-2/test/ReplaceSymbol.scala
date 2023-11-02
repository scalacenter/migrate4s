/*
rules = [
  "replace:scala.concurrent/com.geirsson"
]
patches.removeGlobalImports = [
  "scala.collection.mutable"
]
patches.replaceSymbols = [
  { from = "fix.ReplaceSymbol"
    to = "fix.ReplaceSymbol" }
  { from = "scala.collection.mutable.ListBuffer"
    to = "com.geirsson.mutable.CoolBuffer" }
  { from = "scala.collection.mutable.HashMap"
    to = "com.geirsson.mutable.unsafe.CoolMap" }
  { from = "scala.collection.immutable.TreeMap"
    to = "com.geirsson.immutable.SortedMap" }
  { from = "scala.math.sqrt"
    to = "com.geirsson.fastmath.sqrt" }
  // normalized symbol renames all overloaded methods
  { from = "scala.collection.IterableOnceOps.mkString"
    to = "unsafeMkString" }
  // for prior to scala 2.12
  { from = "scala.collection.TraversableOnce.mkString."
    to = "unsafeMkString" }
  // non-normalized symbol renames single method overload
  { from = "java/lang/String#substring()."
    to = "substringFrom" }
  { from = "java/lang/String#substring(+1)."
    to = "substringBetween" }
]
 */
package fix

import scala.collection.immutable.SortedMap
import scala.collection.immutable.TreeMap
import scala.collection.mutable.HashMap
import scala.collection.mutable.ListBuffer
import scala.collection.mutable
import scala.concurrent.Future

object ReplaceSymbol {
  Future.successful(1 + 2)
  math.sqrt(9)
  List(1).tail.mkString
  List(1).tail.mkString("blah")
  "blah".substring(1)
  "blah".substring(1, 2)
  val u: mutable.HashMap[Int, Int] = HashMap.empty[Int, Int]
  val v: SortedMap[Int, Int] = TreeMap.empty[Int, Int]
  val x: ListBuffer[Int] = ListBuffer.empty[Int]
  val y: mutable.ListBuffer[Int] = mutable.ListBuffer.empty[Int]
  val z: scala.collection.mutable.ListBuffer[Int] =
    scala.collection.mutable.ListBuffer.empty[Int]
}