package scalafix.internal.rule

import scala.meta._
import metaconfig.{Conf, Configured}
import scalafix.rule.SemanticRule
import scalafix.util.SemanticdbIndex
import scalafix.rule.{Rule, RuleCtx}
import scalafix.lint.LintMessage
import scalafix.lint.LintCategory
import scalafix.util.SymbolMatcher
import scalafix.internal.config.Config
import scalafix.syntax._

final case class Disable(index: SemanticdbIndex, configuration: Config[Disable])
    extends SemanticRule(index, "Disable")
    with Product {

  private lazy val errorCategory: LintCategory =
    LintCategory.error(
      """Some constructs are unsafe to use and should be avoided""".stripMargin
    )

  private lazy val disabledSymbol: SymbolMatcher =
    SymbolMatcher.normalized(configuration.symbols: _*)

  override def init(config: Conf): Configured[Rule] =
    config
      .getOrElse[Config[Disable]]("Disable")(Config.empty)(Config.decoder)
      .map(Disable(index, _))

  override def check(ctx: RuleCtx): Seq[LintMessage] =
    ctx.index.names.collect {
      case ResolvedName(
          pos,
          disabledSymbol(Symbol.Global(_, signature)),
          false) =>
        errorCategory
          .copy(id = signature.name)
          .at(s"${signature.name} is disabled", pos)
    }
}
