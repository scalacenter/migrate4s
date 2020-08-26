package scalafix.tests.interfaces

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import java.util.Collections

import buildinfo.RulesBuildInfo
import org.scalatest.funsuite.AnyFunSuite
import scalafix.interfaces.ScalafixArguments
import scalafix.internal.interfaces.ScalafixArgumentsImpl
import scalafix.internal.reflect.ClasspathOps
import scalafix.internal.rule.{RemoveUnused, RemoveUnusedConfig}
import scalafix.internal.tests.utils.SkipWindows
import scalafix.test.StringFS
import scalafix.testkit.DiffAssertions
import scalafix.tests.core.Classpaths
import scalafix.tests.util.ScalaVersions
import scalafix.v1.SemanticRule

import scala.meta.io.AbsolutePath
import collection.JavaConverters._

class ScalafixArgumentsSuite extends AnyFunSuite with DiffAssertions {
  val scalaBinaryVersion =
    RulesBuildInfo.scalaVersion.split('.').take(2).mkString(".")
  val scalaVersion = RulesBuildInfo.scalaVersion
  val removeUnused =
    if (ScalaVersions.isScala213)
      "-Wunused:imports"
    else "-Ywarn-unused-import"
  val api: ScalafixArguments = ScalafixArgumentsImpl()

  val charset = StandardCharsets.US_ASCII
  val cwd = StringFS
    .string2dir(
      """|/src/Main.scala
         |import scala.concurrent.duration
         |import scala.concurrent.Future
         |
         |object Main extends App {
         |  import scala.concurrent.Await
         |  println("test");
         |  println("ok")
         |}
      """.stripMargin,
      charset
    )
    .toNIO
  val d = cwd.resolve("out")
  val target = cwd.resolve("target")
  val src = cwd.resolve("src")
  Files.createDirectories(d)
  val main = src.resolve("Main.scala")
  val relativePath = cwd.relativize(main)

  val scalacOptions = Array[String](
    "-Yrangepos",
    removeUnused,
    s"-Xplugin:${semanticdbPluginPath()}",
    "-Xplugin-require:semanticdb",
    "-classpath",
    s"${scalaLibrary.mkString(":")}",
    s"-P:semanticdb:sourceroot:$src",
    s"-P:semanticdb:targetroot:$target",
    "-d",
    d.toString,
    main.toString
  )

  test("ScalafixArgumentsSuite test RemoveUnused rule", SkipWindows) {
    val _ = scala.tools.nsc.Main.process(scalacOptions)
    val result = api
      .withRules(
        List(
          removeUnsuedRule().name.toString(),
          "ExplicitResultTypes",
          "DisableSyntax"
        ).asJava
      )
      .withParsedArguments(
        List("--settings.DisableSyntax.noSemicolons", "true").asJava
      )
      .withClasspath((scalaLibrary.map(_.toNIO) :+ target).asJava)
      .withScalaVersion(scalaVersion)
      .withScalacOptions(Collections.singletonList(removeUnused))
      .withPaths(Seq(main).asJava)
      .withSourceroot(src)
      .evaluate()

    val errors = result.getError.toList.map(_.toString)
    assert(errors == List("LinterError"))
    assert(result.getScalafixOutputs.length == 1)
    val scalafixOutput = result.getScalafixOutputs.head
    val expected =
      """|
         |object Main extends App {
         |  println("test");
         |  println("ok")
         |}
         |""".stripMargin
    val obtained = scalafixOutput.getOutputFixed.get()
    assertNoDiff(obtained, expected)

    val linterError = scalafixOutput.getDiagnostics.toList
    val linterErrorFormatted = linterError
      .map { d =>
        d.position()
          .get()
          .formatMessage(d.severity().toString, d.message())
      }
      .mkString("\n\n")
      .replaceAllLiterally(main.toString, relativePath.toString)
      .replace('\\', '/') // for windows
    assertNoDiff(
      linterErrorFormatted,
      """|src/Main.scala:6:18: ERROR: semicolons are disabled
         |  println("test");
         |                 ^
      """.stripMargin
    )

    val unifiedDiff = scalafixOutput.getUnifiedDiff.get()
    assert(unifiedDiff.nonEmpty)
    val patches = scalafixOutput.getPatches.toList
    val patchKind = patches.map(_.kind())
    assert(patchKind.toSet == Set("RemoveImportee"))

    val expectedWithOnePatch =
      """|
         |import scala.concurrent.Future
         |
         |object Main extends App {
         |  import scala.concurrent.Await
         |  println("test");
         |  println("ok")
         |}
         |""".stripMargin
    // if applying all patches we should get the same result
    val obtained2 =
      scalafixOutput.getOutputFixedWithSelectivePatches(patches.toArray).get()
    assertNoDiff(obtained2, expected)

    val obtained3 = scalafixOutput
      .getOutputFixedWithSelectivePatches(Seq(patches.head).toArray)
      .get
    assertNoDiff(obtained3, expectedWithOnePatch)

  }

  def removeUnsuedRule(): SemanticRule = {
    val config = RemoveUnusedConfig.default
    new RemoveUnused(config)
  }

  def scalaLibrary: Seq[AbsolutePath] = Classpaths.scalaLibrary.entries

  def semanticdbPluginPath(): String = {
    val semanticdbscalac = ClasspathOps.thisClassLoader.getURLs.collectFirst {
      case url if url.toString.contains("semanticdb-scalac_") =>
        Paths.get(url.toURI).toString
    }
    semanticdbscalac.getOrElse {
      throw new IllegalStateException(
        "unable to auto-detect semanticdb-scalac compiler plugin"
      )
    }
  }
}
