package scalafix.internal.rule

import scala.meta._
import scalafix.Patch
import scalafix.rule.Rule
import scalafix.rule.RuleCtx
import scalafix.syntax._

case object DottyKeywords extends Rule("DottyKeywords") {

  override def description: String =
    "Rewrite that replaces enum and inline with `enum` and `inline` for compatibility with Dotty"

  override def fix(ctx: RuleCtx): Patch =
    ctx.tree.collect {
      case name @ Name("enum") =>
        ctx.replaceTree(name, s"`enum`").atomic
      case name @ Name("inline") if !name.parents.exists(_.is[Mod.Annot]) =>
        ctx.replaceTree(name, s"`inline`").atomic
    }.asPatch
}
