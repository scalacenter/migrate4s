package test.testRules

object ExplicitSynthetic {
  val list = List.apply(1)
  val apply = List.apply(1)
  def +[T](e: T): String = e.toString
  ExplicitSynthetic +[Int] 42
}