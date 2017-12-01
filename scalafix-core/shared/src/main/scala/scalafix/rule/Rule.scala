package scalafix
package rule

import scala.meta._
import scalafix.internal.config.MetaconfigPendingUpstream
import scalafix.internal.config.ScalafixMetaconfigReaders
import scalafix.internal.config.ScalafixConfig
import scalafix.syntax._
import metaconfig.Conf
import metaconfig.ConfDecoder
import metaconfig.Configured

/** A Scalafix Rule.
  *
  * To provide automatic fixes for this rule, override the `fix` method. Example:
  * {{{
  *   object ReverseNames extends Rule("ReverseNames") {
  *     override def fix(ctx: RuleCtx) =
  *       ctx.tree.collect {
  *         case name @ Name(value) => ctx.replaceTree(name, value.reverse)
  *       }.asPatch
  *   }
  * }}}
  *
  * To report violations of this rule (without automatic fix), override
  * the `check` method. Example:
  * {{{
  *   // example syntactic linter
  *   object NoNulls extends Rule("NoNulls") {
  *     val error = LintCategory.error("Nulls are not allowed.")
  *     override def check(ctx: RuleCtx): List[LintMessage] = ctx.tree.collect {
  *       case nil @ q"null" => error.at(nil.pos)
  *     }
  *   }
  * }}}
  *
  * @param ruleName
  *   Name of this rule that users call via .scalafix.conf
  *   or in the sbt shell. By convention, a name should be
  *   PascalCase matching the class name of the rule.
  *
  *   Example good name: NoVars, ExplicitUnit.
  *   Example bad name: no-vars, noVars, FixVars.
  */
abstract class Rule(ruleName: RuleName) { self =>

  /** Returns linter messages to report violations of this rule. */
  def check(ctx: RuleCtx): Seq[LintMessage] = Nil

  /** Returns a patch to fix violations of this rule. */
  def fix(ctx: RuleCtx): Patch = Patch.empty

  /** Initialize this rule with the given user configuration.
    *
    * This method is called once by scalafix before rule is called.
    * Use this method to either read custom configuration or to build
    * expensive indices.
    *
    * @param config The .scalafix.conf configuration.
    * @return the initialized rule or an error. If no initialization is needed,
    *         return Configured.Ok(this).
    */
  def init(config: Conf): Configured[Rule] =
    Configured.Ok(this)

  /** Combine this rule with another rule. */
  final def merge(other: Rule): Rule = Rule.merge(this, other)
  @deprecated("Renamed to merge.", "0.5.0")
  final def andThen(other: Rule): Rule = merge(other)

  /** Returns string output of applying this single patch. */
  final def apply(ctx: RuleCtx): String =
    apply(ctx, fixWithName(ctx))
  final def apply(
      input: Input,
      config: ScalafixConfig = ScalafixConfig.default): String = {
    val ctx = RuleCtx(config.dialect(input).parse[Source].get, config)
    apply(ctx, fixWithName(ctx))
  }
  final def apply(input: String): String = apply(Input.String(input))
  final def apply(ctx: RuleCtx, patch: Patch): String =
    apply(ctx, Map(name -> patch))
  final def apply(ctx: RuleCtx, patches: Map[RuleName, Patch]): String = {
    val result = Patch(patches.values.asPatch, ctx, semanticOption)
    // This overload of apply if purely for convenience
    // Use fixWithName to iterate over LintMessage
    // without printing to the console
    Patch.lintMessages(patches, ctx).foreach(ctx.printLintMessage)
    result
  }
  final def applyAndLint(ctx: RuleCtx): (String, List[LintMessage]) = {
    val patches = fixWithName(ctx)
    val fixed = Patch(patches.values.asPatch, ctx, semanticOption)
    val messages = Patch.lintMessages(patches, ctx)
    (fixed, messages)
  }

  /** Returns unified diff from applying this patch */
  final def diff(ctx: RuleCtx): String =
    diff(ctx, fix(ctx))
  final protected def diff(ctx: RuleCtx, patch: Patch): String = {
    val original = ctx.tree.input
    Patch.unifiedDiff(
      original,
      Input.VirtualFile(original.label, apply(ctx, patch)))
  }

  private[scalafix] final def allNames: List[String] =
    name.identifiers.map(_.value)
  protected[scalafix] def fixWithName(ctx: RuleCtx): Map[RuleName, Patch] =
    Map(name -> (fix(ctx) ++ check(ctx).map(ctx.lint)))

  final override def toString: String = name.toString
  final def name: RuleName = ruleName
  // NOTE. This is kind of hacky and hopefully we can find a better workaround.
  // The challenge is the following:
  // - a.andThen(b) needs to work for mixing semantic + syntactic rules.
  // - applied/appliedDiff should work without passing in SemanticdbIndex explicitly
  protected[scalafix] def semanticOption: Option[SemanticdbIndex] = None
}

abstract class SemanticRule(index: SemanticdbIndex, name: RuleName)
    extends Rule(name) {
  implicit val ImplicitSemanticdbIndex: SemanticdbIndex = index
  @deprecated("Renamed to index.", "0.5.0")
  protected def mirror: SemanticdbIndex = index
  @deprecated("Renamed to index.", "0.5.0")
  protected def sctx: SemanticdbIndex = index
  @deprecated("Renamed to index.", "0.5.0")
  protected def semanticCtx: SemanticdbIndex = index
  override def semanticOption: Option[SemanticdbIndex] = Some(index)
}

object Rule {
  private[scalafix] class CompositeRule(val rules: List[Rule])
      extends Rule(rules.foldLeft(RuleName.empty)(_ + _.name)) {
    override def init(config: Conf): Configured[Rule] = {
      MetaconfigPendingUpstream
        .flipSeq(rules.map(_.init(config)))
        .map(x => new CompositeRule(x.toList))
    }
    override def check(ctx: RuleCtx): Seq[LintMessage] =
      rules.flatMap(_.check(ctx))
    override def fixWithName(ctx: RuleCtx): Map[RuleName, Patch] =
      rules.foldLeft(Map.empty[RuleName, Patch])(_ ++ _.fixWithName(ctx))
    override def fix(ctx: RuleCtx): Patch =
      Patch.empty ++ rules.map(_.fix(ctx))
    override def semanticOption: Option[SemanticdbIndex] =
      rules
        .collectFirst {
          case r if r.semanticOption.isDefined => r.semanticOption
        }
        .getOrElse(None)
  }
  val syntaxRuleConfDecoder: ConfDecoder[Rule] =
    ScalafixMetaconfigReaders.ruleConfDecoderSyntactic(
      ScalafixMetaconfigReaders.baseSyntacticRuleDecoder)
  lazy val empty: Rule = new Rule(RuleName.empty) {}
  def emptyConfigured: Configured[Rule] = Configured.Ok(empty)
  def emptyFromSemanticdbIndexOpt(index: Option[SemanticdbIndex]): Rule =
    index.fold(empty)(emptySemantic)
  def combine(rules: Seq[Rule]): Rule =
    rules.foldLeft(empty)(_ merge _)
  private[scalafix] def emptySemantic(index: SemanticdbIndex): Rule =
    semantic(RuleName.empty.value)(_ => _ => Patch.empty)(index)

  /** Creates a linter. */
  def linter(ruleName: String)(f: RuleCtx => List[LintMessage]): Rule =
    new Rule(ruleName) {
      override def check(ctx: RuleCtx): List[LintMessage] = f(ctx)
    }

  /** Creates a syntactic rule. */
  def syntactic(ruleName: String)(f: RuleCtx => Patch): Rule =
    new Rule(ruleName) {
      override def fix(ctx: RuleCtx): Patch = f(ctx)
    }

  /** Creates a semantic rule. */
  def semantic(ruleName: String)(
      f: SemanticdbIndex => RuleCtx => Patch): SemanticdbIndex => Rule = {
    index =>
      new SemanticRule(index, ruleName) {
        override def fix(ctx: RuleCtx): Patch = f(index)(ctx)
      }
  }

  /** Creates a rule that always returns the same patch. */
  def constant(ruleName: String, patch: Patch, index: SemanticdbIndex): Rule =
    new SemanticRule(index, ruleName) {
      override def fix(ctx: RuleCtx): Patch = patch
    }

  /** Combine two rules into a single rule */
  def merge(a: Rule, b: Rule): Rule = (a, b) match {
    case (ac: CompositeRule, bc: CompositeRule) =>
      new CompositeRule(ac.rules ::: bc.rules)
    case (ac: CompositeRule, b) =>
      new CompositeRule(b :: ac.rules)
    case (a, bc: CompositeRule) =>
      new CompositeRule(a :: bc.rules)
    case (a, b) =>
      new CompositeRule(a :: b :: Nil)
  }
}
