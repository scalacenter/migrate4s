package banana.rule

import scala.meta._
import scala.meta.contrib._
import scalafix._

case class FqnRule(index: SemanticdbIndex)
    extends SemanticRule(index, "FqnRule") {
  override def fix(ctx: RuleCtx): Patch =
    ctx.addGlobalImport(importer"scala.collection.immutable").atomic
}

case object FqnRule2 extends Rule("FqnRule2") {
  override def fix(ctx: RuleCtx): Patch =
    ctx.tree.collectFirst {
      case n: Name => ctx.replaceTree(n, n.value + "2").atomic
    }.asPatch
}

object LambdaRules {
  val syntax: Rule = Rule.syntactic("syntax") { ctx =>
    ctx.addLeft(ctx.tokens.head, "// comment\n").atomic
  }

  val semantic = Rule.semantic("semantic") { implicit index => ctx =>
    ctx.addGlobalImport(importer"scala.collection.mutable").atomic
  }

}

case object PatchTokenWithEmptyRange extends Rule("PatchTokenWithEmptyRange") {
  override def fix(ctx: RuleCtx): Patch = {
    ctx.tokens.collect {
      case tok @ Token.Interpolation.SpliceEnd() =>
        ctx.addRight(tok, "a").atomic
      case tok @ Token.Xml.SpliceEnd() =>
        ctx.addRight(tok, "a").atomic
    }
  }.asPatch
}
