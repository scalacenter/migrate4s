package test.removeUnused

// Not available as of Scala 3.4.1
// https://github.com/scalacenter/scalafix/issues/1937
object UnusedParams {
  val f: String => Unit = _ => println("f")
  val ff = (_: String) => println("f")
  val fs = (used: String, _: Long) => println(used)
  def g(x: String => Unit): Unit = ???
  g{_ => println("g")}
}
