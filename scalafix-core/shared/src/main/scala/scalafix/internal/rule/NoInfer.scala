package scalafix.internal.rule

import metaconfig.{Conf, Configured}

import scala.meta._
import scalafix.internal.config.NoInferConfig
import scalafix.lint.LintCategory
import scalafix.lint.LintMessage
import scalafix.rule.{Rule, RuleCtx, SemanticRule}
import scalafix.util.SemanticdbIndex
import scalafix.util.SymbolMatcher

case class NoInfer(index: SemanticdbIndex,
                   config: NoInferConfig = NoInferConfig.default)
    extends SemanticRule(index, "NoInfer")
    with Product {
  private val badSymbol = SymbolMatcher.exact(NoInfer.badSymbols ++ config.extraSymbols: _*)

  def this(index: SemanticdbIndex) =
    this(index, NoInferConfig.default)

  override def init(config: Conf): Configured[Rule] =
    config
      .getOrElse("noInfer")(NoInferConfig.default)
      .map(c => NoInfer(index, c))

  val error: LintCategory =
    LintCategory.error(
      """The Scala compiler sometimes infers a too generic type such as Any.
        |If this is intended behavior, then the type should be explicitly type
        |annotated in the source.""".stripMargin
    )

  override def check(ctx: RuleCtx): Seq[LintMessage] = {

      ctx.index.synthetics.flatMap {
      case Synthetic(pos, _, names) =>
        names.collect {
          case ResolvedName(_, badSymbol(Symbol.Global(_, signature)), _) =>
            val categoryId = signature.name.toLowerCase()
            error
              .copy(id = categoryId)
              .at(s"Inferred ${signature.name}", pos)
        }
    }
  }
}

case object NoInfer {
  lazy val badSymbols: List[Symbol] = List(
    Symbol("_root_.java.io.Serializable#"),
    Symbol("_root_.scala.Any#"),
    Symbol("_root_.scala.AnyVal#"),
    Symbol("_root_.scala.Product#")
  )

  def badSymbolNames: List[String] = badSymbols.collect {
    case Symbol.Global(_, signature) => signature.name
  }
}
