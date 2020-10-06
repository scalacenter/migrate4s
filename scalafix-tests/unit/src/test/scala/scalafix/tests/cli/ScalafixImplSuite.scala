package scalafix.tests.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.URLClassLoader
import java.nio.charset.StandardCharsets
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Paths
import java.util.Collections
import java.util.Optional

import coursier._
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.JavaConverters._
import buildinfo.RulesBuildInfo

import scala.meta.io.AbsolutePath
import scala.meta.io.Classpath
import scalafix.Versions
import scalafix.interfaces.ScalafixDiagnostic
import scalafix.interfaces.ScalafixException
import scalafix.interfaces.ScalafixMainCallback
import scalafix.interfaces.ScalafixMainMode
import scalafix.internal.reflect.ClasspathOps
import scalafix.internal.reflect.RuleCompiler
import scalafix.test.StringFS
import scalafix.testkit.DiffAssertions
import scalafix.tests.util.{ScalaVersions, SemanticdbPlugin}
import scalafix.{interfaces => i}
import RulesBuildInfo.scalaVersion

import scala.util.Properties

class ScalafixImplSuite extends AnyFunSuite with DiffAssertions {

  def scalaLibrary: AbsolutePath =
    RuleCompiler.defaultClasspathPaths
      .find(_.toNIO.getFileName.toString.contains("scala-library"))
      .getOrElse {
        throw new IllegalStateException("Unable to detect scala-library.jar")
      }

  test("versions") {
    val api = i.Scalafix.classloadInstance(this.getClass.getClassLoader)
    assert(api.scalafixVersion() == Versions.version)
    assert(api.scalametaVersion() == Versions.scalameta)
    assert(api.scala211() == Versions.scala211)
    assert(api.scala212() == Versions.scala212)
    assert(
      api
        .supportedScalaVersions()
        .sameElements(Versions.supportedScalaVersions)
    )
    val help = api.mainHelp(80)
    assert(help.contains("Usage: scalafix"))
  }

  test("availableRules") {
    val api = i.Scalafix.classloadInstance(this.getClass.getClassLoader)
    val rules = api.newArguments().availableRules().asScala
    val names = rules.map(_.name())
    assert(names.contains("DisableSyntax"))
    assert(names.contains("AvailableRule"))
    assert(!names.contains("DeprecatedAvailableRule"))
    val hasDescription = rules.filter(_.description().nonEmpty)
    assert(hasDescription.nonEmpty)
    val isSyntactic = rules.filter(_.kind().isSyntactic)
    assert(isSyntactic.nonEmpty)
    val isSemantic = rules.filter(_.kind().isSemantic)
    assert(isSemantic.nonEmpty)
    val isLinter = rules.filter(_.isLinter)
    assert(isLinter.nonEmpty)
    val isRewrite = rules.filter(_.isRewrite)
    assert(isRewrite.nonEmpty)
    val isExperimental = rules.filter(_.isExperimental)
    assert(isExperimental.isEmpty)
  }

  test("error") {
    val cl = new URLClassLoader(Array(), null)
    val ex = intercept[ScalafixException] {
      i.Scalafix.classloadInstance(cl)
    }
    assert(ex.getCause.isInstanceOf[ClassNotFoundException])
  }

  test("validate") {
    // This is a full integration test that stresses the full breadth of the scalafix-interfaces API
    val api = i.Scalafix.classloadInstance(this.getClass.getClassLoader)
    val args = api.newArguments().withRules(List("RemoveUnused").asJava)
    val e = args.validate()
    assert(e.isPresent)
    assert(e.get().getMessage.contains("-Ywarn-unused"))
  }
  test("rulesThatWillRun") {
    val api = i.Scalafix.classloadInstance(this.getClass.getClassLoader)

    val charset = StandardCharsets.US_ASCII
    val cwd = StringFS
      .string2dir(
        """|/src/Semicolon.scala
           |
           |object Semicolon {
           |  def main { println(42) }
           |}
           |/.scalafix.conf
           |rules = ["DisableSyntax"]
      """.stripMargin,
        charset
      )
    val args = api
      .newArguments()
      .withConfig(Optional.empty())
      .withWorkingDirectory(cwd.toNIO)
    args.validate()
    assert(
      args.rulesThatWillRun().asScala.toList.map(_.toString) == List(
        "ScalafixRule(DisableSyntax)"
      )
    )

    //if a non empty list of rules is provided, rules from config file are ignored
    val args2 = api
      .newArguments()
      .withRules(List("ProcedureSyntax").asJava)
      .withConfig(Optional.empty())
      .withWorkingDirectory(cwd.toNIO)
    args2.validate()
    assert(
      args2.rulesThatWillRun().asScala.toList.map(_.name()) == List(
        "ProcedureSyntax"
      )
    )

  }

  test("runMain") {
    // This is a full integration test that stresses the full breadth of the scalafix-interfaces API
    val api = i.Scalafix.classloadInstance(this.getClass.getClassLoader)
    // Assert that non-ascii characters read into "?"
    val charset = StandardCharsets.US_ASCII
    val cwd = StringFS
      .string2dir(
        """|/src/Semicolon.scala
           |
           |object Semicolon {
           |  val a = 1; // みりん þæö
           |  implicit val b = List(1)
           |  def main { println(42) }
           |}
           |
           |/src/Excluded.scala
           |object Excluded {
           |  val a = 1;
           |}
      """.stripMargin,
        charset
      )
      .toNIO
    val d = cwd.resolve("out")
    val src = cwd.resolve("src")
    Files.createDirectories(d)
    val semicolon = src.resolve("Semicolon.scala")
    val excluded = src.resolve("Excluded.scala")
    val scalaBinaryVersion =
      RulesBuildInfo.scalaVersion.split('.').take(2).mkString(".")
    // This rule is published to Maven Central to simplify testing --tool-classpath.
    val dep =
      Dependency(
        Module(
          Organization("ch.epfl.scala"),
          ModuleName(s"example-scalafix-rule_$scalaBinaryVersion")
        ),
        "1.6.0"
      )
    val toolClasspathJars = Fetch()
      .addDependencies(dep)
      .run()
      .toList
    val toolClasspath = ClasspathOps.toClassLoader(
      Classpath(toolClasspathJars.map(jar => AbsolutePath(jar)))
    )
    val scalacOptions = Array[String](
      "-Yrangepos",
      s"-Xplugin:${SemanticdbPlugin.semanticdbPluginPath()}",
      "-Xplugin-require:semanticdb",
      "-classpath",
      scalaLibrary.toString,
      s"-P:semanticdb:sourceroot:$src",
      "-d",
      d.toString,
      semicolon.toString,
      excluded.toString
    )
    val compileSucceeded = scala.tools.nsc.Main.process(scalacOptions)
    val buf = List.newBuilder[ScalafixDiagnostic]
    val callback = new ScalafixMainCallback {
      override def reportDiagnostic(diagnostic: ScalafixDiagnostic): Unit = {
        buf += diagnostic
      }
    }
    val out = new ByteArrayOutputStream()
    val relativePath = cwd.relativize(semicolon)
    val warnRemoveUnused =
      if (ScalaVersions.isScala213)
        "-Wunused:imports"
      else "-Ywarn-unused-import"
    val args = api
      .newArguments()
      .withParsedArguments(
        List("--settings.DisableSyntax.noSemicolons", "true").asJava
      )
      .withCharset(charset)
      .withClasspath(List(d, scalaLibrary.toNIO).asJava)
      .withSourceroot(src)
      .withWorkingDirectory(cwd)
      .withPaths(List(relativePath.getParent).asJava)
      .withExcludedPaths(
        List(
          FileSystems.getDefault.getPathMatcher("glob:**Excluded.scala")
        ).asJava
      )
      .withMainCallback(callback)
      .withRules(
        List(
          "DisableSyntax", // syntactic linter
          "ProcedureSyntax", // syntactic rewrite
          "ExplicitResultTypes", // semantic rewrite
          "class:fix.Examplescalafixrule_v1" // --tool-classpath
        ).asJava
      )
      .withPrintStream(new PrintStream(out))
      .withMode(ScalafixMainMode.CHECK)
      .withToolClasspath(toolClasspath)
      .withScalacOptions(Collections.singletonList(warnRemoveUnused))
      .withScalaVersion(Properties.versionNumberString)
      .withConfig(Optional.empty())
    val expectedRulesToRun = List(
      "ProcedureSyntax",
      "ExplicitResultTypes",
      "ExampleScalafixRule_v1",
      "DisableSyntax"
    )
    val obtainedRulesToRun =
      args.rulesThatWillRun().asScala.toList.map(_.name())
    assertNoDiff(
      obtainedRulesToRun.sorted.mkString("\n"),
      expectedRulesToRun.sorted.mkString("\n")
    )
    val validateError: Optional[ScalafixException] = args.validate()
    assert(!validateError.isPresent, validateError)
    val scalafixErrors = args.run()
    val errors = scalafixErrors.toList.map(_.toString).sorted
    val stdout = fansi
      .Str(out.toString(charset.name()))
      .plainText
      .replaceAllLiterally(semicolon.toString, relativePath.toString)
      .replace('\\', '/') // for windows
      .linesIterator
      .filterNot(_.trim.isEmpty)
      .mkString("\n")
    assert(errors == List("LinterError", "TestError"), stdout)
    val linterDiagnostics = buf
      .result()
      .map { d =>
        d.position()
          .get()
          .formatMessage(d.severity().toString, d.message())
      }
      .mkString("\n\n")
      .replaceAllLiterally(semicolon.toString, relativePath.toString)
      .replace('\\', '/') // for windows
    assertNoDiff(
      linterDiagnostics,
      """|src/Semicolon.scala:3:12: ERROR: semicolons are disabled
         |  val a = 1; // ??? ???
         |           ^
      """.stripMargin
    )
    assertNoDiff(
      stdout,
      """|--- src/Semicolon.scala
         |+++ <expected fix>
         |@@ -1,6 +1,7 @@
         | object Semicolon {
         |   val a = 1; // ??? ???
         |-  implicit val b = List(1)
         |-  def main { println(42) }
         |+  implicit val b: List[Int] = List(1)
         |+  def main: Unit = { println(42) }
         | }
         |+// Hello world!
         |""".stripMargin
    )
  }
}
