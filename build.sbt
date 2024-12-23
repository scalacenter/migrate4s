import Dependencies._
import TargetAxis.TargetProjectMatrix

inThisBuild(
  List(
    onLoadMessage := s"Welcome to scalafix ${version.value}",
    semanticdbEnabled := true,
    semanticdbVersion := scalametaV
  )
)

Global / cancelable := true
noPublishAndNoMima

// force javac to fork by setting javaHome to get error messages during compilation,
// see https://github.com/sbt/zinc/issues/520
def inferJavaHome() = {
  val home = file(sys.props("java.home"))
  val actualHome =
    if (System.getProperty("java.version").startsWith("1.8")) home.getParentFile
    else home
  Some(actualHome)
}

lazy val interfaces = project
  .in(file("scalafix-interfaces"))
  .settings(
    Compile / resourceGenerators += Def.task {
      val props = new java.util.Properties()
      props.put("scalafixVersion", version.value)
      props.put("scalafixStableVersion", stableVersion.value)
      props.put("scalametaVersion", scalametaV)
      props.put("scala212", scala212)
      props.put("scala213", scala213)
      props.put("scala33", scala33)
      props.put("scala36", scala36)
      props.put("scala3LTS", scala3LTS)
      props.put("scala3Next", scala3Next)
      val out =
        (Compile / managedResourceDirectories).value.head /
          "scalafix-interfaces.properties"
      IO.write(props, "Scalafix version constants", out)
      List(out)
    },
    (Compile / javacOptions) ++= List(
      "-Xlint:all",
      "-Werror"
    ),
    (Compile / doc / javacOptions) := List("-Xdoclint:none"),
    (Compile / javaHome) := inferJavaHome(),
    (Compile / doc / javaHome) := inferJavaHome(),
    libraryDependencies += coursierInterfaces,
    moduleName := "scalafix-interfaces",
    crossPaths := false,
    autoScalaLibrary := false
  )
  .disablePlugins(ScalafixPlugin)

// Scala 3 macros vendored separately (i.e. without runtime classes), to
// shadow Scala 2.13 macros in the Scala 3 compiler classpath, while producing
// code valid against Scala 2.13 bytecode
lazy val `compat-metaconfig-macros` = projectMatrix
  .settings(
    libraryDependencies += metaconfig cross CrossVersion.for3Use2_13
  )
  .defaultAxes(VirtualAxis.jvm)
  .jvmPlatform(cliScalaVersions)
  .disablePlugins(ScalafixPlugin)

lazy val core = projectMatrix
  .in(file("scalafix-core"))
  .settings(
    moduleName := "scalafix-core",
    buildInfoSettingsForCore,
    libraryDependencies ++= Seq(
      googleDiff,
      metaconfig,
      scalametaFor3Use2_13,
      semanticdbSharedFor3Use2_13,
      collectionCompat
    )
  )
  .defaultAxes(VirtualAxis.jvm)
  .jvmPlatform(coreScalaVersions)
  .enablePlugins(BuildInfoPlugin)

// keep compiling core3 without exposing it to matrix projects, just
// to make https://github.com/scalacenter/scalafix/issues/2041 easier
lazy val core3 = project
  .in(file("scalafix-core"))
  .settings(
    noPublishAndNoMima,
    buildInfoSettingsForCore,
    scalaVersion := scala3LTS,
    libraryDependencies ++= Seq(
      googleDiff,
      metaconfig
    ) ++ Seq(
      scalametaFor3Use2_13,
      semanticdbSharedFor3Use2_13
    ).map { mod =>
      mod
        .exclude("com.lihaoyi", "sourcecode_2.13")
        .exclude("org.scala-lang.modules", "scala-collection-compat_2.13")
    }
  )
  .enablePlugins(BuildInfoPlugin)

lazy val rules = projectMatrix
  .in(file("scalafix-rules"))
  .settings(
    moduleName := "scalafix-rules",
    description := "Built-in Scalafix rules",
    isFullCrossVersion,
    buildInfoSettingsForRules,
    libraryDependencies ++= {
      if (!isScala3.value)
        Seq(
          "org.scala-lang" % "scala-compiler" % scalaVersion.value,
          "org.scala-lang" % "scala-reflect" % scalaVersion.value,
          semanticdbScalacCore,
          collectionCompat
        )
      else
        Seq(
          "org.scala-lang" %% "scala3-presentation-compiler" % scalaVersion.value,
          coursierInterfaces
        )
    }
  )
  .defaultAxes(VirtualAxis.jvm)
  .jvmPlatformFull(cliScalaVersions)
  .dependsOn(`compat-metaconfig-macros` % "provided")
  .dependsOn(core)
  .enablePlugins(BuildInfoPlugin)

lazy val reflect = projectMatrix
  .in(file("scalafix-reflect"))
  .settings(
    moduleName := "scalafix-reflect",
    isFullCrossVersion,
    libraryDependencies ++= Seq(
      semanticdbScalacCore,
      "org.scala-lang" % "scala-compiler" % scalaVersion.value,
      "org.scala-lang" % "scala-reflect" % scalaVersion.value
    )
  )
  .defaultAxes(VirtualAxis.jvm)
  .jvmPlatform(coreScalaVersions)
  .dependsOn(core)

// keep compiling reflect3 without exposing it to matrix projects, just
// to make https://github.com/scalacenter/scalafix/issues/2041 easier
lazy val reflect3 = project
  .in(file("scalafix-reflect"))
  .settings(
    isFullCrossVersion,
    noPublishAndNoMima,
    scalaVersion := scala3LTS,
    libraryDependencies ++= Seq(
      // CrossVersion.for3Use2_13 would only lookup a binary version artifact, but this is published with full version
      semanticdbScalacCore
        .cross(CrossVersion.constant(scala213))
        .exclude("com.lihaoyi", "sourcecode_2.13")
        .exclude("org.scala-lang.modules", "scala-collection-compat_2.13"),
      "org.scala-lang" %% "scala3-compiler" % scalaVersion.value
    )
  )
  .dependsOn(core3)

lazy val cli = projectMatrix
  .in(file("scalafix-cli"))
  .settings(
    moduleName := "scalafix-cli",
    isFullCrossVersion,
    libraryDependencies ++= Seq(
      nailgunServer,
      jgit,
      commonText
    ),
    libraryDependencies ++= {
      if (isScala3.value) Seq()
      else
        // Rules built with an old scalafix-core may need packages that
        // disappeared from the classpath to link
        Seq(
          // metaconfig 0.10.0 shaded pprint
          // https://github.com/scalameta/metaconfig/pull/154/files#r794005161
          pprint,
          // scalameta 4.8.3 shaded fastparse and geny
          // https://github.com/scalameta/scalameta/pull/3246
          scalametaFastparse,
          geny
        ).map(_ % Runtime)
    },
    // companion of `.dependsOn(reflect)`
    // issue reported in https://github.com/sbt/sbt/issues/7405
    // using workaround from https://github.com/sbt/sbt/issues/5369#issue-549758513
    // https://github.com/sbt/sbt-projectmatrix/pull/97 only fixed dependencies to binary versions
    projectDependencies := {
      projectDependencies.value.map {
        case reflect
            if reflect.name == "scalafix-reflect" && scalaBinaryVersion.value == "3" =>
          reflect
            .withName(s"scalafix-reflect_${scala213}")
            .withCrossVersion(CrossVersion.disabled)
        case dep =>
          dep
      }
    },
    publishLocalTransitive := Def.taskDyn {
      val ref = thisProjectRef.value
      publishLocal.all(ScopeFilter(inDependencies(ref)))
    }.value
  )
  .defaultAxes(VirtualAxis.jvm)
  .jvmPlatformFull(cliScalaVersions)
  .dependsOn(interfaces)
  .dependsOn(`compat-metaconfig-macros` % "provided")
  .dependsOn(reflect, rules)

lazy val testkit = projectMatrix
  .in(file("scalafix-testkit"))
  .settings(
    moduleName := "scalafix-testkit",
    isFullCrossVersion,
    libraryDependencies ++= Seq(
      googleDiff,
      scalatest
    )
  )
  .defaultAxes(VirtualAxis.jvm)
  .jvmPlatformFull(cliScalaVersions)
  .dependsOn(cli)

lazy val shared = projectMatrix
  .in(file("scalafix-tests/shared"))
  .settings(
    noPublishAndNoMima,
    coverageEnabled := false
  )
  .defaultAxes(VirtualAxis.jvm)
  // just compile shared with the latest patch for each minor Scala version
  // to reduce the cardinality of the build
  .jvmPlatform(cliScalaVersions)
  .disablePlugins(ScalafixPlugin)

lazy val input = projectMatrix
  .in(file("scalafix-tests/input"))
  .settings(
    noPublishAndNoMima,
    scalacOptions ~= (_.filterNot(_ == "-Yno-adapted-args")),
    scalacOptions ++= warnAdaptedArgs.value, // For NoAutoTupling
    logLevel := Level.Error, // avoid flood of compiler warnings
    libraryDependencies ++= testsDependencies.value,
    coverageEnabled := false,
    // mimic dependsOn(shared) but allowing binary Scala version matching
    Compile / internalDependencyClasspath ++=
      resolve(shared, Compile / exportedProducts).value
  )
  .defaultAxes(VirtualAxis.jvm)
  .jvmPlatformTargets(cliScalaVersionsWithTargets.map(_._2))
  .disablePlugins(ScalafixPlugin)

lazy val output = projectMatrix
  .in(file("scalafix-tests/output"))
  .settings(
    noPublishAndNoMima,
    logLevel := Level.Error, // avoid flood of compiler warnings
    libraryDependencies ++= testsDependencies.value,
    coverageEnabled := false,
    // mimic dependsOn(shared) but allowing binary Scala version matching
    Compile / internalDependencyClasspath ++=
      resolve(shared, Compile / exportedProducts).value
  )
  .defaultAxes(VirtualAxis.jvm)
  .jvmPlatformTargets(
    cliScalaVersionsWithTargets
      .map(_._2)
      // don't compile output with old Scala patch versions to reduce the
      // cardinality of the build: checking that it compiles with the
      // latest patch of each minor Scala version is enough
      .filter(axis => cliScalaVersions.contains(axis.scalaVersion))
  )
  .disablePlugins(ScalafixPlugin)

lazy val unit = projectMatrix
  .in(file("scalafix-tests/unit"))
  .settings(
    noPublishAndNoMima,
    libraryDependencies ++= Seq(
      jgit,
      munit,
      scalatest
    ),
    libraryDependencies += {
      if (!isScala3.value) {
        scalametaTeskitFor3Use2_13
      } else {
        // exclude _2.13 artifacts that have their _3 counterpart in the classpath
        scalametaTeskitFor3Use2_13
          .exclude("org.scalameta", "munit_2.13")
      }
    },
    buildInfoPackage := "scalafix.tests",
    buildInfoKeys := Seq[BuildInfoKey](
      "scalaVersion" -> scalaVersion.value
    )
  )
  .defaultAxes(VirtualAxis.jvm)
  .jvmPlatformFull(cliScalaVersions)
  .enablePlugins(BuildInfoPlugin)
  .dependsOn(testkit % Test)

lazy val integration = projectMatrix
  .in(file("scalafix-tests/integration"))
  .settings(
    noPublishAndNoMima,
    Test / parallelExecution := false,
    libraryDependencies ++= {
      if (!isScala3.value) {
        Seq(
          coursierFor3Use2_13
        )
      } else {
        Seq(
          "org.scala-lang" %% "scala3-compiler" % scalaVersion.value,
          // exclude _2.13 artifacts that have their _3 counterpart in the classpath
          coursierFor3Use2_13
            .exclude("org.scala-lang.modules", "scala-xml_2.13")
        )
      }
    },
    buildInfoPackage := "scalafix.tests",
    buildInfoObject := "BuildInfo",
    // create a local alias for input / Compile / fullClasspath at an
    // arbitrary, unused scope to be able to reference it (as a TaskKey) in
    // buildInfoKeys (since the macro only accepts TaskKeys)
    buildInfoKeys / fullClasspath :=
      resolve(input, Compile / fullClasspath).value,
    buildInfoKeys := Seq[BuildInfoKey](
      "scalametaVersion" -> scalametaV,
      "scalaVersion" -> scalaVersion.value,
      "baseDirectory" ->
        (ThisBuild / baseDirectory).value,
      "resourceDirectory" ->
        (Compile / resourceDirectory).value,
      "semanticClasspath" ->
        Seq((Compile / semanticdbTargetRoot).value),
      "sourceroot" ->
        (Compile / sourceDirectory).value,
      "classDirectory" ->
        (Compile / classDirectory).value,
      BuildInfoKey.map(buildInfoKeys / fullClasspath) { case (_, v) =>
        "inputClasspath" -> v
      },
      "inputSemanticClasspath" ->
        Seq(resolve(input, Compile / semanticdbTargetRoot).value),
      "inputSourceroot" ->
        resolve(input, Compile / sourceDirectory).value,
      "outputSourceroot" ->
        resolve(output, Compile / sourceDirectory).value
    ),
    Test / test := (Test / test)
      .dependsOn(
        (resolve(cli, publishLocalTransitive) +: cli.projectRefs
          // always publish Scala 3 artifacts to test Scala 3 minor version fallbacks
          .collect { case p @ LocalProject(n) if n.startsWith("cli3") => p }
          .map(_ / publishLocalTransitive)): _*
      )
      .value,
    Test / testWindows := (Test / testWindows)
      .dependsOn(
        (resolve(cli, publishLocalTransitive) +: cli.projectRefs
          // always publish Scala 3 artifacts to test Scala 3 minor version fallbacks
          .collect { case p @ LocalProject(n) if n.startsWith("cli3") => p }
          .map(_ / publishLocalTransitive)): _*
      )
      .value
  )
  .defaultAxes(VirtualAxis.jvm)
  .jvmPlatformFull(cliScalaVersions)
  .enablePlugins(BuildInfoPlugin)
  .dependsOn(unit % "compile->test")

lazy val expect = projectMatrix
  .in(file("scalafix-tests/expect"))
  .settings(
    noPublishAndNoMima,
    Test / resourceGenerators += Def.task {
      // make sure the output can be compiled
      val _ = TargetAxis.resolve(output, Compile / compile).value

      // copy-pasted code from ScalafixTestkitPlugin to avoid cyclic dependencies between build and sbt-scalafix.
      val props = new java.util.Properties()
      def put(key: String, files: Seq[File]): Unit = {
        val value = files.iterator
          .filter(_.exists())
          .mkString(java.io.File.pathSeparator)
        props.put(key, value)
      }
      put(
        "inputClasspath",
        TargetAxis
          .resolve(input, Compile / fullClasspath)
          .value
          .map(_.data)
      )
      put(
        "inputSourceDirectories",
        TargetAxis
          .resolve(input, Compile / unmanagedSourceDirectories)
          .value
      )
      put(
        "outputSourceDirectories",
        TargetAxis
          .resolve(output, Compile / unmanagedSourceDirectories)
          .value
      )
      props.put(
        "scalaVersion",
        TargetAxis.resolve(input, Compile / scalaVersion).value
      )
      props.put(
        "scalacOptions",
        TargetAxis
          .resolve(input, Compile / scalacOptions)
          .value
          .mkString("|")
      )
      val out =
        (Test / managedResourceDirectories).value.head /
          "scalafix-testkit.properties"
      IO.write(props, "Input data for scalafix testkit", out)
      List(out)
    }
  )
  .defaultAxes(VirtualAxis.jvm)
  .jvmPlatformAgainstTargets(cliScalaVersionsWithTargets)
  .dependsOn(integration)

lazy val docs = projectMatrix
  .in(file("scalafix-docs"))
  .settings(
    noPublishAndNoMima,
    fork := true,
    run / baseDirectory := (ThisBuild / baseDirectory).value,
    moduleName := "scalafix-docs",
    scalacOptions += "-Wconf:msg='match may not be exhaustive':s", // silence exhaustive pattern matching warning for documentation
    scalacOptions += "-Xfatal-warnings",
    mdoc := (Compile / run).evaluated,
    libraryDependencies += scalatags,
    dependencyOverrides += scalametaFor3Use2_13 // force eviction of mdoc transitive dependency
  )
  .defaultAxes(VirtualAxis.jvm)
  .jvmPlatform(scalaVersions = Seq(scala213))
  .dependsOn(testkit, core, cli)
  .enablePlugins(DocusaurusPlugin)
  .disablePlugins(ScalafixPlugin)
