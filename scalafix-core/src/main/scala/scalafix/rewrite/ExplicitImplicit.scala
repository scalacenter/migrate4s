package scalafix.rewrite

import scala.{meta => m}
import scalafix.util.Patch
import scalafix.util.Whitespace
import scala.collection.immutable.Seq
import scalafix.util.TokenPatch

case object ExplicitImplicit extends Rewrite[ScalafixMirror] {
  // Don't explicitly annotate vals when the right-hand body is a single call
  // to `implicitly`. Prevents ambiguous implicit. Not annotating in such cases,
  // this a common trick employed implicit-heavy code to workaround SI-2712.
  // Context: https://gitter.im/typelevel/cats?at=584573151eb3d648695b4a50
  private def isImplicitly(term: m.Term): Boolean = term match {
    case m.Term.ApplyType(m.Term.Name("implicitly"), _) => true
    case _ => false
  }
  override def rewrite[T <: ScalafixMirror](ctx: RewriteCtx[T]): Seq[Patch] = {
    import scala.meta._
    import ctx._
    def fix(defn: Defn, body: Term): Seq[Patch] = {
      import ctx.tokenList._
      for {
        start <- defn.tokens.headOption
        end <- body.tokens.headOption
        // Left-hand side tokens in definition.
        // Example: `val x = ` from `val x = rhs.banana`
        lhsTokens = slice(start, end)
        replace <- lhsTokens.reverseIterator.find(x =>
          !x.is[Token.Equals] && !x.is[Whitespace])
        typ <- mirror.typeSignature(defn)
      } yield TokenPatch.AddRight(replace, s": ${typ.syntax}")
    }.to[Seq]
    tree.collect {
      case t @ m.Defn.Val(mods, _, None, body)
          if mods.exists(_.syntax == "implicit") &&
            !isImplicitly(body) =>
        fix(t, body)
      case t @ m.Defn.Def(mods, _, _, _, None, body)
          if mods.exists(_.syntax == "implicit") =>
        fix(t, body)
    }.flatten
  }
}
