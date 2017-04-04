package scalafix.rewrite

import scala.meta._
import scala.meta.tokens.Token.Comment
import scala.meta.tokens.Token.LeftBrace
import scala.meta.tokens.Token.RightBrace
import scala.meta.tokens.Token.RightParen
import scala.meta.tokens.Token.Space
import scalafix.util.Patch
import scala.collection.immutable.Seq
import scalafix.util.TokenPatch

case object ProcedureSyntax extends Rewrite[Any] {
  override def rewrite[T](ctx: RewriteCtx[T]): Seq[Patch] = {
    import ctx.tokenList._
    val patches: Seq[Patch] = ctx.tree.collect {
      case t: Defn.Def
          if t.decltpe.exists(_.tokens.isEmpty) &&
            t.body.tokens.head.is[LeftBrace] =>
        val bodyStart = t.body.tokens.head
        val defEnd = (for {
          lastParmList <- t.paramss.lastOption
          lastParam <- lastParmList.lastOption
        } yield lastParam.tokens.last).getOrElse(t.name.tokens.last)
        val closingParen =
          slice(defEnd, bodyStart).find(_.is[RightParen]).getOrElse(defEnd)
        val comment: String = {
          val between = slice(closingParen, bodyStart).mkString.trim
          if (between.nonEmpty) " " + between
          else ""
        }
        TokenPatch.AddRight(closingParen, s": Unit =")
    }
    patches
  }
}
