/*
rule = RemoveUnused
 */
package test.removeUnused

// Not available as of Scala 3.4.1
// https://github.com/scalacenter/scalafix/issues/1937
object UnusedParams {
  val f: String => Unit = unused => println("f")
  val ff = (unused: String) => println("f")
  val fs = (used: String, unused: Long) => println(used)
  def g(x: String => Unit): Unit = ???
  g{implicit string => println("g")}
}
