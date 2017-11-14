package scalafix.internal.rule

import scala.meta._
import scala.meta.contrib.Keyword
import metaconfig.{Conf, Configured}

import scalafix.rule.SemanticRule
import scalafix.util.SemanticdbIndex
import scalafix.rule.{Rule, RuleCtx}
import scalafix.lint.LintMessage
import scalafix.lint.LintCategory
import scalafix.util.SymbolMatcher
import scalafix.internal.config.DisableConfig
import scalafix.internal.config.TargetSymbolsConfig
import scalafix.syntax._

final case class Disable(
    index: SemanticdbIndex,
    config: DisableConfig)
    extends SemanticRule(index, "Disable")
    with Product {

  private lazy val errorCategory: LintCategory =
    LintCategory.error(
      """Some constructs are unsafe to use and should be avoided""".stripMargin
    )

  private lazy val disabledSymbol: SymbolMatcher =
    SymbolMatcher.normalized(config.symbols: _*)

  override def init(config: Conf): Configured[Rule] = {
    config
      .getOrElse[DisableConfig]("Disable")(DisableConfig.empty)(
        DisableConfig.reader)
      .map(Disable(index, _))
  }

  override def check(ctx: RuleCtx): Seq[LintMessage] = {
    val keywordsLints = 
      ctx.tree.tokens.collect {
        case token @ Keyword() if config.keywordsSet.contains(token.text) => {
          errorCategory
            .copy(id = token.text)
            .at(token.pos)
        }
      }

    val symbolsLints =
      ctx.index.names.collect {
        case ResolvedName(
            pos,
            disabledSymbol(Symbol.Global(_, signature)),
            false) =>
          errorCategory
            .copy(id = signature.name)
            .at(s"${signature.name} is disabled", pos)
      }

    keywordsLints ++ symbolsLints
  }
}