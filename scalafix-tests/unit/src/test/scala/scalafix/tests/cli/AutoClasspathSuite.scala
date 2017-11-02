package scalafix.tests.cli

import java.io.File
import scala.meta.io.AbsolutePath
import scala.meta.io.Classpath
import scalafix.cli.CliRunner
import scalafix.testkit.utest.ScalafixTest

object AutoClasspathSuite extends ScalafixTest {
  test("--classpath=auto") {
    val tmp = File.createTempFile("foo", "bar")
    assert(tmp.delete())
    val target = tmp.toPath.resolve("target")
    val target2 = tmp.toPath.resolve("bar")
    val semanticdb = target.resolve("META-INF").resolve("semanticdb")
    val semanticdb2 = target2.resolve("META-INF").resolve("semanticdb")
    val fakesemanticdb = tmp.toPath.resolve("blah").resolve("META-INF")
    assert(semanticdb.toFile.mkdirs())
    assert(semanticdb2.toFile.mkdirs())
    assert(fakesemanticdb.toFile.mkdirs())
    val obtained = CliRunner.autoClasspath(List(AbsolutePath(tmp)))
    val expected = Classpath(List(target, target2).map(AbsolutePath.apply))
    assert(obtained.shallow.toSet == expected.shallow.toSet)
  }
}
