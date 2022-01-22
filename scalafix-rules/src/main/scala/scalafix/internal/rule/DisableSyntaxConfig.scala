package scalafix.internal.rule

import java.util.regex.Pattern

import scala.meta.tokens.Token

import metaconfig.Conf
import metaconfig.ConfDecoder
import metaconfig.ConfError
import metaconfig.Configured
import metaconfig.annotation.Description
import metaconfig.annotation.ExampleValue
import metaconfig.annotation.Hidden
import metaconfig.generic
import metaconfig.generic.Surface
import scalafix.config.CustomMessage
import scalafix.config.Regex

case class DisableSyntaxConfig(
    @Hidden
    @Description("Report error on usage of a given set of keywords.")
    keywords: Set[DisabledKeyword] = Set(),
    @Description("Report error for usage of vars.")
    noVars: Boolean = false,
    @Description("Report error for throwing exceptions.")
    noThrows: Boolean = false,
    @Description("Report error for usage of null.")
    noNulls: Boolean = false,
    @Description("Report error for usage of return.")
    noReturns: Boolean = false,
    @Description("Report error for usage of while loops.")
    noWhileLoops: Boolean = false,
    @Description("Report error for usage of asInstanceOf[T] casts.")
    noAsInstanceOf: Boolean = false,
    @Description("Report error for usage of isInstanceOf[T] checks.")
    noIsInstanceOf: Boolean = false,
    @Hidden
    @Description("Report error on semicolon characters.")
    noSemicolons: Boolean = false,
    @Description("Report error on tab characters.")
    @Hidden
    noTabs: Boolean = false,
    @Description("Report error on xml literals.")
    noXml: Boolean = false,
    @Hidden
    @Description("Report error on covariant type parameters.")
    noCovariantTypes: Boolean = false,
    @Hidden
    @Description("Report error on contravariant type parameters.")
    noContravariantTypes: Boolean = false,
    @Description("Report error on method parameters with default arguments.")
    noDefaultArgs: Boolean = false,
    @Hidden
    @Description("Report error on vals inside abstract class/trait.")
    noValInAbstract: Boolean = false,
    @Hidden
    @Description("Report error on object with `implicit` modifier.")
    noImplicitObject: Boolean = false,
    @Hidden
    @Description("Report error on method that define an implicit conversion. ")
    noImplicitConversion: Boolean = false,
    @Description(
      "Report error on `final` modifier for val definitions, " +
        "see [motivation](https://github.com/sbt/zinc/issues/227)"
    )
    noFinalVal: Boolean = false,
    @Description("Reports error when finalize is overridden.")
    noFinalize: Boolean = false,
    @Description(
      "Report error when pattern matching in val assignment with non-tuple patterns."
    )
    noValPatterns: Boolean = false,
    @Description(
      "Report error on `==` (universal equality)"
    )
    @ExampleValue("true")
    noUniversalEquality: Boolean = false,
    @Description(
      "Reporter message for noUniversalEquality"
    )
    @ExampleValue("\"use === instead of ==\"")
    noUniversalEqualityMessage: String =
      "== and != are unsafe since they allow comparing two unrelated types",
    @Description(
      "Report error if the text contents of a source file matches a given regex."
    )
    @ExampleValue(
      """|[
        |  {
        |    id = "offensive"
        |    pattern = "[Pp]imp"
        |    message = "Please consider a less offensive word such as 'extension' or 'enrichment'"
        |  }
        |]""".stripMargin
    )
    regex: List[CustomMessage[Either[Regex, Pattern]]] = Nil
) {

  def isDisabled(keyword: String): Boolean = keyword match {
    case "var" if noVars => true
    case "throw" if noThrows => true
    case "null" if noNulls => true
    case "return" if noReturns => true
    case "while" if noWhileLoops => true
    case _ => keywords.contains(DisabledKeyword(keyword))
  }
}

object DisableSyntaxConfig {
  val default: DisableSyntaxConfig = DisableSyntaxConfig()
  implicit val surface: Surface[DisableSyntaxConfig] =
    generic.deriveSurface[DisableSyntaxConfig]
  // Do not remove this import even if IntelliJ thinks it's unused
  import scalafix.internal.config.ScalafixMetaconfigReaders._
  implicit val decoder: ConfDecoder[DisableSyntaxConfig] =
    generic.deriveDecoder[DisableSyntaxConfig](default)
}

object Keyword {
  def unapply(token: Token): Option[String] = {
    val prefix = token.productPrefix
    if (kwSet.contains(prefix)) {
      Some(prefix.drop(2).toLowerCase)
    } else {
      None
    }
  }
  val all: List[String] = List(
    "abstract",
    "case",
    "catch",
    "class",
    "def",
    "do",
    "else",
    "enum",
    "extends",
    "false",
    "final",
    "finally",
    "for",
    "forSome",
    "if",
    "implicit",
    "import",
    "lazy",
    "match",
    "macro",
    "new",
    "null",
    "object",
    "override",
    "package",
    "private",
    "protected",
    "return",
    "sealed",
    "super",
    "this",
    "throw",
    "trait",
    "true",
    "try",
    "type",
    "val",
    "var",
    "while",
    "with",
    "yield"
  )
  val set: Set[String] = all.toSet
  private val kwSet = all.map(kw => s"Kw${kw.capitalize}").toSet
}

case class DisabledKeyword(keyword: String)

object DisabledKeyword {
  implicit val reader: ConfDecoder[DisabledKeyword] =
    new ConfDecoder[DisabledKeyword] {
      override def read(conf: Conf): Configured[DisabledKeyword] = {
        def readKeyword(keyword: String): Configured[DisabledKeyword] = {
          if (Keyword.set.contains(keyword))
            Configured.Ok(DisabledKeyword(keyword))
          else Configured.NotOk(oneOfTypo(keyword, conf))
        }
        conf match {
          case Conf.Null() => readKeyword("null")
          case Conf.Str(keyword) => readKeyword(keyword)
          case Conf.Bool(value) =>
            if (value) readKeyword("true")
            else readKeyword("false")
          case _ => Configured.typeMismatch("String", conf)
        }
      }
    }

  // XXX: This is from metaconfig.ConfError
  def oneOfTypo(keyword: String, conf: Conf): ConfError = {
    val closestKeyword = Keyword.all.minBy(levenshtein(keyword))
    val relativeDistance =
      levenshtein(keyword)(closestKeyword) /
        keyword.length.toDouble

    val didYouMean =
      if (relativeDistance < 0.20) s" (Did you mean: $closestKeyword?)"
      else ""

    ConfError.message(s"$keyword is not in our supported keywords.$didYouMean")
  }

  /** Levenshtein distance. Implementation based on Wikipedia's algorithm. */
  private def levenshtein(s1: String)(s2: String): Int = {
    val dist = Array.tabulate(s2.length + 1, s1.length + 1) { (j, i) =>
      if (j == 0) i else if (i == 0) j else 0
    }

    for (j <- 1 to s2.length; i <- 1 to s1.length)
      dist(j)(i) =
        if (s2(j - 1) == s1(i - 1))
          dist(j - 1)(i - 1)
        else
          dist(j - 1)(i)
            .min(dist(j)(i - 1))
            .min(dist(j - 1)(i - 1)) + 1

    dist(s2.length)(s1.length)
  }
}
