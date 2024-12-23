package scalafix.tests.interfaces

import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path

import scala.jdk.CollectionConverters._

import coursierapi.MavenRepository
import coursierapi.Repository
import org.scalatest.funsuite.AnyFunSuite
import scalafix.Versions
import scalafix.interfaces.Scalafix
import scalafix.interfaces.ScalafixDiagnostic
import scalafix.interfaces.ScalafixException
import scalafix.interfaces.ScalafixMainCallback
import scalafix.tests.BuildInfo

/**
 * Some tests below require scalafix-cli & its dependencies to be published so
 * that Coursier can fetch them. `publishLocalTransitive` is done automatically
 * as part of `sbt integrationX / test`, so make sure to run that once if you
 * want to run the test with testOnly or through BSP.
 */
class ScalafixSuite extends AnyFunSuite {

  val scalaVersion: String = BuildInfo.scalaVersion

  val repositories: java.util.List[Repository] = Seq[Repository](
    Repository.ivy2Local(), // for scalafix-*
    Repository.central(), // for scala libs
    MavenRepository.of(
      // for scalameta SNAPSHOTS
      "https://oss.sonatype.org/content/repositories/snapshots"
    )
  ).asJava

  test("versions") {
    val api = Scalafix.classloadInstance(this.getClass.getClassLoader)
    assert(api.scalafixVersion() == Versions.version)
    assert(api.scalaVersion() == scalaVersion)
    assert(api.scalametaVersion() == Versions.scalameta)
    assert(api.scala212() == Versions.scala212)
    assert(api.scala213() == Versions.scala213)
    assert(api.scala33() == Versions.scala33)
    assert(api.scala36() == Versions.scala36)
    assert(api.scala3LTS() == Versions.scala3LTS)
    assert(api.scala3Next() == Versions.scala3Next)
    assert(
      api
        .supportedScalaVersions()
        .sameElements(Versions.supportedScalaVersions)
    )
    val help = api.mainHelp(80)
    assert(help.contains("Usage: scalafix"))
  }

  test("fail to classload EOL versions") {
    assertThrows[IllegalArgumentException](
      Scalafix.fetchAndClassloadInstance("2.11", repositories)
    )
    assertThrows[IllegalArgumentException](
      Scalafix.fetchAndClassloadInstance("3.0", repositories)
    )
    assertThrows[IllegalArgumentException](
      Scalafix.fetchAndClassloadInstance("3.1", repositories)
    )
    assertThrows[IllegalArgumentException](
      Scalafix.fetchAndClassloadInstance("3.2", repositories)
    )
    assertThrows[IllegalArgumentException](
      Scalafix.fetchAndClassloadInstance("3.4", repositories)
    )
    assertThrows[IllegalArgumentException](
      Scalafix.fetchAndClassloadInstance("3.5", repositories)
    )
  }

  test("classload Scala 2.12 with full version") {
    val scalafixAPI =
      Scalafix.fetchAndClassloadInstance("2.12.20", repositories)
    assert(scalafixAPI.scalaVersion() == Versions.scala212)
  }

  test("classload Scala 2.12 with major.minor version") {
    val scalafixAPI = Scalafix.fetchAndClassloadInstance("2.12", repositories)
    assert(scalafixAPI.scalaVersion() == Versions.scala212)
  }

  test("classload Scala 2.13 with full version") {
    val scalafixAPI =
      Scalafix.fetchAndClassloadInstance("2.13.15", repositories)
    assert(scalafixAPI.scalaVersion() == Versions.scala213)
  }

  test("classload Scala 2.13 with major.minor version") {
    val scalafixAPI = Scalafix.fetchAndClassloadInstance("2.13", repositories)
    assert(scalafixAPI.scalaVersion() == Versions.scala213)
  }

  test("classload Scala 2.13 with major version") {
    val scalafixAPI = Scalafix.fetchAndClassloadInstance("2", repositories)
    assert(scalafixAPI.scalaVersion() == Versions.scala213)
  }

  test("classload Scala 3 LTS with full LTS version") {
    val scalafixAPI = Scalafix.fetchAndClassloadInstance("3.3.4", repositories)
    assert(scalafixAPI.scalaVersion() == Versions.scala3LTS)
  }

  test("classload Scala 3 LTS with major.minor LTS version") {
    val scalafixAPI = Scalafix.fetchAndClassloadInstance("3.3", repositories)
    assert(scalafixAPI.scalaVersion() == Versions.scala3LTS)
  }

  test("classload Scala 3 Next with full version") {
    val scalafixAPI = Scalafix.fetchAndClassloadInstance("3.6.2", repositories)
    assert(scalafixAPI.scalaVersion() == Versions.scala3Next)
  }

  test("classload Scala 3 Next with major.minor version") {
    val scalafixAPI = Scalafix.fetchAndClassloadInstance("3.6", repositories)
    assert(scalafixAPI.scalaVersion() == Versions.scala3Next)
  }

  test("classload Scala 3 Next with major version") {
    val scalafixAPI = Scalafix.fetchAndClassloadInstance("3", repositories)
    assert(scalafixAPI.scalaVersion() == Versions.scala3Next)
  }

  test("invalid class loader") {
    val cl = new URLClassLoader(Array(), null)
    val ex = intercept[ScalafixException] {
      Scalafix.classloadInstance(cl)
    }
    assert(ex.getCause.isInstanceOf[ClassNotFoundException])
  }

  def tmpFile(prefix: String, suffix: String)(content: String): Path = {
    val path = Files.createTempFile(prefix, suffix)
    Files.write(path, content.getBytes)
    path
  }

  test(s"fetch & load cli for $scalaVersion") {
    val scalafixAPI = Scalafix.fetchAndClassloadInstance(
      scalaVersion,
      repositories
    )
    val args = scalafixAPI.newArguments

    assert(args.availableRules.asScala.map(_.name).contains("RemoveUnused"))

    // inspect the Scala version used to compile core by running a custom rule
    val ruleSource =
      tmpFile("CaptureScalaVersion", ".scala") {
        """import scalafix.v1._
          |class CaptureScalaVersion extends SyntacticRule("CaptureScalaVersion") {
          |  override def fix(implicit doc: SyntacticDocument): Patch =
          |    Patch.lint(
          |      Diagnostic(
          |        "",
          |        scalafix.Versions.scalaVersion,
          |        scala.meta.Position.None,
          |        "",
          |        scalafix.lint.LintSeverity.Error
          |      )
          |    )
          |}""".stripMargin
      }
    var maybeDiagnostic: Option[ScalafixDiagnostic] = None
    val scalafixMainCallback = new ScalafixMainCallback {
      override def reportDiagnostic(diagnostic: ScalafixDiagnostic): Unit =
        maybeDiagnostic = Some(diagnostic)
    }
    args
      .withRules(Seq(ruleSource.toUri.toString).asJava)
      .withMainCallback(scalafixMainCallback)
      // any target file would do to emit a diagnostic, so run the rule on itself
      .withPaths(List(ruleSource).asJava)
      .withWorkingDirectory(ruleSource.getParent)
      .run()
    val expectedScalaVersion = scalaVersion match {
      case scala3 if scala3.startsWith("3") => "2.13"
      case scala2 => scala2
    }
    assert(maybeDiagnostic.get.message.startsWith(expectedScalaVersion))
  }

  test(s"fetch & load cli for $scalaVersion with external dependencies") {
    val scalafixAPI = Scalafix.fetchAndClassloadInstance(
      scalaVersion,
      repositories
    )

    val ruleForDependency = Map(
      // built against scalafix 0.9.16
      "com.nequissimus::sort-imports:0.5.2" -> "SortImports",
      // built against scalafix 0.10.0, uses metaconfig
      "com.github.xuwei-k::scalafix-rules:0.2.1" -> "KindProjector"
    )

    val availableRules = scalafixAPI.newArguments
      .withToolClasspath(
        Seq[URL]().asJava,
        ruleForDependency.keys.toList.asJava,
        Seq[Repository](Repository.central()).asJava
      )
      .availableRules
      .asScala
      .map(_.name)

    assert(availableRules.contains("RemoveUnused")) // built-in
    ruleForDependency.values.foreach { rule =>
      assert(availableRules.contains(rule))
    }
  }

}
