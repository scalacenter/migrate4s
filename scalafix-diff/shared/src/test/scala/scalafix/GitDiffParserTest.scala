package scalafix.internal.diff

import java.nio.file.Paths
import org.scalatest.FunSuite
import scala.io.Source

class GitDiffParserTest extends FunSuite {

  test("parse chunks") {
    val hunkHeaders = List(
      "@@ -1,18 +0,0 @@",
      "@@ -11 +11 @@ import scalafix.util.SymbolMatcher",
      "@@ -14 +14,3 @@ import scalafix.syntax._",
    )

    val hunks = List(
      Hunk(1, 18, 0, 0),
      Hunk(11, 1, 11, 1),
      Hunk(14, 1, 14, 3)
    )

    hunkHeaders.zip(hunks).map {
      case (header, expected) =>
        header match {
          case HunkExtractor(hunk) =>
            assert(hunk == expected)
        }
    }
  }

  test("100% similarity") {
    val diff =
      """|diff --git a/A.scala b/B.scala
         |similarity index 100%
         |rename from A.scala
         |rename to B.scala""".stripMargin

    val itt = Source.fromString(diff).getLines
    val gitDiffparser = new GitDiffParser(itt, Paths.get("."))
    val diffs = gitDiffparser.parse()

  }

  test("parse tests") {
    (1 to 3).foreach { i =>
      val source = Source.fromURL(
        getClass.getClassLoader.getResource(s"./git$i.diff")
      )
      val gitDiffparser = new GitDiffParser(source.getLines, Paths.get("."))
      val diffs = gitDiffparser.parse()
      assert(!diffs.isEmpty)
      source.close()
    }
  }
}
