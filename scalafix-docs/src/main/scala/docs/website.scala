package scalafix

import java.nio.file.Files

import scala.meta.internal.io.PathIO

import mdoc.Reporter
import metaconfig.generic.Setting
import metaconfig.generic.Settings
import scalafix.internal.v1.Rules
import scalafix.v0._
import scalatags.Text
import scalatags.Text.all._

package object website {
  import scalafix.internal.rule.SimpleDefinitions

  def url(name: String, relpath: String): Text.TypedTag[String] =
    a(href := s"/scalafix/$relpath", name)
  def ruleLink(name: String): Text.TypedTag[String] = {

    url(name, s"docs/rules/$name.html")
  }

  def allRulesTable(reporter: Reporter): String = {
    val rules = Rules
      .all()
      .filterNot(_.name.isDeprecated)
      .filterNot(_.isExperimental)
      .sortBy(_.name.value)

    val (semantic, syntactic) = rules.partition(_.isInstanceOf[v1.SemanticRule])

    def buildTable(rules: List[v1.Rule]): Text.TypedTag[String] = {
      val rows: List[Text.TypedTag[String]] = rules.map { rule =>
        val docPath = PathIO.workingDirectory
          .resolve("docs")
          .resolve("rules")
          .resolve(rule.name.value + ".md")
        if (!rule.isExperimental && !Files.isRegularFile(docPath.toNIO)) {
          reporter.warning(s"Missing $docPath")
        }
        tr(
          td(ruleLink(rule.name.value)),
          td(rule.description)
        )
      }

      val header =
        thead(
          tr(
            th("Name"),
            th("Description")
          )
        )

      table(
        header,
        tbody(rows)
      )
    }

    div(
      h2("Semantic Rules"),
      buildTable(semantic),
      h2("Syntactic Rules"),
      buildTable(syntactic)
    ).toString
  }

  // TODO(olafur) replace this hack with ConfEncoder[T] typeclass.
  def render(any: Any): String = any match {
    case s: Symbol =>
      val syntax =
        s.syntax.stripPrefix("_root_.").stripSuffix("#").stripSuffix(".")
      new StringBuilder()
        .append("\"")
        .append(syntax)
        .append("\"")
        .toString()
    case _ => any.toString
  }
  private def flat[T](
      default: T
  )(implicit settings: Settings[T], ev: T <:< Product): List[(Setting, Any)] = {
    settings.settings
      .zip(default.productIterator.toIterable)
      .filterNot { case (setting, _) => setting.isHidden }
      .flatMap {
        case (s, d: SimpleDefinitions) =>
          (s, d.kinds.mkString("['", "', '", "']")) :: Nil
        case (deepSetting, defaultSetting: Product)
            if deepSetting.underlying.nonEmpty =>
          deepSetting.flat.zip(defaultSetting.productIterator.toIterable)
        case (s, lst: Iterable[_]) =>
          val rendered = lst.map(render)
          val string =
            if (lst.size < 2) rendered.mkString("[", ", ", "]")
            else rendered.mkString("[\n  ", ",\n  ", "\n]")
          (s, string) :: Nil
        case (s, defaultValue) =>
          (s, defaultValue) :: Nil
      }

  }
  def htmlSetting(setting: Setting): Text.TypedTag[String] = {
    val tpe = setting.field.tpe
      .replace("scalafix.v0.Symbol.Global", "Symbol")
      .replace("java.util.regex.", "")
      .replace("scalafix.CustomMessage", "Message")
      .replace("scalafix.internal.config.", "")
    tr(
      td(code(setting.name)),
      td(code(tpe)),
      td(setting.description)
    )
  }

  def html(all: List[Setting]): String = {
    val fields = all.map { setting =>
      htmlSetting(setting)
    }
    table(
      thead(
        tr(
          th("Name"),
          th("Type"),
          th("Description")
        )
      ),
      tbody(fields)
    ).toString()
  }

  def config[T](name: String)(implicit settings: Settings[T]): String =
    s"\n\n### $name\n\n" +
      html(settings.settings)

  def defaults[T](ruleName: String, all: List[(Setting, Any)]): String = {
    val sb = new StringBuilder
    sb.append("\n\n### Defaults\n\n```")
    all.foreach {
      case (setting, default) =>
        sb.append("\n")
          .append(ruleName)
          .append(".")
          .append(setting.name)
          .append(" = ")
          .append(default)
    }
    sb.append("\n```\n\n")
    sb.toString()
  }

  def examples[T](ruleName: String)(implicit settings: Settings[T]): String = {
    if (settings.settings.forall(_.exampleValues.isEmpty)) ""
    else {
      val sb = new StringBuilder
      sb.append("\n\n### Examples\n\n```")
      settings.settings.foreach { setting =>
        setting.exampleValues match {
          case Nil =>
          case example :: _ =>
            sb.append("\n")
              .append(ruleName)
              .append(".")
              .append(setting.name)
              .append(" = ")
              .append(example)
        }
      }
      sb.append("\n```\n\n")
      sb.toString()
    }
  }

  def rule[T](
      ruleName: String,
      default: T
  )(
      implicit settings: Settings[T],
      ev: T <:< Product
  ): String = {
    val sb = new StringBuilder
    val all = flat(default)
    sb.append(html(all.map(_._1)))
    sb.append(defaults(ruleName, all))
    sb.append(examples[T](ruleName))
    sb.toString()
  }

}
