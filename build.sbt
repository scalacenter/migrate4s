import sbt.ScriptedPlugin
import sbt.ScriptedPlugin._
import Dependencies._

version.in(ThisBuild) ~= { old: String =>
  sys.props.getOrElse("scalafix.version", old.replace('+', '-'))
}

lazy val scalaFixedProjects: List[ProjectReference] =
  List(
    `scalafix-sbt`,
    testsInputSbt,
    testsOutputDotty,
    testsOutputSbt,
    website
  )

lazy val scala212Projects: List[ProjectReference] =
  List(
    cli212,
    core212JS,
    core212JVM,
    diff212JS,
    diff212JVM,
    reflect212,
    testkit212,
    testsInput212,
    testsOutput212,
    testsShared212,
    unit212
  )

lazy val allScala212Projects: List[ProjectReference] =
  scala212Projects ++ scalaFixedProjects

lazy val scala212ProjectsDependencies: List[ClasspathDep[ProjectReference]] =
  scala212Projects.map(ClasspathDependency(_, None))

lazy val scala211Projects: List[ProjectReference] =
  List(
    cli211,
    core211JS,
    core211JVM,
    diff211JS,
    diff211JVM,
    reflect211,
    testkit211,
    testsInput211,
    testsOutput211,
    testsShared211,
    unit211
  )

lazy val allScala211Projects: List[ProjectReference] =
  scala211Projects ++ scalaFixedProjects

lazy val scala211ProjectsDependencies: List[ClasspathDep[ProjectReference]] =
  scala211Projects.map(ClasspathDependency(_, None))

lazy val scalafix = project
  .in(file("."))
  .settings(
    moduleName := "scalafixRoot",
    onLoadMessage := s"Welcome to Scalafix ${version.value}",
    noPublish,
    scalaVersion := scala212
  )
  .aggregate(allScala212Projects: _*)
  .dependsOn(scala212ProjectsDependencies: _*)

lazy val scalafix211 = project
  .in(file(".scalafix211"))
  .settings(
    moduleName := "scalafix211",
    noPublish,
    scalaVersion := scala211
  )
  .aggregate(allScala211Projects: _*)
  .dependsOn(scala211ProjectsDependencies: _*)

val diff = MultiScalaCrossProject(
  "diff",
  _.settings(
    moduleName := "scalafix-diff",
    description := "JVM/JS library to build unified diffs."
  ).jvmSettings(
      libraryDependencies += googleDiff
    )
    .jsSettings(
      allJSSettings,
      npmDependencies in Compile += "diff" -> "3.2.0"
    )
    .jsConfigure(_.enablePlugins(ScalaJSBundlerPlugin))
)

val diff211 = diff(scala211)
val diff212 = diff(scala212)

lazy val diff211JVM = diff211.jvm
lazy val diff211JS = diff211.js
lazy val diff212JVM = diff212.jvm
lazy val diff212JS = diff212.js

val core = MultiScalaCrossProject(
  "core",
  _.settings(
    buildInfoSettings,
    libraryDependencies ++= List(
      scalameta.value,
      "org.scala-lang" % "scala-reflect" % scalaVersion.value % Provided
    )
  ).jvmSettings(
      libraryDependencies += "com.geirsson" %% "metaconfig-typesafe-config" % metaconfigV
    )
    .jsSettings(
      libraryDependencies += "com.geirsson" %%% "metaconfig-hocon" % metaconfigV
    )
    .enablePlugins(BuildInfoPlugin)
)

val core211 = core(scala211, _.dependsOn(diff211))
val core212 = core(scala212, _.dependsOn(diff212))

lazy val core211JVM = core211.jvm
lazy val core211JS = core211.js
lazy val core212JVM = core212.jvm
lazy val core212JS = core212.js

val reflect = MultiScalaProject(
  "reflect",
  _.settings(
    isFullCrossVersion,
    libraryDependencies ++= Seq(
      "org.scala-lang" % "scala-compiler" % scalaVersion.value,
      "org.scala-lang" % "scala-reflect" % scalaVersion.value
    )
  )
)

lazy val reflect211 = reflect(scala211, _.dependsOn(core211JVM))
lazy val reflect212 = reflect(scala212, _.dependsOn(core212JVM))

val cli = MultiScalaProject(
  "cli",
  _.settings(
    isFullCrossVersion,
    mainClass in assembly := Some("scalafix.cli.Cli"),
    assemblyJarName in assembly := "scalafix.jar",
    libraryDependencies ++= Seq(
      "org.scalameta" %% "semanticdb-sbt-runtime" % semanticdbSbt,
      "com.github.alexarchambault" %% "case-app" % "1.2.0",
      "org.typelevel" %% "paiges-core" % "0.2.0",
      "com.martiansoftware" % "nailgun-server" % "0.9.1"
    )
  )
)
lazy val cli211 =
  cli(scala211, _.dependsOn(core211JVM, reflect211, testkit211 % Test))
lazy val cli212 =
  cli(scala212, _.dependsOn(core212JVM, reflect212, testkit212 % Test))

lazy val `scalafix-sbt` = project
  .settings(
    buildInfoSettings,
    ScriptedPlugin.scriptedSettings,
    commands += Command.command(
      "installCompletions",
      "Code generates names of scalafix rules.",
      "") { s =>
      "cli/run --sbt scalafix-sbt/src/main/scala/scalafix/internal/sbt/ScalafixRewriteNames.scala" ::
        s
    },
    scalaVersion := {
      CrossVersion.binarySbtVersion(scriptedSbt.value) match {
        case "0.13" => scala210
        case _ => scala212
      }
    },
    sbtPlugin := true,
    libraryDependencies ++= coursierDeps,
    testQuick := {}, // these test are slow.
    // scripted tests needs scalafix 2.12
    // semanticdb-scala will generate the semantic db for both scala 2.11 and scala 2.12
    publishLocal := publishLocal
      .dependsOn(
        publishLocal in diff212JVM,
        publishLocal in core212JVM,
        publishLocal in reflect212,
        publishLocal in cli212)
      .value,
    moduleName := "sbt-scalafix",
    mimaPreviousArtifacts := Set.empty,
    scriptedLaunchOpts ++= Seq(
      "-Dplugin.version=" + version.value,
      // .jvmopts is ignored, simulate here
      "-XX:MaxPermSize=256m",
      "-Xmx2g",
      "-Xss2m"
    ),
    scriptedBufferLog := false
  )
  .enablePlugins(BuildInfoPlugin)

val testkit = MultiScalaProject(
  "testkit",
  _.settings(
    isFullCrossVersion,
    libraryDependencies ++= Seq(
      semanticdb,
      ammonite,
      googleDiff,
      scalatest.value
    )
  ))

lazy val testkit211 = testkit(scala211, _.dependsOn(core211JVM, reflect211))
lazy val testkit212 = testkit(scala212, _.dependsOn(core212JVM, reflect212))

val testsShared = TestProject(
  "shared",
  _.settings(
    semanticdbSettings,
    noPublish
  ))

lazy val testsShared211 = testsShared(scala211)
lazy val testsShared212 = testsShared(scala212)

val testsInput = TestProject(
  "input",
  (project, srcMain) =>
    project.settings(
      noPublish,
      semanticdbSettings,
      scalacOptions += {
        val sourceroot = baseDirectory.in(ThisBuild).value / srcMain
        s"-P:semanticdb:sourceroot:$sourceroot"
      },
      scalacOptions ~= (_.filterNot(_ == "-Yno-adapted-args")),
      scalacOptions += "-Ywarn-adapted-args", // For NoAutoTupling
      scalacOptions += "-Ywarn-unused-import", // For RemoveUnusedImports
      scalacOptions += "-Ywarn-unused", // For RemoveUnusedTerms
      logLevel := Level.Error, // avoid flood of compiler warnings
      testsInputOutputSetting
  )
)

lazy val testsInput211 = testsInput(scala211, _.dependsOn(testsShared211))
lazy val testsInput212 = testsInput(scala212, _.dependsOn(testsShared212))

val testsOutput = TestProject(
  "output",
  _.settings(
    noPublish,
    semanticdbSettings,
    scalacOptions -= warnUnusedImports,
    testsInputOutputSetting
  ))

val testsOutput211 = testsOutput(scala211, _.dependsOn(testsShared211))
val testsOutput212 = testsOutput(scala212, _.dependsOn(testsShared212))

lazy val testsOutputDotty = project
  .in(file("scalafix-tests/output-dotty"))
  .settings(
    noPublish,
    // Skip this project for IntellIJ, see https://youtrack.jetbrains.com/issue/SCL-12237
    SettingKey[Boolean]("ide-skip-project") := true,
    scalaVersion := dotty,
    crossScalaVersions := List(dotty),
    libraryDependencies := libraryDependencies.value.map(_.withDottyCompat()),
    scalacOptions := Nil
  )

lazy val testsInputSbt = project
  .in(file("scalafix-tests/input-sbt"))
  .settings(
    noPublish,
    logLevel := Level.Error, // avoid flood of deprecation warnings.
    scalacOptions += "-Xplugin-require:semanticdb-sbt",
    sbtPlugin := true,
    scalacOptions += {
      val sourceroot =
        baseDirectory
          .in(ThisBuild)
          .value / "scalafix-tests" / "input-sbt" / "src" / "main"
      s"-P:semanticdb-sbt:sourceroot:$sourceroot"
    },
    addCompilerPlugin(
      "org.scalameta" % "semanticdb-sbt" % semanticdbSbt cross CrossVersion.full)
  )

lazy val testsOutputSbt = project
  .in(file("scalafix-tests/output-sbt"))
  .settings(
    noPublish,
    sbtPlugin := true
  )

def unit(
    scalav: String,
    cli: Project,
    testkit: Project,
    testsInput: Project,
    testsInputMulti: MultiScalaProject,
    testsInputSbt: Project,
    testsOutput: Project,
    testsOutputMulti: MultiScalaProject,
    testsOutputDotty: Project,
    testsOutputSbt: Project,
    testsShared: Project): Project = {

  val unitMultiProject =
    MultiScalaProject(
      "unit",
      s"scalafix-tests/unit",
      _.settings(
        noPublish,
        fork := false,
        javaOptions := Nil,
        buildInfoPackage := "scalafix.tests",
        buildInfoObject := "BuildInfo",
        sources.in(Test) +=
          sourceDirectory.in(`scalafix-sbt`, Compile).value /
            "scala" / "scalafix" / "internal" / "sbt" / "ScalafixJarFetcher.scala",
        libraryDependencies ++= coursierDeps ++ testsDeps
      ).enablePlugins(BuildInfoPlugin)
    )

  unitMultiProject(
    scalav,
    _.settings(
      compileInputs.in(Compile, compile) := {
        compileInputs
          .in(Compile, compile)
          .dependsOn(
            compile.in(testsInput, Compile),
            compile.in(testsInputSbt, Compile),
            compile.in(testsOutputSbt, Compile),
            compile.in(testsOutputDotty, Compile),
            compile.in(testsOutput, Compile)
          )
          .value
      },
      buildInfoKeys := Seq[BuildInfoKey](
        "baseDirectory" ->
          baseDirectory.in(ThisBuild).value,
        "inputSourceroot" ->
          baseDirectory.in(ThisBuild).value / testsInputMulti.srcMain,
        "outputSourceroot" ->
          baseDirectory.in(ThisBuild).value / testsOutputMulti.srcMain,
        "testsInputResources" ->
          baseDirectory
            .in(ThisBuild)
            .value / testsInputMulti.srcMain / "resources",
        "inputSbtSourceroot" ->
          sourceDirectory.in(testsInputSbt, Compile).value,
        "outputDottySourceroot" ->
          sourceDirectory.in(testsOutputDotty, Compile).value,
        "outputSbtSourceroot" ->
          sourceDirectory.in(testsOutputSbt, Compile).value,
        "semanticSbtClasspath" ->
          classDirectory.in(testsInputSbt, Compile).value,
        "semanticClasspath" ->
          classDirectory.in(testsInput, Compile).value,
        "sharedClasspath" ->
          classDirectory.in(testsShared, Compile).value
      )
    ).dependsOn(
      testsInput,
      cli,
      testkit
    )
  )
}

lazy val unit211 = unit(
  scala211,
  cli211,
  testkit211,
  testsInput211,
  testsInput,
  testsInputSbt,
  testsOutput211,
  testsOutput,
  testsOutputDotty,
  testsOutputSbt,
  testsShared211
)

lazy val unit212 = unit(
  scala212,
  cli212,
  testkit212,
  testsInput212,
  testsInput,
  testsInputSbt,
  testsOutput212,
  testsOutput,
  testsOutputDotty,
  testsOutputSbt,
  testsShared212
)

lazy val website = project
  .enablePlugins(MicrositesPlugin)
  .enablePlugins(ScalaUnidocPlugin)
  .settings(
    scalaVersion := scala212,
    noPublish,
    websiteSettings,
    unidocSettings,
    unidocProjectFilter in (ScalaUnidoc, unidoc) := inProjects(
      testkit212,
      core212JVM)
  )
  .dependsOn(testkit212, core212JVM, cli212)

inScope(Global)(
  Seq(
    credentials ++= (for {
      username <- sys.env.get("SONATYPE_USERNAME")
      password <- sys.env.get("SONATYPE_PASSWORD")
    } yield
      Credentials(
        "Sonatype Nexus Repository Manager",
        "oss.sonatype.org",
        username,
        password)).toSeq,
    PgpKeys.pgpPassphrase := sys.env.get("PGP_PASSPHRASE").map(_.toCharArray())
  )
)
