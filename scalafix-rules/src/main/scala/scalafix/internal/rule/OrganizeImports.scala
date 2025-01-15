package scalafix.internal.rule

import scala.annotation.tailrec
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.util.Try

import scala.meta.Dialect
import scala.meta.Import
import scala.meta.Importee
import scala.meta.Importee.GivenAll
import scala.meta.Importee.Wildcard
import scala.meta.Importer
import scala.meta.Name
import scala.meta.Pkg
import scala.meta.Source
import scala.meta.Stat
import scala.meta.Term
import scala.meta.Tree
import scala.meta.XtensionClassifiable
import scala.meta.XtensionCollectionLikeUI
import scala.meta.XtensionSyntax
import scala.meta.dialects
import scala.meta.inputs.Input.File
import scala.meta.inputs.Input.VirtualFile
import scala.meta.inputs.Position
import scala.meta.tokens.Token

import metaconfig.Conf
import metaconfig.ConfDecoder
import metaconfig.ConfEncoder
import metaconfig.ConfOps
import metaconfig.Configured
import metaconfig.internal.ConfGet
import scalafix.internal.config.ScalaVersion
import scalafix.internal.rule.ImportMatcher._
import scalafix.lint.Diagnostic
import scalafix.patch.Patch
import scalafix.v1.Configuration
import scalafix.v1.Rule
import scalafix.v1.RuleName.stringToRuleName
import scalafix.v1.SemanticDocument
import scalafix.v1.SemanticRule
import scalafix.v1.Symbol
import scalafix.v1.SymbolInformation
import scalafix.v1.XtensionSeqPatch
import scalafix.v1.XtensionTreeScalafix

class OrganizeImports(
    config: OrganizeImportsConfig,
    // shadows the default implicit always on scope (Dialect.current, matching the runtime Scala version)
    implicit val targetDialect: Dialect = Dialect.current,
    scala3DialectForScala3Paths: Boolean = false
) extends SemanticRule("OrganizeImports") {
  import OrganizeImports._
  import ImportMatcher._

  private lazy val scala3TargetDialect =
    new OrganizeImports(config, dialects.Scala3)

  private val matchers = buildImportMatchers(config)

  private val wildcardGroupIndex: Int = matchers indexOf *

  def this() = this(OrganizeImportsConfig())

  override def description: String = "Organize import statements"

  override def isLinter: Boolean = true

  override def isRewrite: Boolean = true

  override def withConfiguration(config: Configuration): Configured[Rule] =
    config.conf
      .getOrElse("OrganizeImports")(OrganizeImportsConfig())
      .andThen(patchPreset(_, config.conf))
      .andThen(checkScalacOptions(_, config.scalacOptions, config.scalaVersion))

  override def fix(implicit doc: SemanticDocument): Patch = {
    (doc.input, scala3DialectForScala3Paths) match {
      case (File(path, _), true)
          if path.toFile.getAbsolutePath.contains("scala-3/") =>
        scala3TargetDialect.fixWithImplicitDialect(doc)
      case (VirtualFile(path, _), true) if path.contains("scala-3/") =>
        scala3TargetDialect.fixWithImplicitDialect(doc)
      case _ =>
        fixWithImplicitDialect(doc)
    }
  }

  private def fixWithImplicitDialect(implicit doc: SemanticDocument): Patch = {

    val diagnostics: ArrayBuffer[Diagnostic] = ArrayBuffer.empty[Diagnostic]

    val unusedImporteePositions = new UnusedImporteePositions

    val (globalImports, localImports) = collectImports(doc.tree)

    val globalImportsPatch =
      if (globalImports.isEmpty) Patch.empty
      else
        organizeGlobalImports(unusedImporteePositions, diagnostics)(
          globalImports
        )

    val localImportsPatch =
      if (!config.removeUnused || localImports.isEmpty) Patch.empty
      else removeUnusedImports(unusedImporteePositions)(localImports)

    diagnostics.map(Patch.lint).asPatch + globalImportsPatch + localImportsPatch
  }

  private def organizeGlobalImports(
      unusedImporteePositions: UnusedImporteePositions,
      diagnostics: ArrayBuffer[Diagnostic]
  )(
      imports: Seq[Import]
  )(implicit doc: SemanticDocument): Patch = {
    val noUnused = imports flatMap (_.importers) flatMap (
      removeUnusedImporters(unusedImporteePositions)(_).toSeq
    )

    val (implicits, noImplicits) =
      if (!config.groupExplicitlyImportedImplicitsSeparately) (Nil, noUnused)
      else partitionImplicits(noUnused)

    val (fullyQualifiedImporters, relativeImporters) =
      noImplicits partition isFullyQualified(diagnostics)

    // Organizes all the fully-qualified global importers.
    val fullyQualifiedGroups: Seq[ImportGroup] = {
      val expanded =
        if (config.expandRelative) relativeImporters map expandRelative else Nil
      groupImporters(diagnostics)(fullyQualifiedImporters ++ expanded)
    }

    // Moves relative imports (when `config.expandRelative` is false) and
    // explicitly imported implicit names into a separate order preserving
    // group. This group will be appended after all the other groups.
    //
    // See https://github.com/liancheng/scalafix-organize-imports/issues/30
    // for why implicits require special handling.
    val orderPreservingGroup = {
      val relatives = if (config.expandRelative) Nil else relativeImporters
      Option(
        relatives ++ implicits sortBy (_.importees.head.pos.start)
      ) filter (_.nonEmpty)
    }

    // Builds a patch that inserts the organized imports.
    val insertionPatch = insertOrganizedImports(
      imports.head.tokens.head,
      fullyQualifiedGroups ++
        orderPreservingGroup.map(ImportGroup(matchers.length, _))
    )

    // Builds a patch that removes all the tokens forming the original imports.
    val removalPatch = Patch.removeTokens(
      doc.tree.tokens.slice(
        imports.head.tokens.start,
        imports.last.tokens.end
      )
    )

    (insertionPatch + removalPatch).atomic
  }

  private def removeUnusedImports(
      unusedImporteePositions: UnusedImporteePositions
  )(
      imports: Seq[Import]
  ): Patch =
    Patch.fromIterable {
      imports flatMap (_.importers) flatMap { case Importer(_, importees) =>
        val hasUsedWildcard = importees exists { i =>
          i.is[Importee.Wildcard] && !unusedImporteePositions(i)
        }

        importees collect {
          case i @ Importee.Rename(_, to)
              if unusedImporteePositions(i) && hasUsedWildcard =>
            // Unimport the identifier instead of removing the importee since
            // unused renamed may still impact compilation by shadowing an
            // identifier.
            //
            // See https://github.com/scalacenter/scalafix/issues/614
            Patch.replaceTree(to, "_").atomic

          case i if unusedImporteePositions(i) =>
            Patch.removeImportee(i).atomic
        }
      }
    }

  private def removeUnusedImporters(
      unusedImporteePositions: UnusedImporteePositions
  )(
      importer: Importer
  ): Option[Importer] =
    if (!config.removeUnused) Some(importer)
    else {
      val hasUsedWildcard = importer.importees exists { i =>
        i.is[Importee.Wildcard] && !unusedImporteePositions(i)
      }

      var rewritten = false

      val noUnused = importer.importees.flatMap {
        case i @ Importee.Rename(from, _)
            if unusedImporteePositions(i) && hasUsedWildcard =>
          // Unimport the identifier instead of removing the importee since
          // unused renamed may still impact compilation by shadowing an
          // identifier.
          //
          // See https://github.com/scalacenter/scalafix/issues/614
          rewritten = true
          Importee.Unimport(from) :: Nil

        case i if unusedImporteePositions(i) =>
          rewritten = true
          Nil

        case i =>
          i :: Nil
      }

      if (!rewritten) Some(importer)
      else if (noUnused.isEmpty) None
      else Some(importer.copy(importees = noUnused))
    }

  private def partitionImplicits(
      importers: Seq[Importer]
  )(implicit doc: SemanticDocument): (Seq[Importer], Seq[Importer]) = {
    val (implicits, implicitPositions) = importers.flatMap {
      case importer @ Importer(_, importees) =>
        importees collect {
          case i: Importee.Name if i.symbol.infoNoThrow exists (_.isImplicit) =>
            importer.copy(importees = i :: Nil) -> i.pos
        }
    }.unzip

    val noImplicits = importers.flatMap {
      _.filterImportees { importee =>
        !implicitPositions.contains(importee.pos)
      }.toSeq
    }

    (implicits, noImplicits)
  }

  private def isFullyQualified(
      diagnostics: ArrayBuffer[Diagnostic]
  )(
      importer: Importer
  )(implicit doc: SemanticDocument): Boolean = {
    val topQualifier = topQualifierOf(importer.ref)
    val topQualifierSymbol = topQualifier.symbol
    val owner = topQualifierSymbol.owner

    (
      // The owner of the top qualifier is `_root_`, e.g.: `import scala.util`
      owner.isRootPackage ||

      // The top qualifier is a top-level class/trait/object defined under no packages. In this
      // case, Scalameta defines the owner to be the empty package.
      owner.isEmptyPackage ||

      // The top qualifier itself is `_root_`, e.g.: `import _root_.scala.util`
      topQualifier.value == "_root_" ||

      // https://github.com/liancheng/scalafix-organize-imports/issues/64:
      // Sometimes, the symbol of the top qualifier can be missing due to
      // unknown reasons. In this case, we issue a warning and continue
      // processing assuming that the top qualifier is fully-qualified.
      topQualifierSymbol.isNone && {
        diagnostics += ImporterSymbolNotFound(topQualifier)
        true
      }
    )
  }

  private def expandRelative(
      importer: Importer
  )(implicit doc: SemanticDocument): Importer = {

    /**
     * Converts a `Symbol` into a fully-qualified `Term.Ref`.
     *
     * NOTE: The returned `Term.Ref` does NOT contain symbol information since
     * it's not parsed from the source file.
     */
    def toFullyQualifiedRef(symbol: Symbol): Term.Ref = {
      val owner = symbol.owner

      symbol match {
        // When importing names defined within package objects, skip the `package` part for brevity.
        // For instance, with the following definition:
        //
        //   package object foo { val x: Int = ??? }
        //
        // when importing `foo.x`, we prefer "import foo.x" instead of "import foo.`package`.x",
        // which is also valid, but unnecessarily lengthy.
        //
        // See https://github.com/liancheng/scalafix-organize-imports/issues/55.
        case _ if symbol.infoNoThrow exists (_.isPackageObject) =>
          toFullyQualifiedRef(owner)

        // See the comment marked with "issues/64" for the case of `symbol.isNone`
        case _
            if symbol.isNone || owner.isRootPackage || owner.isEmptyPackage =>
          Term.Name(symbol.displayName)

        case _ =>
          Term.Select(toFullyQualifiedRef(owner), Term.Name(symbol.displayName))
      }
    }

    val fullyQualifiedTopQualifier =
      toFullyQualifiedRef(topQualifierOf(importer.ref).symbol)

    importer.copy(
      ref = replaceTopQualifier(importer.ref, fullyQualifiedTopQualifier)
    )
  }

  private def groupImporters(
      diagnostics: ArrayBuffer[Diagnostic]
  )(
      importers: Seq[Importer]
  ): Seq[ImportGroup] =
    importers
      .groupBy(matchImportGroup) // Groups imports by importer prefix.
      .mapValues(
        deduplicateImportees _ andThen organizeImportGroup(diagnostics)
      )
      .map { case (index, imports) => ImportGroup(index, imports) }
      .toSeq
      .sortBy(_.index)

  private def deduplicateImportees(importers: Seq[Importer]): Seq[Importer] = {
    // Scalameta `Tree` nodes do not provide structural equality comparisons, here we pretty-print
    // them and compare the string results.
    val seenImportees = mutable.Set.empty[(String, String)]

    importers flatMap { importer =>
      importer filterImportees { importee =>
        importee.is[Importee.Wildcard] || importee.is[Importee.GivenAll] ||
        seenImportees.add(importee.syntax -> importer.ref.syntax)
      }
    }
  }

  private def organizeImportGroup(
      diagnostics: ArrayBuffer[Diagnostic]
  )(
      importers: Seq[Importer]
  ): Seq[Importer] = {
    val importeesSorted = locally {
      config.groupedImports match {
        case GroupedImports.Merge =>
          mergeImporters(diagnostics)(importers, aggressive = false)
        case GroupedImports.AggressiveMerge =>
          mergeImporters(diagnostics)(importers, aggressive = true)
        case GroupedImports.Explode =>
          explodeImportees(importers)
        case GroupedImports.Keep =>
          importers
      }
    } map (coalesceImportees _ andThen sortImportees)

    config.importsOrder match {
      // https://github.com/liancheng/scalafix-organize-imports/issues/84: The Scalameta `Tree` node pretty-printer
      // checks whether the node originates directly from the parser. If yes, the original source
      // code text is returned, and may interfere imports sort order. The `.copy()` call below
      // erases the source position information so that the pretty-printer would actually
      // pretty-print an `Importer` into a single line.
      case ImportsOrder.Ascii =>
        importeesSorted sortBy (i => importerSyntax(i.copy()))
      case ImportsOrder.SymbolsFirst =>
        sortImportersSymbolsFirst(importeesSorted)
      case ImportsOrder.Keep =>
        importeesSorted
    }
  }

  private def mergeImporters(
      diagnostics: ArrayBuffer[Diagnostic]
  )(
      importers: Seq[Importer],
      aggressive: Boolean
  ): Seq[Importer] =
    importers.groupBy(_.ref.syntax).values.toSeq.flatMap {
      case importer :: Nil =>
        // If this group has only one importer, returns it as is to preserve the original source
        // level formatting.
        importer :: Nil

      case group @ Importer(ref, _) :: _ =>
        val importeeLists = group map (_.importees)
        val hasWildcard = group exists (_.hasWildcard)
        val hasGivenAll = group exists (_.hasGivenAll)

        // Collects the last set of unimports with a wildcard, if any. It cancels all previous
        // unimports. E.g.:
        //
        //   import p.{A => _}
        //   import p.{B => _, _}
        //   import p.{C => _, _}
        //
        // Only `C` is unimported. `A` and `B` are still available.
        //
        // TODO: Shall we issue a warning here as using order-sensitive imports is a bad practice?
        val lastUnimportsWithWildcard = importeeLists.reverse collectFirst {
          case Importees(_, _, unimports @ _ :: _, _, _, Some(_)) => unimports
        }

        val lastUnimportsWithGivenAll = importeeLists.reverse collectFirst {
          case Importees(_, _, unimports @ _ :: _, _, Some(_), _) => unimports
        }

        // Collects all unimports without an accompanying wildcard.
        val unimports = importeeLists.collect {
          case Importees(_, _, unimports, _, None, None) =>
            unimports
        }.flatten

        val (givens, nonGivens) =
          group.flatMap(_.importees).toList.partition(_.is[Importee.Given])

        // Here we assume that a name is renamed at most once within a single source file, which is
        // true in most cases.
        //
        // Note that the IntelliJ IDEA Scala import optimizer does not handle this case properly
        // either. If a name is renamed more than once, it only keeps one of the renames in the
        // result and may break compilation (unless other renames are not actually referenced).
        val renames = nonGivens
          .collect { case rename: Importee.Rename => rename }
          .groupBy(_.name.value)
          .map {
            case (_, rename :: Nil) => rename
            case (_, renames @ (head @ Importee.Rename(from, _)) :: _) =>
              diagnostics += TooManyAliases(from, renames)
              head
          }
          .toList

        // Collects distinct explicitly imported names, and filters out those that are also renamed.
        // If an explicitly imported name is also renamed, both the original name and the new name
        // are available. This implies that both of them must be preserved in the merged result, but
        // in two separate import statements (Scala only allows a name to appear in an import at
        // most once). E.g.:
        //
        //   import p.A
        //   import p.{A => A1}
        //   import p.B
        //   import p.{B => B1}
        //
        // The above snippet should be rewritten into:
        //
        //   import p.{A, B}
        //   import p.{A => A1, B => B1}
        val (renamedImportedNames, importedNames) = {
          val renamedNames =
            renames.map { case Importee.Rename(Name(from), _) =>
              from
            }.toSet

          nonGivens
            .filter(_.is[Importee.Name])
            .groupBy { case Importee.Name(Name(name)) => name }
            .map { case (_, importees) => importees.head }
            .toList
            .partition { case Importee.Name(Name(name)) =>
              renamedNames contains name
            }
        }

        val mergedNonGivens = (hasWildcard, lastUnimportsWithWildcard) match {
          case (true, _) =>
            // A few things to note in this case:
            //
            // 1. Unimports are discarded because they are canceled by the wildcard. E.g.:
            //
            //      import scala.collection.mutable.{Set => _, _}
            //      import scala.collection.mutable._
            //
            //    The above two imports should be merged into:
            //
            //      import scala.collection.mutable._
            //
            // 2. Explicitly imported names can NOT be discarded even though they seem to be covered
            //    by the wildcard, unless groupedImports is set to AggressiveMerge. This is because
            //    explicitly imported names have higher precedence than names imported via a
            //    wildcard. Discarding them may introduce ambiguity in some cases. E.g.:
            //
            //      import scala.collection.immutable._
            //      import scala.collection.mutable._
            //      import scala.collection.mutable.Set
            //
            //      object Main { val s: Set[Int] = ??? }
            //
            //    The type of `Main.s` above is unambiguous because `mutable.Set` is explicitly
            //    imported, and has higher precedence than `immutable.Set`, which is made available
            //    via a wildcard. In this case, the imports should be merged into:
            //
            //      import scala.collection.immutable._
            //      import scala.collection.mutable.{Set, _}
            //
            //    rather than
            //
            //      import scala.collection.immutable._
            //      import scala.collection.mutable._
            //
            //    Otherwise, the type of `Main.s` becomes ambiguous and a compilation error is
            //    introduced.
            //
            // 3. However, the case discussed above is relatively rare in real life. A more common
            //    case is something like:
            //
            //      import scala.collection.Set
            //      import scala.collection._
            //
            //    In this case, we do want to merge them into:
            //
            //      import scala.collection._
            //
            //    rather than
            //
            //      import scala.collection.{Set, _}
            //
            //    To achieve this, users may set `groupedImports` to `AggressiveMerge`. Instead of
            //    being conservative and ensure correctness in all the cases, this option merges
            //    imports aggressively for conciseness.
            //
            // 4. Renames must be moved into a separate import statement to make sure that the
            //    original names made available by the wildcard are still preserved. E.g.:
            //
            //      import p._
            //      import p.{A => A1}
            //
            //    The above imports cannot be merged into
            //
            //      import p.{A => A1, _}
            //
            //    Otherwise, the original name `A` is no longer available.
            if (aggressive) Seq(renames, Wildcard() :: Nil)
            else Seq(renames, importedNames :+ Wildcard())

          case (false, Some(lastUnimports)) =>
            // A wildcard must be appended for unimports.
            Seq(
              renamedImportedNames,
              importedNames ++ renames ++ lastUnimports :+ Wildcard()
            )

          case (false, None) =>
            Seq(renamedImportedNames, importedNames ++ renames ++ unimports)
        }

        /* Adjust the result to add givens imports, these are
         * are the Scala 3 way of importing implicits, which are not imported
         * with the wildcard import.
         */
        val newImporteeListsWithGivens = if (hasGivenAll) {
          if (aggressive) mergedNonGivens :+ List(GivenAll())
          else mergedNonGivens :+ (givens :+ GivenAll())
        } else {
          lastUnimportsWithGivenAll match {
            case Some(unimports) =>
              mergedNonGivens :+ (givens ++ unimports :+ GivenAll())
            case None =>
              mergedNonGivens :+ givens
          }
        }

        preserveOriginalImportersFormatting(
          group,
          newImporteeListsWithGivens,
          ref
        )
    }

  private def sortImportersSymbolsFirst(
      importers: Seq[Importer]
  ): Seq[Importer] =
    importers.sortBy { importer =>
      // See the comment marked with "issues/84" for why a `.copy()` is needed.
      val syntax = importer.copy().syntax

      importer match {
        case Importer(_, Importee.Wildcard() :: Nil) =>
          val wildcardSyntax = Importee.Wildcard().syntax
          syntax.patch(
            syntax.lastIndexOfSlice(s".$wildcardSyntax"),
            ".\u0001",
            2
          )

        case _ if importer.isCurlyBraced =>
          syntax
            .replaceFirst("[{]", "\u0002")
            .patch(syntax.lastIndexOf("}"), "\u0002", 1)

        case _ => syntax
      }
    }

  private def coalesceImportees(importer: Importer): Importer = {
    val Importees(names, renames, unimports, givens, _, _) = importer.importees

    config.coalesceToWildcardImportThreshold
      .filter(importer.importees.length > _)
      // Skips if there's no `Name`s or `Given`s. `Rename`s and `Unimport`s cannot be coalesced.
      .filterNot(_ => names.isEmpty && givens.isEmpty)
      .map {
        case _ if givens.isEmpty => renames ++ unimports :+ Wildcard()
        case _ if names.isEmpty => renames ++ unimports :+ GivenAll()
        case _ => renames ++ unimports :+ GivenAll() :+ Wildcard()
      }
      .map(importees => importer.copy(importees = importees))
      .getOrElse(importer)
  }

  private def sortImportees(importer: Importer): Importer = {
    import ImportSelectorsOrder._

    // The Scala language spec allows an import expression to have at most one final wildcard, which
    // can only appears in the last position.
    val (wildcards, others) =
      importer.importees partition (i =>
        i.is[Importee.Wildcard] || i.is[Importee.GivenAll]
      )

    val orderedImportees = config.importSelectorsOrder match {
      case Ascii =>
        Seq(others, wildcards) map (_.sortBy(_.syntax)) reduce (_ ++ _)
      case SymbolsFirst =>
        Seq(others, wildcards) map sortImporteesSymbolsFirst reduce (_ ++ _)
      case Keep =>
        importer.importees
    }

    // Checks whether importees of the input importer are already sorted. If yes, we should return
    // the original importer to preserve the original source level formatting.
    val alreadySorted =
      config.importSelectorsOrder == Keep ||
        (importer.importees corresponds orderedImportees) { (lhs, rhs) =>
          lhs.syntax == rhs.syntax
        }

    if (alreadySorted) importer else importer.copy(importees = orderedImportees)
  }

  /**
   * Returns the index of the group to which the given importer belongs. Each
   * group is represented by an `ImportMatcher`. If multiple `ImporterMatcher` s
   * match the given import, the one matches the longest prefix wins.
   */
  private def matchImportGroup(importer: Importer): Int = {
    val matchedGroups = matchers
      .map(_ matches importer)
      .zipWithIndex
      .filter { case (length, _) => length > 0 }

    if (matchedGroups.isEmpty) wildcardGroupIndex
    else {
      val (_, index) = matchedGroups.maxBy { case (length, _) => length }
      index
    }
  }

  private def insertOrganizedImports(
      token: Token,
      importGroups: Seq[ImportGroup]
  ): Patch = {
    val prettyPrintedGroups = importGroups.map {
      case ImportGroup(index, imports) =>
        index -> prettyPrintImportGroup(imports)
    }

    val blankLines = {
      // Indices of all blank lines configured in `OrganizeImports.groups`, either automatically or
      // manually.
      val blankLineIndices = matchers.zipWithIndex.collect {
        case (`---`, index) => index
      }.toSet

      // Checks each pair of adjacent import groups. Inserts a blank line between them if necessary.
      importGroups map (_.index) sliding 2 filter (_.length == 2) flatMap {
        case Seq(lhs, rhs) =>
          val hasBlankLine = blankLineIndices exists (i => lhs < i && i < rhs)
          if (hasBlankLine) Some((lhs + 1) -> "") else None
      }
    }

    val withBlankLines = (prettyPrintedGroups ++ blankLines)
      .sortBy { case (index, _) => index }
      .map { case (_, lines) => lines }
      .mkString("\n")

    // Global imports within curly-braced packages must be indented accordingly, e.g.:
    //
    //   package foo {
    //     package bar {
    //       import baz
    //       import qux
    //     }
    //   }
    val indented = withBlankLines.linesIterator.zipWithIndex.map {
      // The first line will be inserted at an already indented position.
      case (line, 0) => line
      case (line, _) if line.isEmpty => line
      case (line, _) => " " * token.pos.startColumn + line
    }

    Patch.addLeft(token, indented mkString "\n")
  }

  private def prettyPrintImportGroup(group: Seq[Importer]): String =
    group
      .map(i => "import " + importerSyntax(i))
      .mkString("\n")

  private def importerSyntax(importer: Importer): String =
    importer.pos match {
      case pos: Position.Range =>
        // Position found, implies that `importer` was directly parsed from the source code. Rewrite
        // importees to ensure they follow the target dialect. For importers with a single importee,
        // strip enclosing braces if they exist (or add/preserve them for Rename & Unimport on Scala 2).

        val syntax = new StringBuilder(pos.text)

        def patchSyntax(
            t: Tree,
            newSyntax: String
        ) = {
          val start = t.pos.start - pos.start
          syntax.replace(start, t.pos.end - pos.start, newSyntax)

          if (importer.importees.length == 1) {
            val end = t.pos.start - pos.start + newSyntax.length
            (
              syntax.take(start).lastIndexOf('{'),
              syntax.indexOf('}', end),
              importer.isCurlyBraced
            ) match {
              case (-1, -1, true) =>
                // braces required but not detected
                syntax.append('}')
                syntax.insert(start, '{')
              case (opening, closing, false)
                  if opening != -1 && closing != -1 =>
                // braces detected but not required
                syntax.delete(end, closing + 1)
                syntax.delete(opening, start)
              case _ =>
            }
          }
        }

        // traverse & patch backwards to avoid shifting indices
        importer.importees.reverse.foreach {
          case i @ Importee.Rename(_, _) =>
            patchSyntax(i, i.copy().syntax)
          case i @ Importee.Unimport(_) =>
            patchSyntax(i, i.copy().syntax)
          case i @ Importee.Wildcard() =>
            patchSyntax(i, i.copy().syntax)
          case i =>
            patchSyntax(i, i.syntax)
        }

        syntax.toString

      case Position.None =>
        // Position not found, implies that `importer` is derived from certain existing import
        // statement(s). Pretty-prints it.
        val syntax = importer.syntax

        // HACK: The Scalafix pretty-printer decides to add spaces after open and
        // before close braces in imports with multiple importees, i.e., `import a.{
        // b, c }` instead of `import a.{b, c}`. On the other hand, renames are
        // pretty-printed without the extra spaces, e.g., `import a.{b => c}`. This
        // behavior is not customizable and makes ordering imports by ASCII order
        // complicated.
        //
        // This function removes the unwanted spaces as a workaround. In cases where
        // users do want the inserted spaces, Scalafmt should be used after running
        // the `OrganizeImports` rule.

        // NOTE: We need to check whether the input importer is curly braced first and then replace
        // the first "{ " and the last " }" if any. Naive string replacement is insufficient, e.g.,
        // a quoted-identifier like "`{ d }`" may cause broken output.
        (importer.isCurlyBraced, syntax lastIndexOfSlice " }") match {
          case (_, -1) =>
            syntax
          case (true, index) =>
            syntax.patch(index, "}", 2).replaceFirst("\\{ ", "{")
          case _ =>
            syntax
        }
    }

  implicit private class ImporterExtension(importer: Importer) {

    /**
     * Checks whether the `Importer` should be curly-braced when pretty-printed.
     */
    def isCurlyBraced: Boolean = {
      val importees @ Importees(_, renames, unimports, _, _, _) =
        importer.importees

      importees.length > 1 ||
      ((renames.length == 1 || unimports.length == 1) && !targetDialect.allowAsForImportRename)
    }

    /**
     * Returns an `Importer` with all the `Importee` s that are selected from
     * the input `Importer` and satisfy a predicate. If all the `Importee` s are
     * selected, the input `Importer` instance is returned to preserve the
     * original source level formatting. If none of the `Importee` s are
     * selected, returns a `None`.
     */
    def filterImportees(f: Importee => Boolean): Option[Importer] = {
      val filtered = importer.importees filter f
      if (filtered.length == importer.importees.length) Some(importer)
      else if (filtered.isEmpty) None
      else Some(importer.copy(importees = filtered))
    }

    /** Returns true if the `Importer` contains a standalone wildcard. */
    def hasWildcard: Boolean = {
      val Importees(_, _, unimports, _, _, wildcard) = importer.importees
      unimports.isEmpty && wildcard.nonEmpty
    }

    /** Returns true if the `Importer` contains a standalone given wildcard. */
    def hasGivenAll: Boolean = {
      val Importees(_, _, unimports, _, givenAll, _) = importer.importees
      unimports.isEmpty && givenAll.nonEmpty
    }
  }
}

object OrganizeImports {
  private case class ImportGroup(index: Int, imports: Seq[Importer])

  private def patchPreset(
      ruleConf: OrganizeImportsConfig,
      conf: Conf
  ): Configured[OrganizeImportsConfig] = {
    val preset = OrganizeImportsConfig.presets(ruleConf.preset)
    val presetConf = ConfEncoder[OrganizeImportsConfig].write(preset)
    val userConf = ConfGet
      .getOrOK(conf, "OrganizeImports" :: Nil, Configured.ok, Conf.Obj.empty)
      .getOrElse(Conf.Obj.empty)
    val mergedConf = ConfOps.merge(presetConf, userConf)
    ConfDecoder[OrganizeImportsConfig].read(mergedConf)
  }

  private def checkScalacOptions(
      conf: OrganizeImportsConfig,
      scalacOptions: List[String],
      scalaVersion: String
  ): Configured[Rule] = {
    val hasCompilerSupport =
      Seq("3.0", "3.1", "3.2", "3.3.0", "3.3.1", "3.3.2", "3.3.3")
        .forall(v => !scalaVersion.startsWith(v))

    val hasWarnUnused = hasCompilerSupport && {
      val warnUnusedPrefix = Set("-Wunused", "-Ywarn-unused")
      val warnUnusedString = Set("-Xlint", "-Xlint:unused")
      scalacOptions exists { option =>
        (warnUnusedPrefix exists option.startsWith) || (warnUnusedString contains option)
      }
    }

    val (targetDialect, scala3DialectForScala3Paths) =
      conf.targetDialect match {
        case TargetDialect.Auto =>
          val dialect = ScalaVersion
            .from(scalaVersion)
            .map { scalaVersion =>
              def extractSuffixForScalacOption(prefix: String) = {
                scalacOptions
                  .filter(_.startsWith(prefix))
                  .lastOption
                  .map(_.stripPrefix(prefix))
              }

              // We only lookup the Scala 2 option (Scala 3 is `-source`), as the latest Scala 3
              // dialect is used no matter what the actual minor version is anyway, and as of now,
              // the pretty printer is just more permissive with the latest dialect.
              val sourceScalaVersion =
                extractSuffixForScalacOption("-Xsource:")
                  .map(_.stripSuffix("-cross"))
                  .flatMap(ScalaVersion.from(_).toOption)

              scalaVersion.dialect(sourceScalaVersion)
            }
            .getOrElse(Dialect.current)
          (dialect, false)
        case TargetDialect.Scala2 =>
          (dialects.Scala212, false)
        case TargetDialect.Scala3 =>
          (dialects.Scala3, false)
        case TargetDialect.StandardLayout =>
          (dialects.Scala212, true)
      }

    if (!conf.removeUnused || hasWarnUnused)
      Configured.ok(
        new OrganizeImports(conf, targetDialect, scala3DialectForScala3Paths)
      )
    else if (hasCompilerSupport)
      Configured.error(
        "A Scala compiler option is required to use OrganizeImports with"
          + " \"OrganizeImports.removeUnused\" set to true. To fix this"
          + " problem, update your build to add `-Ywarn-unused-import` (2.12)"
          + " or `-Wunused:imports` (2.13 and 3.3.4+)."
      )
    else
      Configured.error(
        "\"OrganizeImports.removeUnused\"" + s"is not supported on $scalaVersion as the compiler is"
          + " not providing enough information. Please upgrade the Scala compiler to 3.3.4 or greater."
          + " Otherwise, run the rule with \"OrganizeImports.removeUnused\" set to false"
          + " to organize imports while keeping potentially unused imports."
      )
  }

  private def buildImportMatchers(
      config: OrganizeImportsConfig
  ): Seq[ImportMatcher] = {
    val withWildcard = {
      val parsed = config.groups map parse
      // The wildcard group should always exist. Appends one at the end if omitted.
      if (parsed contains *) parsed else parsed :+ *
    }

    // Inserts a blank line marker between adjacent import groups when `blankLines` is `Auto`.
    config.blankLines match {
      case BlankLines.Manual => withWildcard
      case BlankLines.Auto => withWildcard.flatMap(_ :: --- :: Nil)
    }
  }

  private def positionOf(importee: Importee): Position =
    importee match {
      case Importee.Rename(from, _) => from.pos
      case _ => importee.pos
    }

  @tailrec private def collectImports(
      tree: Tree
  ): (Seq[Import], Seq[Import]) = {
    def extractImports(stats: Seq[Stat]): (Seq[Import], Seq[Import]) = {
      val (importStats, otherStats) = stats.span(_.is[Import])
      val globalImports = importStats.map { case i: Import => i }
      val localImports = otherStats.flatMap(_.collect { case i: Import => i })
      (globalImports, localImports)
    }

    tree match {
      case Source(Seq(p: Pkg)) => collectImports(p)
      case Pkg(_, Seq(p: Pkg)) => collectImports(p)
      case Source(stats) => extractImports(stats)
      case Pkg(_, stats) => extractImports(stats)
      case _ => (Nil, Nil)
    }
  }

  @tailrec private def topQualifierOf(term: Term): Term.Name =
    term match {
      case Term.Select(qualifier, _) => topQualifierOf(qualifier)
      case name: Term.Name => name
    }

  /**
   * Replaces the top-qualifier of the input `term` with a new term
   * `newTopQualifier`.
   */
  private def replaceTopQualifier(
      term: Term,
      newTopQualifier: Term.Ref
  ): Term.Ref =
    term match {
      case _: Term.Name =>
        newTopQualifier
      case Term.Select(qualifier, name) =>
        Term.Select(replaceTopQualifier(qualifier, newTopQualifier), name)
    }

  private def sortImporteesSymbolsFirst(
      importees: List[Importee]
  ): List[Importee] = {
    val symbols = ArrayBuffer.empty[Importee]
    val lowerCases = ArrayBuffer.empty[Importee]
    val upperCases = ArrayBuffer.empty[Importee]

    importees.foreach {
      case i if i.syntax.head.isLower => lowerCases += i
      case i if i.syntax.head.isUpper => upperCases += i
      case i => symbols += i
    }

    List(symbols, lowerCases, upperCases) flatMap (_ sortBy (_.syntax))
  }

  private def explodeImportees(importers: Seq[Importer]): Seq[Importer] =
    importers flatMap {
      case importer @ Importer(_, _ :: Nil) =>
        // If the importer has exactly one importee, returns it as is to preserve the original
        // source level formatting.
        importer :: Nil

      case importer @ Importer(
            ref,
            Importees(names, renames, unimports, givens, givenAll, wildcard)
          ) if givenAll.isDefined || wildcard.isDefined =>
        // When a wildcard exists, all renames, unimports, and the wildcard must appear in the same
        // importer, e.g.:
        //
        //   import p.{A => _, B => _, C => D, E, _}
        //
        // should be rewritten into
        //
        //   import p.{A => _, B => _, C => D, _}
        //   import p.E
        val importeesList =
          (names ++ givens).map(
            _ :: Nil
          ) :+ (renames ++ unimports ++ wildcard ++ givenAll)
        preserveOriginalImportersFormatting(Seq(importer), importeesList, ref)

      case importer =>
        importer.importees map (i => importer.copy(importees = i :: Nil))
    }

  /**
   * https://github.com/liancheng/scalafix-organize-imports/issues/127: After
   * merging or exploding imports, checks whether there are any input importers
   * left untouched. For those importers, returns the original importer instance
   * to preserve the original source level formatting.
   */
  private def preserveOriginalImportersFormatting(
      importers: Seq[Importer],
      newImporteeLists: Seq[List[Importee]],
      newImporterRef: Term.Ref
  ) = {
    val importerSyntaxMap = importers.map { i => i.copy().syntax -> i }.toMap

    newImporteeLists filter (_.nonEmpty) map { importees =>
      val newImporter = Importer(newImporterRef, importees)
      importerSyntaxMap.getOrElse(newImporter.syntax, newImporter)
    }
  }

  /**
   * Categorizes a list of `Importee` s into the following four groups:
   *
   *   - Names, e.g., `Seq`, `Option`, etc.
   *   - Renames, e.g., `{Long => JLong}`, `Duration as D`, etc.
   *   - Unimports, e.g., `{Foo => _}` or `Foo as _`.
   *   - Givens, e.g., `given Foo`.
   *   - GivenAll, i.e., `given`.
   *   - Wildcard, i.e., `_` or `*`.
   */
  object Importees {
    def unapply(importees: Seq[Importee]): Option[
      (
          List[Importee.Name],
          List[Importee.Rename],
          List[Importee.Unimport],
          List[Importee.Given],
          Option[Importee.GivenAll],
          Option[Importee.Wildcard]
      )
    ] = {
      val names = ArrayBuffer.empty[Importee.Name]
      val renames = ArrayBuffer.empty[Importee.Rename]
      val givens = ArrayBuffer.empty[Importee.Given]
      val unimports = ArrayBuffer.empty[Importee.Unimport]
      var maybeWildcard: Option[Importee.Wildcard] = None
      var maybeGivenAll: Option[Importee.GivenAll] = None

      importees foreach {
        case i: Importee.Wildcard => maybeWildcard = Some(i)
        case i: Importee.Unimport => unimports += i
        case i: Importee.Rename => renames += i
        case i: Importee.Name => names += i
        case i: Importee.Given => givens += i
        case i: Importee.GivenAll => maybeGivenAll = Some(i)
      }

      Option(
        (
          names.toList,
          renames.toList,
          unimports.toList,
          givens.toList,
          maybeGivenAll,
          maybeWildcard
        )
      )
    }
  }

  class UnusedImporteePositions(implicit doc: SemanticDocument) {
    private val positions: Seq[Position] =
      doc.diagnostics.toSeq.collect {
        // Scala2 says "Unused import" while Scala3 says "unused import"
        case d if d.message.toLowerCase == "unused import" => d.position
      }

    /** Returns true if the importee was marked as unused by the compiler */
    def apply(importee: Importee): Boolean = {
      // positionOf returns the position of `bar` for `import foo.{bar => baz}`
      // this position matches with the diagnostics from Scala2, but Scala3
      // diagnostics has a position for `bar => baz`, which doesn't match
      // with the return value of `positionOf`.
      // We could adjust the behavior of `positionOf` based on Scala version,
      // but this implementation just checking the unusedImporteePosition
      // includes the importee pos, for simplicity.
      val pos = positionOf(importee)
      positions.exists { unused =>
        unused.start <= pos.start && pos.end <= unused.end
      }
    }
  }

  implicit private class SymbolExtension(symbol: Symbol) {

    /**
     * HACK: In certain cases, `Symbol#info` may throw `MissingSymbolException`
     * due to some unknown reason. This implicit class adds a safe version of
     * `Symbol#info` to return `None` instead of throw an exception when this
     * happens.
     *
     * See [[https://github.com/scalacenter/scalafix/issues/1123 issue #1123]].
     */
    def infoNoThrow(implicit doc: SemanticDocument): Option[SymbolInformation] =
      Try(symbol.info).toOption.flatten
  }
}
