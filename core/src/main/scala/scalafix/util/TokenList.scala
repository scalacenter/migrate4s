package scalafix.util

import scala.meta.tokens.Token
import scala.meta.tokens.Tokens

class TokenList(tokens: Tokens) {
  private[this] val tok2idx = {
    val map = Map.newBuilder[Token, Int]
    var i = 0
    tokens.foreach { tok =>
      map += (tok -> i)
      i += 1
    }
    map.result()
  }

  def find(start: Token)(f: Token => Boolean): Option[Token] = {
    def loop(curr: Token): Option[Token] = {
      if (f(curr)) Option(curr)
      else {
        val iter = next(curr)
        if (iter == curr) None // reached EOF
        else loop(iter)
      }
    }
    loop(next(start))
  }

  def slice(from: Token, to: Token): Seq[Token] = {
    val builder = Seq.newBuilder[Token]
    var curr = next(from)
    while (curr.start < to.start) {
      builder += curr
      curr = next(curr)
    }
    builder.result()
  }

  def next(token: Token): Token = {
    tok2idx.get(token) match {
      case Some(i) if tokens.length > i + 1 =>
        tokens(i + 1)
      case _ => token
    }
  }

  def prev(token: Token): Token = {
    tok2idx.get(token) match {
      case Some(i) if tokens.length > i - 1 =>
        tokens(i - 1)
      case _ => token
    }
  }

}
