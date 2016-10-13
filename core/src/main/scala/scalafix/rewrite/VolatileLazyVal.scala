package scalafix.rewrite

import scala.meta._
import scalafix.util.Patch

case object VolatileLazyVal extends Rewrite {
  private object NonVolatileLazyVal {
    def unapply(defn: Defn.Val): Option[Token] = {
      defn.mods.collectFirst {
        case x if x.syntax == "@volatile" =>
          None
        case x if x.syntax == "lazy" =>
          Some(defn.mods.head.tokens.head)
      }
    }.flatten
  }
  override def rewrite(ast: Tree, ctx: RewriteCtx): Seq[Patch] = {
    ast.collect {
      case NonVolatileLazyVal(tok) => Patch(tok, tok, s"@volatile ${tok.syntax}")
    }
  }
}
