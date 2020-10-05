package scalafix.v1

import scala.meta.Dialect
import scala.meta.Input
import scala.meta.Tokens
import scala.meta.Tree
import scala.meta.contrib.AssociatedComments

import scalafix.internal.config.ScalafixConfig
import scalafix.internal.diff.DiffDisable
import scalafix.internal.patch.EscapeHatch
import scalafix.internal.v1.InternalDoc
import scalafix.internal.v1.LazyValue
import scalafix.util.MatchingParens
import scalafix.util.TokenList

final class SyntacticDocument private[scalafix] (
    private[scalafix] val internal: InternalDoc
) {
  def input: Input = internal.input
  def tree: Tree = internal.tree.value
  def tokens: Tokens = tree.tokens
  def comments: AssociatedComments = internal.comments.value
  def matchingParens: MatchingParens = internal.matchingParens.value
  def tokenList: TokenList = internal.tokenList.value
  override def toString: String = s"SyntacticDocument(${input.syntax})"
}

object SyntacticDocument {
  def fromInput(input: Input): SyntacticDocument = {
    fromInput(input, scala.meta.dialects.Scala212)
  }
  def fromInput(input: Input, dialect: Dialect): SyntacticDocument = {
    import scala.meta._
    val tree = LazyValue.later { () =>
      parsers.Parse.parseSource.apply(input, dialect).get: Tree
    }
    SyntacticDocument(
      input,
      tree,
      DiffDisable.empty,
      ScalafixConfig.default
    )
  }

  def fromTree(tree: Tree): SyntacticDocument = {
    SyntacticDocument(
      tree.pos.input,
      LazyValue.now(tree),
      DiffDisable.empty,
      ScalafixConfig.default
    )
  }

  private[scalafix] def apply(
      input: Input,
      tree: LazyValue[Tree],
      diffDisable: DiffDisable,
      config: ScalafixConfig
  ): SyntacticDocument = {
    val tokens = LazyValue.later { () =>
      tree.value.tokens
    }
    val comments = LazyValue.later { () =>
      AssociatedComments(tree.value)
    }
    val escape = LazyValue.later { () =>
      EscapeHatch(input, tree, comments, diffDisable)
    }
    val matchingParens = LazyValue.later { () =>
      MatchingParens(tokens.value)
    }
    val tokenList = LazyValue.later { () =>
      TokenList(tokens.value)
    }
    val internal = new InternalDoc(
      input = input,
      tree = tree,
      comments = comments,
      config = config,
      escapeHatch = escape,
      matchingParens = matchingParens,
      tokenList = tokenList
    )
    new SyntacticDocument(internal)
  }
}
