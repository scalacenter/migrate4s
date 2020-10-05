package scalafix.testkit

import scala.meta._
import scala.meta.internal.symtab.SymbolTable

import metaconfig.Conf
import metaconfig.internal.ConfGet
import metaconfig.typesafeconfig.typesafeConfigMetaconfigParser
import scalafix.internal.config.ScalafixConfig
import scalafix.internal.diff.DiffDisable
import scalafix.internal.v1.LazyValue
import scalafix.internal.v1.Rules
import scalafix.v1
import scalafix.v1.Configuration
import scalafix.v1.RuleDecoder

final class RuleTest(
    val path: TestkitPath,
    val run: () => (Rules, v1.SemanticDocument)
)

object RuleTest {
  private[scalafix] def fromPath(
      props: TestkitProperties,
      test: TestkitPath,
      classLoader: ClassLoader,
      symtab: SymbolTable
  ): RuleTest = {
    val run: () => (Rules, v1.SemanticDocument) = { () =>
      val input = test.toInput
      val tree = input.parse[Source].get
      val comment = SemanticRuleSuite.findTestkitComment(tree.tokens)
      val syntax = comment.syntax.stripPrefix("/*").stripSuffix("*/")
      val conf = Conf.parseString(test.testName, syntax).get
      val scalafixConfig = conf.as[ScalafixConfig].get
      val doc = v1.SyntacticDocument(
        tree.pos.input,
        LazyValue.now(tree),
        DiffDisable.empty,
        scalafixConfig
      )
      val sdoc =
        v1.SemanticDocument.fromPath(
          doc,
          test.semanticdbPath,
          classLoader,
          symtab,
          () => None
        )
      val decoderSettings =
        RuleDecoder.Settings().withConfig(scalafixConfig)
      val decoder = RuleDecoder.decoder(decoderSettings)
      val rulesConf = ConfGet
        .getKey(conf, "rules" :: "rule" :: Nil)
        .getOrElse(Conf.Lst(Nil))
      val config = Configuration()
        .withConf(conf)
        .withScalaVersion(props.scalaVersion)
        .withScalacOptions(props.scalacOptions)
        .withScalacClasspath(props.inputClasspath.entries)
      val rules = decoder.read(rulesConf).get.withConfiguration(config).get
      (rules, sdoc)
    }

    new RuleTest(test, run)
  }
}
