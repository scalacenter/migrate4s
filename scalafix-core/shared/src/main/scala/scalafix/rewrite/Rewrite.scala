package scalafix
package rewrite

import scala.collection.immutable.Seq
import scala.meta._
import scalafix.config.LazyMirror
import scalafix.config.PatchConfig
import scalafix.config.RewriteKind
import scalafix.syntax._
import metaconfig.ConfDecoder
import metaconfig.ConfError
import metaconfig.Configured
import sourcecode.Name

/** A Rewrite is a program that produces a Patch from a scala.meta.Tree. */
abstract class Rewrite(implicit rewriteName: Name) { self =>

  /** Build patch for a single tree/compilation unit.
    *
    * Override this method to implement a rewrite.
    */
  def rewrite(ctx: RewriteCtx): Patch

  /** Combine this rewrite with another rewrite. */
  final def andThen(other: Rewrite): Rewrite = Rewrite.merge(this, other)

  /** Returns string output of applying this single patch. */
  final def apply(ctx: RewriteCtx): String = apply(ctx, rewrite(ctx))
  final def apply(
      input: Input,
      config: ScalafixConfig = ScalafixConfig.default): String = {
    val ctx = RewriteCtx(config.dialect(input).parse[Source].get, config)
    apply(ctx, rewrite(ctx))
  }
  final protected def apply(ctx: RewriteCtx, patch: Patch): String =
    Patch(patch, ctx, mirrorOption)

  /** Returns unified diff from applying this patch */
  final def diff(ctx: RewriteCtx): String =
    diff(ctx, rewrite(ctx))
  final protected def diff(ctx: RewriteCtx, patch: Patch): String = {
    val original = ctx.tree.input
    Patch.unifiedDiff(
      original,
      Input.LabeledString(original.label, apply(ctx, patch)))

  }

  final def name: String = rewriteName.value
  final override def toString: String = name

  // NOTE. This is kind of hacky and hopefully we can find a better workaround.
  // The challenge is the following:
  // - a.andThen(b) needs to work for mixing semantic + syntactic rewrites.
  // - applied/appliedDiff should work without passing in Mirror explicitly
  protected[scalafix] def mirrorOption: Option[Mirror] = None
}

abstract class SemanticRewrite(mirror: Mirror)(implicit name: Name)
    extends Rewrite {
  implicit val ImplicitMirror: Mirror = mirror
  override def mirrorOption: Option[Mirror] = Some(mirror)
}

object Rewrite {
  val syntaxRewriteConfDecoder: ConfDecoder[Rewrite] =
    config.rewriteConfDecoderSyntactic(config.baseSyntacticRewriteDecoder)
  def emptyConfigured: Configured[Rewrite] = Configured.Ok(empty)
  def empty: Rewrite = syntactic(_ => Patch.empty)
  def emptyFromMirrorOpt(mirror: Option[Mirror]): Rewrite =
    mirror.fold(empty)(emptySemantic)
  def combine(rewrites: Seq[Rewrite]): Rewrite =
    rewrites.foldLeft(empty)(_ andThen _)
  // NOTE: this is one example where the Rewrite.wrappedRewrite hack leaks.
  // An empty semantic rewrite is necessary to support patches from .scalafix.conf
  // like `patches.addGlobalImport = ???`.
  // TODO(olafur) get rid of this rewrite by converting `patches.addGlobalImport`
  // into an actual rewrite instead of handling it specially inside Patch.applied.
  private[scalafix] def emptySemantic(mirror: Mirror): Rewrite =
    semantic(x => y => Patch.empty)(Name("empty"))(mirror)

  /** Creates a syntactic rewrite. */
  def syntactic(f: RewriteCtx => Patch)(implicit name: Name): Rewrite =
    new Rewrite() {
      override def rewrite(ctx: RewriteCtx): Patch = f(ctx)
    }

  /** Creates a semantic rewrite. */
  def semantic(f: Mirror => RewriteCtx => Patch)(
      implicit name: Name): Mirror => Rewrite = { mirror =>
    new SemanticRewrite(mirror) {
      override def rewrite(ctx: RewriteCtx): Patch = f(mirror)(ctx)
    }
  }

  /** Creates a rewrite that always returns the same patch. */
  def constant(name: String, patch: Patch, mirror: Mirror): Rewrite =
    new SemanticRewrite(mirror)(Name(name)) {
      override def rewrite(ctx: RewriteCtx): Patch = patch
    }

  // Build rewrite from PatchConfig.
  def patchRewrite(
      patches: PatchConfig,
      getMirror: LazyMirror): Configured[Option[Rewrite]] = {
    val configurationPatches = patches.all
    if (configurationPatches.isEmpty) Configured.Ok(None)
    else {
      getMirror(RewriteKind.Semantic) match {
        case None =>
          ConfError
            .msg(".scalafix.conf patches require the Semantic API.")
            .notOk
        case Some(mirror) =>
          val rewrite = Rewrite.constant(
            "ConfigPatches",
            configurationPatches.asPatch,
            mirror
          )
          Configured.Ok(Some(rewrite))
      }
    }
  }

  /** Combine two rewrites into a single rewrite */
  def merge(a: Rewrite, b: Rewrite): Rewrite = {
    val newName =
      if (a.name == "empty") b.name
      else if (b.name == "empty") a.name
      else s"${a.name}+${b.name}"
    new Rewrite()(Name(newName)) {
      override def rewrite(ctx: RewriteCtx): Patch =
        a.rewrite(ctx) + b.rewrite(ctx)
      override def mirrorOption: Option[Mirror] =
        (a.mirrorOption, b.mirrorOption) match {
          case (Some(m1), Some(m2)) =>
            if (m1 ne m2) throw Failure.MismatchingMirror(m1, m2)
            else Some(m1)
          case (a, b) => a.orElse(b)
        }
    }
  }
}
