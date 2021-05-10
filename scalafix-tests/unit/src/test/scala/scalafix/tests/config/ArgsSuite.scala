package scalafix.tests.config

import scala.meta.io.AbsolutePath

import metaconfig.Conf
import metaconfig.internal.ConfGet
import metaconfig.typesafeconfig.typesafeConfigMetaconfigParser
import scalafix.internal.config.ScalaVersion
import scalafix.internal.config.ScalafixConfig
import scalafix.internal.v1.Args

class ArgsSuite extends munit.FunSuite {

  private lazy val givenConf = Conf
    .parseString(
      "ArgsSuite",
      """
        |rules = [DisableSyntax, RemoveUnused]
        |
        |triggered.rules = [DisableSyntax]
        |
        |DisableSyntax.noVars = true
        |DisableSyntax.noThrows = true
        |
        |triggered = {
        |  DisableSyntax.noVars = false
        |}
        |
        |triggered.DisableSyntax.noReturns = true
        |""".stripMargin
    )
    .get

  test("ignore triggered section if args.triggered is false") {
    val args = Args.default.copy(scalacOptions = "-Ywarn-unused" :: Nil)
    val config = ScalafixConfig()

    assert(!args.triggered, "triggered should be false at default.")

    val rulesConfigured = args.configuredRules(givenConf, config).get

    assert(
      rulesConfigured.rules
        .map(_.name.value) == List("DisableSyntax", "RemoveUnused")
    )

    val merged = args.maybeOverlaidConfWithTriggered(givenConf)

    val disableSyntaxRule = ConfGet.getKey(merged, "DisableSyntax" :: Nil).get

    val expected =
      Conf.Obj("noVars" -> Conf.Bool(true), "noThrows" -> Conf.Bool(true))

    assertEquals(disableSyntaxRule, expected)
  }

  test("use triggered section if args.triggered is true") {
    val args = Args.default.copy(triggered = true)
    val config = ScalafixConfig()

    val rulesConfigured = args.configuredRules(givenConf, config).get

    assert(rulesConfigured.rules.map(_.name.value) == List("DisableSyntax"))

    val merged = args.maybeOverlaidConfWithTriggered(givenConf)

    val disableSyntaxRule = ConfGet.getKey(merged, "DisableSyntax" :: Nil).get

    val expected =
      Conf.Obj(
        "noVars" -> Conf.Bool(false),
        "noThrows" -> Conf.Bool(true),
        "noReturns" -> Conf.Bool(true)
      )

    assertEquals(disableSyntaxRule, expected)
  }

  test("targetRoot") {
    val targetRootPath = "/some-path"
    val scalacOptions = List("first", "-semanticdb-target", targetRootPath)
    val args: Args = Args.default.copy(
      scalacOptions = scalacOptions,
      scalaVersion = ScalaVersion.from("3.0.0-RC3").get
    )
    val classpath = args.validatedClasspath
    assert(classpath.entries.contains(AbsolutePath(targetRootPath)))
  }
}
