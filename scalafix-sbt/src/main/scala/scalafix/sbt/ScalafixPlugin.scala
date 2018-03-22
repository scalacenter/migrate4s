package scalafix.sbt

import scalafix.Versions
import sbt.File
import sbt.Keys._
import sbt._
import sbt.complete.Parser
import sbt.plugins.JvmPlugin
import scalafix.internal.sbt.ScalafixCompletions
import scalafix.internal.sbt.ScalafixJarFetcher
import sbt.Def

object ScalafixPlugin extends AutoPlugin {
  override def trigger: PluginTrigger = allRequirements
  override def requires: Plugins = JvmPlugin
  object autoImport {
    val scalafix: InputKey[Unit] =
      inputKey[Unit]("Run scalafix rule.")
    val scalafixCli: InputKey[Unit] =
      inputKey[Unit]("Run scalafix rule.")
    val scalafixTest: InputKey[Unit] =
      inputKey[Unit](
        "Runs scalafix failing the build if sources would change. Does not modify files.")
    val scalafixAutoSuppressLinterErrors: InputKey[Unit] =
      inputKey[Unit](
        "Run scalafix and automatically suppress linter errors " +
          "by inserting /* scalafix:ok */ comments into the source code. " +
          "Useful when migrating an existing large codebase with many linter errors.")
    val sbtfix: InputKey[Unit] =
      inputKey[Unit](
        "Run syntactic scalafix rule on build sources. Note, semantic rewrites are not supported.")
    val sbtfixTest: InputKey[Unit] =
      inputKey[Unit](
        "Run scalafix on build sources failing the build if source would change. Does not modify files.")
    val scalafixConfig: SettingKey[Option[File]] =
      settingKey[Option[File]](
        ".scalafix.conf file to specify which scalafix rules should run.")
    val scalafixVersion: SettingKey[String] = settingKey[String](
      s"Which scalafix version to run. Default is ${Versions.version}.")
    val scalafixScalaVersion: SettingKey[String] = settingKey[String](
      s"Which scala version to run scalafix from. Default is ${Versions.scala212}.")
    val scalafixSemanticdbVersion: SettingKey[String] = settingKey[String](
      s"Which version of semanticdb to use. Default is ${Versions.scalameta}.")
    val scalafixSemanticdb =
      "org.scalameta" % "semanticdb-scalac" % Versions.scalameta cross CrossVersion.full
    val scalafixVerbose: SettingKey[Boolean] =
      settingKey[Boolean]("pass --verbose to scalafix")

    lazy val scalafixConfigSettings: Seq[Def.Setting[_]] = Seq(
      scalafix := scalafixTaskImpl(
        scalafixParserCompat,
        compat = true,
        Seq("--format", "sbt")).evaluated,
      scalafixTest := scalafixTaskImpl(
        scalafixParserCompat,
        compat = true,
        Seq("--test", "--format", "sbt")).evaluated,
      scalafixCli := scalafixTaskImpl(
        scalafixParser,
        compat = false,
        Seq("--format", "sbt")).evaluated,
      scalafixAutoSuppressLinterErrors := scalafixTaskImpl(
        scalafixParser,
        compat = true,
        Seq("--auto-suppress-linter-errors", "--format", "sbt")).evaluated
    )

    @deprecated("This setting is no longer used", "0.6.0")
    val scalafixSourceroot: SettingKey[File] = settingKey[File]("Unused")
    @deprecated("Use scalacOptions += -Yrangepos instead", "0.6.0")
    def scalafixScalacOptions: Def.Initialize[Seq[String]] =
      ScalafixPlugin.scalafixScalacOptions
    @deprecated("Use addCompilerPlugin(semanticdb-scalac) instead", "0.6.0")
    def scalafixLibraryDependencies: Def.Initialize[List[ModuleID]] =
      ScalafixPlugin.scalafixLibraryDependencies
    @deprecated("This setting is no longer used", "0.6.0")
    def sbtfixSettings: Seq[Def.Setting[_]] = Nil
    @deprecated(
      "Use addCompilerPlugin(semanticdb-scalac) and scalacOptions += -Yrangepos instead",
      "0.6.0")
    def scalafixSettings: Seq[Def.Setting[_]] = List(
      scalacOptions ++= scalafixScalacOptions.value,
      libraryDependencies ++= scalafixLibraryDependencies.value
    )
  }
  import scalafix.internal.sbt.CliWrapperPlugin.autoImport._
  import autoImport._

  override def projectSettings: Seq[Def.Setting[_]] =
    Seq(Compile, Test).flatMap(inConfig(_)(scalafixConfigSettings))

  override def globalSettings: Seq[Def.Setting[_]] = Seq(
    scalafixConfig := Option(file(".scalafix.conf")).filter(_.isFile),
    cliWrapperMainClass := "scalafix.cli.Cli$",
    scalafixVerbose := false,
    commands += ScalafixEnable.command,
    sbtfix := sbtfixImpl(compat = true).evaluated,
    sbtfixTest := sbtfixImpl(compat = true, extraOptions = Seq("--test")).evaluated,
    aggregate.in(sbtfix) := false,
    aggregate.in(sbtfixTest) := false,
    scalafixVersion := Versions.version,
    scalafixSemanticdbVersion := Versions.scalameta,
    scalafixScalaVersion := Versions.scala212,
    cliWrapperClasspath := {
      val jars = ScalafixJarFetcher.fetchJars(
        "ch.epfl.scala",
        s"scalafix-cli_${scalafixScalaVersion.value}",
        scalafixVersion.value
      )
      if (jars.isEmpty) {
        throw new MessageOnlyException("Unable to download scalafix-cli jars!")
      }
      jars
    }
  )

  private def sbtfixImpl(compat: Boolean, extraOptions: Seq[String] = Seq()) = {
    Def.inputTaskDyn {
      val baseDir = baseDirectory.in(ThisBuild).value
      val sbtDir: File = baseDir./("project")
      val sbtFiles = baseDir.*("*.sbt").get
      val options =
        "--no-strict-semanticdb" ::
          "--classpath-auto-roots" ::
          baseDir./("target").getAbsolutePath ::
          sbtDir.getAbsolutePath ::
          Nil ++ extraOptions
      scalafixTaskImpl(
        scalafixParserCompat.parsed,
        compat,
        options,
        sbtDir +: sbtFiles,
        "sbt-build",
        streams.value
      )
    }
  }

  // hack to avoid illegal dynamic reference, can't figure out how to do
  // scalafixParser(baseDirectory.in(ThisBuild).value).parsed
  private def workingDirectory = file(sys.props("user.dir"))

  private val scalafixParser =
    ScalafixCompletions.parser(workingDirectory.toPath, compat = false)
  private val scalafixParserCompat =
    ScalafixCompletions.parser(workingDirectory.toPath, compat = true)

  private val isSupportedScalaVersion = Def.setting {
    CrossVersion.partialVersion(scalaVersion.value) match {
      case Some((2, 11 | 12)) => true
      case _ => false
    }
  }

  lazy val scalafixLibraryDependencies: Def.Initialize[List[ModuleID]] =
    Def.setting {
      if (isSupportedScalaVersion.value) {
        compilerPlugin(
          "org.scalameta" % "semanticdb-scalac" % scalafixSemanticdbVersion.value cross CrossVersion.full
        ) :: Nil
      } else Nil
    }
  lazy val scalafixScalacOptions: Def.Initialize[Seq[String]] = Def.setting {
    if (isSupportedScalaVersion.value) {
      Seq(
        "-Yrangepos",
        s"-Xplugin-require:semanticdb"
      )
    } else Nil
  }

  def scalafixTaskImpl(
      parser: Parser[Seq[String]],
      compat: Boolean,
      extraOptions: Seq[String] = Seq()): Def.Initialize[InputTask[Unit]] =
    Def.inputTaskDyn {
      scalafixTaskImpl(parser.parsed, compat, extraOptions)
    }

  def scalafixTaskImpl(
      inputArgs: Seq[String],
      compat: Boolean,
      extraOptions: Seq[String]): Def.Initialize[Task[Unit]] =
    Def.taskDyn {
      compile.value // trigger compilation
      val classDir = classDirectory.value
      val classpath = if (classDir.exists()) classDir.toString else ""
      val sourcesToFix = for {
        source <- unmanagedSources.value
        if source.exists()
        if canFix(source)
      } yield source
      val options: Seq[String] = List("--classpath", classpath) ++ extraOptions
      scalafixTaskImpl(
        inputArgs,
        compat,
        options,
        sourcesToFix,
        thisProject.value.id,
        streams.value
      )
    }

  def scalafixTaskImpl(
      inputArgs: Seq[String],
      compat: Boolean,
      options: Seq[String],
      files: Seq[File],
      projectId: String,
      streams: TaskStreams
  ): Def.Initialize[Task[Unit]] = {
    if (files.isEmpty) Def.task(())
    else {
      Def.task {
        val log = streams.log
        val verbose = if (scalafixVerbose.value) "--verbose" :: Nil else Nil
        val main = cliWrapperMain.value
        val baseArgs = Set[String](
          "--project-id",
          projectId,
          "--no-sys-exit",
          "--non-interactive"
        )
        val args: Seq[String] = {
          // run scalafix rules
          val config =
            scalafixConfig.value
              .map(x => "--config" :: x.getAbsolutePath :: Nil)
              .getOrElse(Nil)

          val inputArgs0 =
            if (compat && inputArgs.nonEmpty) "--rules" +: inputArgs
            else inputArgs

          // only fix unmanaged sources, skip code generated files.
          verbose ++
            config ++
            inputArgs0 ++
            baseArgs ++
            options
        }
        val finalArgs = args ++ files.map(_.getAbsolutePath)
        val nonBaseArgs = args.filterNot(baseArgs).mkString(" ")
        log.info(s"Running scalafix $nonBaseArgs")
        main.main(finalArgs.toArray)
      }
    }
  }

  private def canFix(file: File): Boolean = {
    val path = file.getPath
    path.endsWith(".scala") ||
    path.endsWith(".sbt")
  }
}
