package scalafix.testkit

import scala.meta._

import org.scalatest.Suite
import org.scalatest.Tag
import org.scalatest.TestRegistration
import scalafix.internal.config.ScalafixConfig
import scalafix.internal.patch.PatchInternals
import scalafix.internal.v1.Rules
import scalafix.syntax._
import scalafix.v1._

/**
 * Utility to unit test syntactic rules. <p> Mix-in AnyFunSuiteLike or the
 * testing style of your choice if you add your own tests.
 *
 * @param rule
 *   the default rule to use from `check` /`checkDiff`.
 */
abstract class AbstractSyntacticRuleSuite()
    extends Suite
    with TestRegistration
    with DiffAssertions {

  def checkDiff(
      rule: SyntacticRule,
      original: Input,
      expected: String,
      testTags: Tag*
  ): Unit = {
    registerTest(original.label, testTags: _*) {
      val scalaVersion = ScalafixConfig.default.scalaVersion
      val doc = SyntacticDocument.fromInput(original, scalaVersion)
      val rules = Rules(List(rule))
      val resultWithContext = rules.syntacticPatch(doc, suppress = true)
      val obtained = resultWithContext.fixed
      val diff = PatchInternals.unifiedDiff(
        original,
        Input.VirtualFile(original.label, obtained)
      )
      assertNoDiff(diff, expected)
    }
  }

  def check(
      rule: SyntacticRule,
      name: String,
      original: String,
      expected: String,
      testTags: Tag*
  ): Unit = {
    registerTest(name, testTags: _*) {
      val scalaVersion = ScalafixConfig.default.scalaVersion
      val doc =
        SyntacticDocument.fromInput(Input.String(original), scalaVersion)
      val rules = Rules(List(rule))
      val resultWithContext = rules.syntacticPatch(doc, suppress = true)
      val obtained = resultWithContext.fixed
      assertNoDiff(obtained, expected)
    }
  }

}
