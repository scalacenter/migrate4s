package test.testRules

import org.scalatest_autofix.matchers.should.Matchers._

object ScalatestAutofixRule {
  def foo(): Unit = shouldBe(1)
}
