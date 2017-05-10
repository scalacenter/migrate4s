package banana.rewrite

import scala.meta._
import scala.meta.contrib._
import scalafix._

case class FqnRewrite(mirror: Mirror) extends SemanticRewrite(mirror) {
  override def rewrite(ctx: RewriteCtx): Patch =
    ctx.addGlobalImport(importer"scala.meta._")
}

case object FqnRewrite2 extends Rewrite {
  override def rewrite(ctx: RewriteCtx): Patch =
    ctx.tree.collectFirst {
      case n: Name => ctx.rename(n, Term.Name(n.value + "2"))
    }.asPatch
}

object LambdaRewrites {
  val syntax: Rewrite = Rewrite.syntactic { ctx =>
    ctx.addLeft(ctx.tokens.head, "// comment\n")
  }

  val semantic = Rewrite.semantic { implicit mirror => ctx =>
    ctx.addGlobalImport(importer"hello.semantic")
  }

}

case object PatchTokenWithEmptyRange extends Rewrite {
  override def rewrite(ctx: RewriteCtx): Patch = {
    ctx.tokens.collect {
      case tok @ Token.Interpolation.SpliceEnd() =>
        ctx.addRight(tok, "a")
      case tok @ Token.Xml.SpliceEnd() =>
        ctx.addRight(tok, "a")
    }
  }.asPatch
}
