import sbt._
import scala.util.Try

/* scalafmt: { maxColumn = 120 }*/

object Dependencies {
  val scala211 = "2.11.12"
  val scala212 = "2.12.13"
  val scala213 = "2.13.6"
  val scala3 = "3.0.0-RC3"
  // we support 3 last binary versions of scala212 and scala213
  val testedPreviousScalaVersions: Map[String, List[String]] =
    List(scala213, scala212).map(version => version -> previousVersions(version)).toMap

  val bijectionCoreV = "0.9.7"
  val collectionCompatV = "2.4.4"
  val coursierV = "2.0.0-RC5-6"
  val coursierInterfaceV = "1.0.4"
  val commontTextV = "1.9"
  val googleDiffV = "1.3.0"
  val java8CompatV = "0.9.0"
  val jgitV = "5.11.1.202105131744-r"
  val metaconfigFor211V = "0.9.10" // metaconfig stops publishing for scala 2.11
  val metaconfigV = "0.9.11"
  val nailgunV = "0.9.1"
  val scalaXmlV = "1.3.0"
  val scalametaV = "4.4.18"
  val scalatestV = "3.0.8" // don't bump, to avoid forcing breaking changes on clients via eviction

  val bijectionCore = "com.twitter" %% "bijection-core" % bijectionCoreV
  val collectionCompat = "org.scala-lang.modules" %% "scala-collection-compat" % collectionCompatV
  val commonText = "org.apache.commons" % "commons-text" % commontTextV
  val coursier = "io.get-coursier" %% "coursier" % coursierV
  val coursierInterfaces = "io.get-coursier" % "interface" % coursierInterfaceV
  val googleDiff = "com.googlecode.java-diff-utils" % "diffutils" % googleDiffV
  val java8Compat = "org.scala-lang.modules" %% "scala-java8-compat" % java8CompatV
  val jgit = "org.eclipse.jgit" % "org.eclipse.jgit" % jgitV
  val metaconfigFor211 = "com.geirsson" %% "metaconfig-typesafe-config" % metaconfigFor211V
  val metaconfig = "com.geirsson" %% "metaconfig-typesafe-config" % metaconfigV
  val metaconfigDocFor211 = "com.geirsson" %% "metaconfig-docs" % metaconfigFor211V
  val metaconfigDoc = "com.geirsson" %% "metaconfig-docs" % metaconfigV
  val metacp = "org.scalameta" %% "metacp" % scalametaV
  val nailgunServer = "com.martiansoftware" % "nailgun-server" % nailgunV
  val scalaXml = "org.scala-lang.modules" %% "scala-xml" % scalaXmlV
  val scalameta = "org.scalameta" %% "scalameta" % scalametaV
  val scalametaTeskit = "org.scalameta" %% "testkit" % scalametaV
  val scalatest = "org.scalatest" %% "scalatest" % scalatestV
  val semanticdbScalacCore = "org.scalameta" % "semanticdb-scalac-core" % scalametaV cross CrossVersion.full

  def testsDeps = List(
    scalaXml,
    bijectionCore
  )

  private def previousVersions(scalaVersion: String): List[String] = {
    val split = scalaVersion.split('.')
    val binaryVersion = split.take(2).mkString(".")
    val compilerVersion = Try(split.last.toInt).toOption
    val previousPatchVersions =
      compilerVersion.map(version => List.range(version - 2, version).filter(_ >= 0)).getOrElse(Nil)
    previousPatchVersions.map(v => s"$binaryVersion.$v")
  }
}
