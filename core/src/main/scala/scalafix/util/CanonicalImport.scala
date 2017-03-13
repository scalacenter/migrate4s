package scalafix.util

import scala.collection.immutable.Seq
import scala.meta._
import scala.meta.tokens.Token.Comment
import scalafix.rewrite.AnyCtx
import scalafix.rewrite.ScalafixCtx
import scalafix.rewrite.SyntaxCtx

object CanonicalImport {
  def fromWildcard(ref: Term.Ref,
                   wildcard: Importee.Wildcard,
                   unimports: Seq[Importee.Unimport],
                   renames: Seq[Importee.Rename])(
      implicit ctx: AnyCtx,
      ownerImport: Import
  ): CanonicalImport =
    new CanonicalImport(
      ref,
      wildcard,
      unimports,
      renames,
      leadingComments = ctx.comments.leading(ownerImport),
      trailingComments = ctx.comments.trailing(ownerImport) ++
        (wildcard +: unimports).flatMap(ctx.comments.trailing),
      None
    ) {}
  def fromImportee(ref: Term.Ref, importee: Importee)(
      implicit ctx: AnyCtx,
      ownerImport: Import
  ): CanonicalImport =
    new CanonicalImport(
      ref,
      importee,
      Nil,
      Nil,
      leadingComments = ctx.comments.leading(ownerImport),
      trailingComments = ctx.comments.trailing(ownerImport) ++
        ctx.comments.trailing(importee),
      None
    ) {}
}

/** A canonical imports is the minimal representation for a single import statement
  *
  * Only construct this class from custom constructors in the companion object.
  * This class should be sealed abstract but the abstract modifier removes the
  * convenient copy method.
  */
sealed case class CanonicalImport(
    ref: Term.Ref,
    importee: Importee,
    unimports: Seq[Importee.Unimport],
    renames: Seq[Importee.Rename],
    leadingComments: Set[Comment],
    trailingComments: Set[Comment],
    fullyQualifiedRef: Option[Term.Ref]
)(implicit ctx: AnyCtx) {

  def isRootImport: Boolean =
    ref.collect {
      case q"_root_.$_" => true
    }.nonEmpty

  def addRootImport(ref: Term.Ref): Term.Ref =
    if (!isRootImport) ref
    else {
      ("_root_." + ref.syntax).parse[Term].get.asInstanceOf[Term.Ref]
    }

  def withFullyQualifiedRef(fqnRef: Option[Term.Ref]): CanonicalImport =
    copy(fullyQualifiedRef = fqnRef.map(addRootImport))

  def isSpecialImport: Boolean = {
    val base = ref.syntax
    base.startsWith("scala.language") ||
    base.startsWith("scala.annotation")
  }
  private def extraImportees = renames ++ unimports
  def withoutLeading(leading: Set[Comment]): CanonicalImport =
    copy(leadingComments = leadingComments.filterNot(leading))
  def tree: Import = Import(Seq(Importer(ref, unimports :+ importee)))
  def syntax: String =
    s"${leading}import $importerSyntax$trailing"
  def leading: String =
    if (leadingComments.isEmpty) ""
    else leadingComments.mkString("", "\n", "\n")
  def trailing: String =
    if (trailingComments.isEmpty) ""
    else trailingComments.mkString(" ", "\n", "")
  def importerSyntax: String =
    s"$refSyntax.$importeeSyntax"
  private def curlySpace =
    if (ctx.config.imports.spaceAroundCurlyBrace) " "
    else ""

  def actualRef: Term.Ref =
    if (ctx.config.imports.expandRelative) fullyQualifiedRef.getOrElse(ref)
    else ref
  def refSyntax: String =
    actualRef.syntax
  def importeeSyntax: String =
    if (extraImportees.nonEmpty)
      s"""{$curlySpace${extraImportees
        .map(_.syntax)
        .mkString(", ")}, $importee$curlySpace}"""
    else
      importee match {
        case i: Importee.Rename => s"{$curlySpace$i$curlySpace}"
        case i => i.syntax
      }
  private def importeeOrder = importee match {
    case i: Importee.Rename => (1, i.name.syntax)
    case i: Importee.Wildcard => (0, i.syntax)
    case i => (1, i.syntax)
  }
  def sortOrder: (String, (Int, String)) =
    (refSyntax, importeeOrder)
  def structure: String = Importer(ref, Seq(importee)).structure
}
