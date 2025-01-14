import ScalafixBuild.autoImport.isScala2
import sbt.Keys.scalaVersion
import sbt._

/* scalafmt: { maxColumn = 140 }*/

object Dependencies {
  val scala212 = sys.props.getOrElse("scala212.nightly", "2.12.20")
  val scala213 = sys.props.getOrElse("scala213.nightly", "2.13.16")
  val scala33 = "3.3.4"
  val scala36 = "3.6.2"
  val scala3LTS = scala33
  val scala3Next = sys.props.getOrElse("scala3.nightly", scala36)

  val bijectionCoreV = "0.9.8"
  val collectionCompatV = "2.12.0"
  val coursierV = "2.1.23"
  val coursierInterfaceV = "1.0.26"
  val commontTextV = "1.13.0"
  val googleDiffV = "1.3.0"
  val jgitV = "5.13.3.202401111512-r"
  val metaconfigV = "0.14.0"
  val nailgunV = "0.9.1"
  val scalaXmlV = "2.2.0"
  val scalametaV = "4.12.5"
  val scalatagsV = "0.13.1"
  val scalatestV = "3.2.19"
  val munitV = "1.0.3"

  val bijectionCore = "com.twitter" %% "bijection-core" % bijectionCoreV
  val collectionCompat = "org.scala-lang.modules" %% "scala-collection-compat" % collectionCompatV
  val commonText = "org.apache.commons" % "commons-text" % commontTextV
  val coursierFor3Use2_13 = "io.get-coursier" %% "coursier" % coursierV cross CrossVersion.for3Use2_13
  val coursierInterfaces = "io.get-coursier" % "interface" % coursierInterfaceV
  val googleDiff = "com.googlecode.java-diff-utils" % "diffutils" % googleDiffV
  val jgit = "org.eclipse.jgit" % "org.eclipse.jgit" % jgitV
  val metaconfig = "org.scalameta" %% "metaconfig-typesafe-config" % metaconfigV
  val metacp = "org.scalameta" %% "metacp" % scalametaV
  val nailgunServer = "com.martiansoftware" % "nailgun-server" % nailgunV
  val scalaXml = "org.scala-lang.modules" %% "scala-xml" % scalaXmlV
  val scalametaFor3Use2_13 = "org.scalameta" %% "scalameta" % scalametaV cross CrossVersion.for3Use2_13
  val scalametaTeskitFor3Use2_13 = "org.scalameta" %% "testkit" % scalametaV cross CrossVersion.for3Use2_13
  val scalatags = "com.lihaoyi" %% "scalatags" % scalatagsV
  val scalatest = "org.scalatest" %% "scalatest" % scalatestV
  val munit = "org.scalameta" %% "munit" % munitV
  val semanticdbScalacCore = "org.scalameta" % "semanticdb-scalac-core" % scalametaV cross CrossVersion.full
  val semanticdbSharedFor3Use2_13 = "org.scalameta" % "semanticdb-shared" % scalametaV cross CrossVersion.for3Use2_13

  // scala-steward:off

  // Now shaded in dependencies, kept here only for backward compatbility
  val pprint = "com.lihaoyi" %% "pprint" % "0.6.6"
  val scalametaFastparse = "org.scalameta" %% "fastparse-v2" % "2.3.1"
  val geny = "com.lihaoyi" %% "geny" % "0.6.5"
}
