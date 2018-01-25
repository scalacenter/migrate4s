package scalafix.internal.rule

import scala.meta._
import metaconfig.{Conf, Configured}

import scalafix.Patch
import scalafix.rule.{Rule, RuleCtx}
import scalafix.lint.LintMessage
import scalafix.lint.LintCategory
import scalafix.internal.config.{DisableSyntaxConfig, Keyword}

final case class DisableSyntax(
    config: DisableSyntaxConfig = DisableSyntaxConfig())
    extends Rule("DisableSyntax")
    with Product {

  override def description: String =
    "Linter that reports an error on a configurable set of keywords and syntax."

  override def init(config: Conf): Configured[Rule] =
    config
      .getOrElse("disableSyntax", "DisableSyntax")(DisableSyntaxConfig.default)
      .map(DisableSyntax(_))

  private def checkRegex(ctx: RuleCtx): Seq[LintMessage] = {
    def pos(offset: Int): Position =
      Position.Range(ctx.input, offset, offset)
    val regexLintMessages = Seq.newBuilder[LintMessage]
    config.regex.foreach { regex =>
      val matcher = regex.value.matcher(ctx.input.chars)
      val pattern = regex.value.pattern
      val message = regex.message.getOrElse(s"$pattern is disabled")
      while (matcher.find()) {
        regexLintMessages +=
          errorCategory
            .copy(id = regex.id.getOrElse(pattern))
            .at(message, pos(matcher.start))
      }
    }
    regexLintMessages.result()
  }

  private def checkTokens(ctx: RuleCtx): Seq[LintMessage] = {
    ctx.tree.tokens.collect {
      case token @ Keyword(keyword) if config.isDisabled(keyword) =>
        errorCategory
          .copy(id = s"keywords.$keyword")
          .at(s"$keyword is disabled", token.pos)
      case token @ Token.Semicolon() if config.noSemicolons =>
        error("noSemicolons", token)
      case token @ Token.Tab() if config.noTabs =>
        error("noTabs", token)
      case token @ Token.Xml.Start() if config.noXml =>
        error("noXml", token)
    }
  }

  private def checkTree(ctx: RuleCtx): Seq[LintMessage] = {
    object AbstractWithVals {
      def unapply(t: Tree): Option[List[Defn.Val]] = {
        val stats = t match {
          case Defn.Class(mods, _, _, _, templ)
              if mods.exists(_.is[Mod.Abstract]) =>
            templ.stats
          case Defn.Trait(_, _, _, _, templ) => templ.stats
          case _ => List.empty
        }
        val vals = stats.flatMap {
          case v: Defn.Val => Some(v)
          case _ => None
        }
        if (vals.isEmpty) None else Some(vals)
      }
    }

    def hasDefaultArgs(d: Defn.Def): Boolean =
      d.paramss.exists(_.exists(_.default.isDefined))

    def hasNonImplicitParam(d: Defn.Def): Boolean =
      d.paramss.exists(_.exists(_.mods.forall(!_.is[Mod.Implicit])))

    ctx.tree.collect {
      case t @ mod"+" if config.noCovariantTypes =>
        Seq(
          errorCategory
            .copy(id = "covariant")
            .at(
              "Covariant types could lead to error-prone situations.",
              t.pos
            )
        )
      case t @ mod"-" if config.noContravariantTypes =>
        Seq(
          errorCategory
            .copy(id = "contravariant")
            .at(
              "Contravariant types could lead to error-prone situations.",
              t.pos
            )
        )
      case d: Defn.Def if hasDefaultArgs(d) && config.noDefaultArgs =>
        Seq(
          errorCategory
            .copy(id = "defaultArgs")
            .at(
              "Default args makes it hard to use methods as functions.",
              d.pos)
        )
      case t @ AbstractWithVals(vals) if config.noValInAbstract =>
        vals.map { v =>
          errorCategory
            .copy(id = "valInAbstract")
            .at(
              "val definitions in traits/abstract classes may cause initialization bugs",
              v.pos)
        }
      case t @ Defn.Object(mods, _, _)
          if mods.exists(_.is[Mod.Implicit]) && config.noImplicitObject =>
        Seq(
          errorCategory
            .copy(id = "implicitObject")
            .at("implicit objects may cause implicit resolution errors", t.pos)
        )
      case t @ Defn.Def(mods, _, _, paramss, _, _)
          if mods.exists(_.is[Mod.Implicit]) &&
            hasNonImplicitParam(t) &&
            config.noImplicitConversion =>
        Seq(
          errorCategory
            .copy(id = "implicitConversion")
            .at(
              "implicit conversions weaken type safety and always can be replaced by explicit conversions",
              t.pos)
        )
    }.flatten
  }

  private def fixTree(ctx: RuleCtx): Patch = {
    ctx.tree.collect {
      case t @ Defn.Val(mods, _, _, _)
          if config.noFinalVal &&
            mods.exists(_.is[Mod.Final]) =>
        val finalTokens =
          mods.find(_.is[Mod.Final]).map(_.tokens.toList).getOrElse(List.empty)
        ctx.removeTokens(finalTokens) +
          ctx.removeTokens(finalTokens.flatMap(ctx.tokenList.trailingSpaces))
    }.asPatch
  }

  override def fix(ctx: RuleCtx): Patch = {
    val lints =
      (checkTree(ctx) ++ checkTokens(ctx) ++ checkRegex(ctx)).map(ctx.lint)
    fixTree(ctx) ++ lints
  }

  private val errorCategory: LintCategory =
    LintCategory.error(
      "Some constructs are unsafe to use and should be avoided")

  private def error(keyword: String, token: Token): LintMessage =
    errorCategory.copy(id = keyword).at(s"$keyword is disabled", token.pos)
}
