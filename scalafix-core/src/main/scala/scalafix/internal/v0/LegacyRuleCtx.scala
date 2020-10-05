package scalafix.internal.v0

import scala.meta.Input
import scala.meta.Tree
import scala.meta.contrib.AssociatedComments
import scala.meta.tokens.Tokens

import org.scalameta.FileLine
import scalafix.internal.patch.LegacyPatchOps
import scalafix.util.MatchingParens
import scalafix.util.SemanticdbIndex
import scalafix.util.TokenList
import scalafix.v0.RuleCtx
import scalafix.v1.SyntacticDocument

class LegacyRuleCtx(doc: SyntacticDocument)
    extends RuleCtx
    with LegacyPatchOps {
  override def tree: Tree = doc.tree
  override def input: Input = doc.input
  override def tokens: Tokens = doc.tokens
  override def matchingParens: MatchingParens = doc.matchingParens
  override def tokenList: TokenList = doc.tokenList
  override def comments: AssociatedComments = doc.comments
  override def index(implicit index: SemanticdbIndex): SemanticdbIndex =
    index
  override def debugIndex()(
      implicit index: SemanticdbIndex,
      fileLine: FileLine
  ): Unit =
    throw new UnsupportedOperationException
  override private[scalafix] def toks(t: Tree) =
    t.tokens(doc.internal.config.dialect)
  override private[scalafix] def config = doc.internal.config
  override private[scalafix] def escapeHatch = doc.internal.escapeHatch.value
}
