package scalafix
package patch

import metaconfig._
import scala.collection.immutable.Seq
import scala.meta._
import scala.meta.contrib._
import scala.meta.internal.ast.Helpers._
import scala.meta.tokens.Token
import scala.meta.tokens.Token
import scalafix.config._
import scalafix.syntax._
import scalafix.patch.TokenPatch.Add
import scalafix.patch.TokenPatch.Remove
import scalafix.patch.TreePatch.RenamePatch
import scalafix.patch.TreePatch.Replace

import difflib.DiffUtils

/** A data structure that can produce a .patch file.
  *
  * The best way to build a Patch is with a RewriteCtx inside a Rewrite.
  * For example, `Rewrite.syntactic(ctx => ctx.addLeft(ctx.tree.tokens.head): Patch)`
  *
  * Patches can be composed with Patch.+ and Patch.++. A Seq[Patch] can be combined
  * into a single patch with `Seq[Patch](...).asPatch` with `import scalafix._`.
  *
  * Patches are split into low-level token patches and high-level tree patches.
  * A token patch works on scala.meta.Token and provides surgical precision over
  * how details like formatting are managed by the rewrite.
  *
  * NOTE: Patch current only works for a single file, but it may be possible
  * to add support in the future for combining patches for different files
  * with Patch + Patch.
  */
sealed abstract class Patch {
  // NOTE: potential bottle-neck, this might be very slow for large
  // patches. We might want to group related patches and enforce some ordering.
  def +(other: Patch): Patch =
    if (this eq other) this
    else if (isEmpty) other
    else if (other.isEmpty) this
    else Concat(this, other)
  def ++(other: Seq[Patch]): Patch = other.foldLeft(this)(_ + _)
  def isEmpty: Boolean = this == EmptyPatch
  def nonEmpty: Boolean = !isEmpty
}

private[scalafix] case class Concat(a: Patch, b: Patch) extends Patch
private[scalafix] case object EmptyPatch extends Patch
abstract class TreePatch extends Patch
abstract class TokenPatch(val tok: Token, val newTok: String) extends Patch {
  override def toString: String =
    s"TokenPatch(${tok.syntax.revealWhiteSpace}, ${tok.structure}, $newTok)"
}

abstract class ImportPatch(val importer: Importer) extends TreePatch {
  def importee: Importee = importer.importees.head
  def toImport: Import = Import(Seq(importer))
}

private[scalafix] object TreePatch {
  trait RenamePatch
  case class Rename(from: Name, to: Name) extends TreePatch with RenamePatch
  case class RenameSymbol(from: Symbol, to: Name, normalize: Boolean = false)
      extends TreePatch
      with RenamePatch {
    private lazy val resolvedFrom = if (normalize) from.normalized else from
    def matches(symbol: Symbol): Boolean =
      if (normalize) symbol.normalized == resolvedFrom
      else symbol == resolvedFrom
  }

  @DeriveConfDecoder
  case class Replace(from: Symbol,
                     to: Term.Ref,
                     additionalImports: List[Importer] = Nil,
                     normalized: Boolean = true)
      extends TreePatch {
    require(to.isStableId)
  }
  case class RemoveGlobalImport(override val importer: Importer)
      extends ImportPatch(importer)
  case class AddGlobalImport(override val importer: Importer)
      extends ImportPatch(importer)
}

private[scalafix] object TokenPatch {
  case class Remove(override val tok: Token) extends TokenPatch(tok, "")
  case class Add(override val tok: Token,
                 addLeft: String,
                 addRight: String,
                 keepTok: Boolean = true)
      extends TokenPatch(tok,
                         s"""$addLeft${if (keepTok) tok else ""}$addRight""")

}
object Patch {

  /** Combine a sequence of patches into a single patch */
  def fromSeq(seq: scala.Seq[Patch]): Patch =
    seq.foldLeft(empty)(_ + _)

  /** A patch that does no diff/rewrite */
  val empty: Patch = EmptyPatch

  private def merge(a: TokenPatch, b: TokenPatch): TokenPatch = (a, b) match {
    case (add1: Add, add2: Add) =>
      Add(add1.tok,
          add1.addLeft + add2.addLeft,
          add1.addRight + add2.addRight,
          add1.keepTok && add2.keepTok)
    case (_: Remove, add: Add) => add.copy(keepTok = false)
    case (add: Add, _: Remove) => add.copy(keepTok = false)
    case (rem: Remove, _: Remove) => rem
    case _ => throw Failure.TokenPatchMergeError(a, b)
  }
  private[scalafix] def apply(p: Patch,
                              ctx: RewriteCtx,
                              mirror: Option[Mirror]): String = {
    val patches = underlying(p)
    val semanticPatches = patches.collect { case tp: TreePatch => tp }
    mirror match {
      case Some(x) =>
        semanticApply(underlying(p))(ctx, x)
      case None =>
        if (semanticPatches.nonEmpty)
          throw Failure.Unsupported(
            s"Semantic patches are not supported without a Mirror: $semanticPatches")
        syntaxApply(ctx, underlying(p).collect {
          case tp: TokenPatch => tp
        })
    }
  }

  private def syntaxApply(ctx: RewriteCtx, patches: Seq[TokenPatch]): String = {
    val patchMap = patches
      .groupBy(_.tok.hash)
      .mapValues(_.reduce(merge).newTok)

    ctx.tokens.toIterator
      .map(tok => patchMap.getOrElse(tok.hash, tok.syntax))
      .mkString
  }

  private def semanticApply(patches: Seq[Patch])(implicit ctx: RewriteCtx,
                                                 mirror: Mirror): String = {
    val ast = ctx.tree
    val tokenPatches = patches.collect { case e: TokenPatch => e }
    val renamePatches = Renamer.toTokenPatches(patches.collect {
      case e: RenamePatch => e
    })
    val replacePatches = Replacer.toTokenPatches(ast, patches.collect {
      case e: Replace => e
    })
    val importPatches = OrganizeImports.organizeImports(
      patches.collect { case e: ImportPatch => e } ++
        replacePatches.collect { case e: ImportPatch => e }
    )
    val replaceTokenPatches = replacePatches.collect {
      case t: TokenPatch => t
    }
    syntaxApply(
      ctx,
      importPatches ++
        tokenPatches ++
        replaceTokenPatches ++
        renamePatches
    )
  }

  private def underlying(patch: Patch): Seq[Patch] = {
    val builder = Seq.newBuilder[Patch]
    def loop(patch: Patch): Unit = patch match {
      case Concat(a, b) =>
        loop(a)
        loop(b)
      case els =>
        builder += els
    }
    loop(patch)
    builder.result()
  }

  def unifiedDiff(original: Input, revised: Input): String = {
    import scala.collection.JavaConverters._
    val originalLines = original.asString.lines.toSeq.asJava
    val diff =
      DiffUtils.diff(originalLines, revised.asString.lines.toSeq.asJava)
    DiffUtils
      .generateUnifiedDiff(original.label,
                           revised.label,
                           originalLines,
                           diff,
                           3)
      .asScala
      .mkString("\n")
  }
}
