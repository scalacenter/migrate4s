package scalafix.tests

import scalafix.rewrite.Rewrite
import ammonite.ops._

import java.io.File

import ammonite.ops.Path

case class ItTest(name: String,
                  repo: String,
                  hash: String,
                  config: String = "",
                  cmds: Seq[Command] = Command.default,
                  rewrites: Seq[Rewrite] = Rewrite.defaultRewrites,
                  addCoursier: Boolean = true,
                  testPatch: Boolean = false) {
  def repoName: String = repo match {
    case Command.RepoName(x) => x
    case _ =>
      throw new IllegalArgumentException(
        s"Unable to parse repo name from repo: $repo")
  }
  def workingPath: Path = ItTest.root / repoName
  def parentDir: File = workingPath.toIO.getParentFile
}

object ItTest {
  val organizeImportsConfig: String =
    """|imports.optimize = true
       |imports.removeUnused = true""".stripMargin
  val catsImportConfig: String =
    """|imports.organize = true
       |imports.removeUnused = false
       |imports.groupByPrefix = true""".stripMargin
  val root: Path = pwd / "target" / "it"
}
