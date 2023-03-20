import Dependencies._
import sbt._
import sbt.Classpaths
import sbt.Keys._
import sbt.nio.Keys._
import sbt.plugins.JvmPlugin
import com.typesafe.tools.mima.plugin.MimaPlugin.autoImport._
import sbtbuildinfo.BuildInfoKey
import sbtbuildinfo.BuildInfoPlugin.autoImport._
import sbtversionpolicy.SbtVersionPolicyPlugin
import sbtversionpolicy.SbtVersionPolicyPlugin.autoImport._
import scalafix.sbt.ScalafixPlugin.autoImport._
import com.github.sbt.sbtghpages.GhpagesKeys
import sbt.librarymanagement.ivy.IvyDependencyResolution
import sbt.plugins.IvyPlugin

object ScalafixBuild extends AutoPlugin with GhpagesKeys {
  override def trigger = allRequirements
  override def requires =
    JvmPlugin &&
      SbtVersionPolicyPlugin // don't let it override our mimaPreviousArtifacts
  object autoImport {
    lazy val stableVersion =
      settingKey[String]("Version of latest release to Maven.")
    lazy val noPublishAndNoMima = Seq(
      mimaReportBinaryIssues := {},
      mimaPreviousArtifacts := Set.empty,
      publish / skip := true
    )
    lazy val supportedScalaVersions = List(scala213, scala212)
    lazy val publishLocalTransitive =
      taskKey[Unit]("Run publishLocal on this project and its dependencies")
    lazy val isFullCrossVersion = Seq(
      crossVersion := CrossVersion.full
    )
    lazy val isScala3 = Def.setting {
      scalaVersion.value.startsWith("3")
    }
    lazy val isScala2 = Def.setting {
      scalaVersion.value.startsWith("2")
    }
    lazy val isScala213 = Def.setting {
      scalaVersion.value.startsWith("2.13")
    }
    lazy val isScala212 = Def.setting {
      scalaVersion.value.startsWith("2.12")
    }
    lazy val warnUnusedImports = Def.setting {
      if (isScala3.value) Nil
      else if (isScala213.value) Seq("-Wunused:imports")
      else Seq("-Ywarn-unused-import")
    }
    lazy val warnUnused = Def.setting {
      if (isScala2.value) Seq("-Ywarn-unused")
      else Nil
    }
    lazy val targetJvm = Def.setting {
      if (isScala3.value) Seq("-Xtarget:8")
      else if (isScala213.value) Seq("-release", "8")
      else Seq("-target:jvm-1.8")
    }
    val warnAdaptedArgs = Def.setting {
      if (isScala3.value) Nil
      else if (isScala213.value) Seq("-Xlint:adapted-args", "-deprecation")
      else Seq("-Ywarn-adapted-args", "-deprecation")
    }
    lazy val scaladocOptions = Seq(
      "-groups",
      "-implicits"
    )
    lazy val testsDependencies = Def.setting {
      val otherLibs =
        if (isScala2.value)
          Seq(
            bijectionCore,
            "org.scala-lang" % "scala-reflect" % scalaVersion.value
          )
        else Nil
      scalaXml +: otherLibs
    }
    lazy val compilerOptions = Def.setting(
      warnUnusedImports.value ++
        targetJvm.value ++
        Seq(
          "-encoding",
          "UTF-8",
          "-feature",
          "-unchecked"
        )
    )

    lazy val semanticdbSyntheticsCompilerOption = Def.setting(
      if (!isScala3.value)
        Seq("-P:semanticdb:synthetics:on")
      else Nil
    )

    lazy val buildInfoSettingsForCore: Seq[Def.Setting[_]] = Seq(
      buildInfoKeys := Seq[BuildInfoKey](
        name,
        version,
        stableVersion,
        "coursier" -> coursierV,
        "nightly" -> version.value,
        "scalameta" -> scalametaV,
        scalaVersion,
        "supportedScalaVersions" -> supportedScalaVersions,
        "scala212" -> scala212,
        "scala213" -> scala213,
        sbtVersion
      ),
      buildInfoPackage := "scalafix",
      buildInfoObject := "Versions"
    )

    lazy val buildInfoSettingsForRules: Seq[Def.Setting[_]] = Seq(
      buildInfoObject := "RulesBuildInfo"
    )

    lazy val scalatestDep = Def.setting {
      if (isScala3.value) scalatest.withRevision(scalatestLatestV)
      else scalatest
    }
  }

  import autoImport._

  override def globalSettings: Seq[Def.Setting[_]] = List(
    stableVersion := (ThisBuild / version).value.replaceFirst("\\+.*", ""),
    resolvers ++=
      Resolver.sonatypeOssRepos("snapshots") ++
        Resolver.sonatypeOssRepos("public") :+
        Resolver.mavenLocal,
    Test / testOptions += Tests.Argument("-oD"),
    updateOptions := updateOptions.value.withCachedResolution(true),
    ThisBuild / watchTriggeredMessage := Watch.clearScreenOnTrigger,
    commands += Command.command("save-expect") { s =>
      "unit2_13Target2_13/test:runMain scalafix.tests.util.SaveExpect" ::
        "unit3Target3/test:runMain scalafix.tests.util.SaveExpect" ::
        s
    },
    commands += Command.command("ci-3") { s =>
      "unit2_12Target3/test" ::
        "unit3Target3/test" ::
        s
    },
    commands += Command.command("ci-213") { s =>
      "unit2_13Target2_13/test" ::
        "docs2_13/run" ::
        "interfaces/doc" ::
        testRulesAgainstPreviousScalaVersions(scala213, s)
    },
    commands += Command.command("ci-212") { s =>
      "unit2_12Target2_12/test" ::
        testRulesAgainstPreviousScalaVersions(scala212, s)
    },
    commands += Command.command("ci-213-windows") { s =>
      "publishLocalTransitive" :: // scalafix.tests.interfaces.ScalafixSuite
        s"unit2_13Target2_13/testOnly -- -l scalafix.internal.tests.utils.SkipWindows" ::
        s
    },
    // There is flakyness in CliGitDiffTests and CliSemanticTests
    Test / parallelExecution := false,
    Test / publishArtifact := false,
    licenses := Seq(
      "Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")
    ),
    homepage := Some(url("https://github.com/scalacenter/scalafix")),
    autoAPIMappings := true,
    apiURL := Some(url("https://scalacenter.github.io/scalafix/")),
    organization := "ch.epfl.scala",
    developers ++= Developers.list
  )

  private val PreviousScalaVersion: Map[String, String] = Map(
  )

  override def buildSettings: Seq[Setting[_]] = List(
    // https://github.com/sbt/sbt/issues/5568#issuecomment-1094380636
    versionPolicyIgnored += "com.lihaoyi" %% "pprint",
    versionPolicyIgnoredInternalDependencyVersions :=
      Some("^\\d+\\.\\d+\\.\\d+\\+\\d+".r),
    versionScheme := Some("early-semver"),
    // coursier-versions always return false for the *.*.*.*-r pattern jgit uses
    libraryDependencySchemes += Dependencies.jgit.withRevision("always"),
    // https://github.com/scala/bug/issues/12632
    libraryDependencySchemes += "org.scala-lang.modules" %% "scala-xml" % "always",
    // silence warning for 0.10.x -> 0.11.x (no actual breaking change)
    // TODO: remove after 0.10.5
    libraryDependencySchemes += "com.geirsson" %% "metaconfig-core" % "always",
    libraryDependencySchemes += "com.geirsson" %% "metaconfig-pprint" % "always",
    libraryDependencySchemes += "com.geirsson" %% "metaconfig-typesafe-config" % "always"
  )

  override def projectSettings: Seq[Def.Setting[_]] = List(
    // avoid "missing dependency" on artifacts with full scala version when bumping scala
    versionPolicyIgnored ++= {
      PreviousScalaVersion.get(scalaVersion.value) match {
        case Some(previous) =>
          // all transitive dependencies with full scala version we know about
          Seq(
            "org.scalameta" % s"semanticdb-scalac-core_$previous",
            "ch.epfl.scala" % s"scalafix-cli_$previous",
            "ch.epfl.scala" % s"scalafix-reflect_$previous"
          )
        case None => Seq()
      }
    },
    // don't publish scala 3 artifacts for now
    publish / skip := (if ((publish / skip).value) true
                       else scalaBinaryVersion.value == "3"),
    versionPolicyIntention := Compatibility.BinaryCompatible,
    scalacOptions ++= compilerOptions.value,
    scalacOptions ++= semanticdbSyntheticsCompilerOption.value,
    Compile / console / scalacOptions :=
      compilerOptions.value :+ "-Yrepl-class-based",
    Compile / doc / scalacOptions ++= scaladocOptions,
    Compile / unmanagedResourceDirectories ++= {
      val resourceParentDir = (Compile / resourceDirectory).value.getParentFile
      CrossVersion.partialVersion(scalaVersion.value) match {
        case Some((major, _)) => Seq(resourceParentDir / s"resources-${major}")
        case _ => Seq()
      }
    },
    // Don't package sources & docs when publishing locally as it adds a significant
    // overhead when testing because of publishLocalTransitive. Tweaking publishArtifact
    // would more readable, but it would also affect remote (sonatype) publishing.
    publishLocal / packagedArtifacts :=
      Classpaths.packaged(Seq(Compile / packageBin)).value,
    scmInfo := Some(
      ScmInfo(
        url("https://github.com/scalacenter/scalafix"),
        "scm:git:git@github.com:scalacenter/scalafix.git"
      )
    ),
    mimaPreviousArtifacts := {
      val currentScalaFullV = scalaVersion.value
      val previousScalaFullV =
        PreviousScalaVersion.getOrElse(currentScalaFullV, currentScalaFullV)
      val previousScalaVCrossName = CrossVersion(
        crossVersion.value,
        previousScalaFullV,
        scalaBinaryVersion.value
      ).getOrElse(identity[String] _)(moduleName.value)
      Set(
        organizationName.value % previousScalaVCrossName % stableVersion.value
      )
    },
    mimaBinaryIssueFilters ++= Mima.ignoredABIProblems
  ) ++ Seq(Compile, Test).flatMap(conf => inConfig(conf)(configSettings))

  private def configSettings: Seq[Def.Setting[_]] = List(
    // Workaround for https://github.com/scalacenter/scalafix/issues/1592:
    // effectively skip scalafix[All] if the scala binary version of the project
    // does not match the scalafix one (which cannot be per-project as it must be
    // defined on ThisBuild)
    scalafix / unmanagedSources := {
      val prev = (scalafix / unmanagedSources).value
      if (scalaBinaryVersion.value == scalafixScalaBinaryVersion.value) {
        prev
      } else {
        Seq()
      }
    }
  )

  private def testRulesAgainstPreviousScalaVersions(
      scalaVersion: String,
      state: State
  ): State = {
    val projectSuffix = scalaVersion.split('.').take(2).mkString("_")
    testedPreviousScalaVersions
      .getOrElse(scalaVersion, Nil)
      .flatMap { v =>
        List(
          s"""set Project("testsInput${projectSuffix}", file(".")) / scalaVersion := "$v"""",
          s"show testsInput${projectSuffix} / scalaVersion",
          s"unit${projectSuffix}Target${projectSuffix} / testOnly scalafix.tests.rule.RuleSuite"
        )
      }
      .foldRight(state)(_ :: _)
  }
}
