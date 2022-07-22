package scalafix.tests.cli

import scalafix.cli._

class CliSyntacticSuite extends BaseCliSuite {

  check(
    name = "--help",
    originalLayout = "",
    args = Array("--help"),
    expectedLayout = "",
    expectedExit = ExitStatus.Ok,
    outputAssert = { out =>
      assert(out.startsWith("Scalafix"))
      assert(out.contains("--rules"))
    }
  )

  check(
    name = "--zsh",
    originalLayout = "",
    args = Array("--zsh"),
    expectedLayout = "",
    expectedExit = ExitStatus.Ok,
    outputAssert = { out =>
      assert(out.contains("local -a scalafix_opts"))
    }
  )

  check(
    name = "--bash",
    originalLayout = "",
    args = Array("--bash"),
    expectedLayout = "",
    expectedExit = ExitStatus.Ok,
    outputAssert = { out =>
      assert(out.contains("complete -F _scalafix scalafix"))
    }
  )

  check(
    name = "fix file",
    originalLayout = s"""/hello.scala
      |$original
      |""".stripMargin,
    args = Array("-r", "ProcedureSyntax", "hello.scala"),
    expectedLayout = s"""/hello.scala
      |$expected
      |""".stripMargin,
    expectedExit = ExitStatus.Ok
  )

  check(
    name = "--syntactic ignores semantic rule",
    originalLayout = s"""|
      |/.scalafix.conf
      |rules = [
      |  RemoveUnused
      |  ExplicitResultTypes
      |  ProcedureSyntax
      |]
      |/hello.scala
      |$original
      |""".stripMargin,
    args = Array("--syntactic", "hello.scala"),
    expectedLayout = s"""|
      |/.scalafix.conf
      |rules = [
      |  RemoveUnused
      |  ExplicitResultTypes
      |  ProcedureSyntax
      |]
      |/hello.scala
      |$expected
      |""".stripMargin,
    expectedExit = ExitStatus.Ok
  )

  check(
    // same test as above except using @args expansion
    name = "@args",
    originalLayout = s"""/hello.scala
      |$original
      |/scalafix.args
      |-r
      |ProcedureSyntax
      |hello.scala
      |""".stripMargin,
    args = Array("@scalafix.args"),
    expectedLayout = s"""/hello.scala
      |$expected
      |/scalafix.args
      |-r
      |ProcedureSyntax
      |hello.scala
      |""".stripMargin,
    expectedExit = ExitStatus.Ok
  )

  check(
    name = "fix directory",
    originalLayout = s"""|/dir/a.scala
      |$original
      |/dir/b.scala
      |$original""".stripMargin,
    args = Array(
      "-r",
      "ProcedureSyntax",
      "dir"
    ),
    expectedLayout = s"""|/dir/a.scala
      |$expected
      |/dir/b.scala
      |$expected""".stripMargin,
    expectedExit = ExitStatus.Ok
  )

  check(
    name = "file not found",
    originalLayout = s"/foobar.scala\n",
    args = Array("-r", "ProcedureSyntax", "unknown-file.scala"),
    expectedLayout = "/foobar.scala",
    expectedExit = ExitStatus.UnexpectedError
  )

  check(
    name = "empty rule",
    originalLayout = s"/foobar.scala\n",
    args = Array("foobar.scala"),
    expectedLayout = "/foobar.scala",
    expectedExit = ExitStatus.NoRulesError
  )

  check(
    name = "TestError",
    originalLayout = s"""/foobar.scala
      |$original""".stripMargin,
    args = Array("--check", "-r", "ProcedureSyntax", "foobar.scala"),
    expectedLayout = s"""/foobar.scala
      |$original""".stripMargin,
    expectedExit = ExitStatus.TestError,
    outputAssert = { out =>
      assert(
        out.endsWith(
          """|<expected fix>
            |@@ -1,4 +1,4 @@
            | object Main {
            |-  def foo() {
            |+  def foo(): Unit = {
            |   }
            | }
            |""".stripMargin
        )
      )
    }
  )

  check(
    name = "--check OK",
    originalLayout = s"""/foobar.scala
      |$expected""".stripMargin,
    args = Array("--check", "-r", "ProcedureSyntax", "foobar.scala"),
    expectedLayout = s"""/foobar.scala
      |$expected""".stripMargin,
    expectedExit = ExitStatus.Ok
  )

  check(
    name = "--triggered is respected",
    originalLayout = s"""|
      |/.scalafix.conf
      |rules = [
      |  RemoveUnused
      |]
      |
      |triggered.rules = [ProcedureSyntax]
      |
      |/hello.scala
      |$original
      |""".stripMargin,
    args = Array("--triggered", "hello.scala"),
    expectedLayout = s"""|
      |/.scalafix.conf
      |rules = [
      |  RemoveUnused
      |]
      |
      |triggered.rules = [ProcedureSyntax]
      |
      |/hello.scala
      |$expected
      |""".stripMargin,
    expectedExit = ExitStatus.Ok
  )

  check(
    name = "linter error",
    originalLayout = s"""/foobar.scala
      |$original""".stripMargin,
    args = Array(
      "-r",
      "scala:scalafix.tests.cli.LintError",
      "foobar.scala"
    ),
    expectedLayout = s"""/foobar.scala
      |$original""".stripMargin,
    expectedExit = ExitStatus.LinterError,
    outputAssert = { out =>
      assert(out.contains("Error!"))
    }
  )

  check(
    name = "linter warning promoted to error",
    originalLayout = s"""/foobar.scala
      |$original""".stripMargin,
    args = Array(
      "--settings.lint.error",
      "LintWarning.warning",
      "-r",
      "scala:scalafix.tests.cli.LintWarning",
      "foobar.scala"
    ),
    expectedLayout = s"""/foobar.scala
      |$original""".stripMargin,
    expectedExit = ExitStatus.LinterError,
    outputAssert = { out =>
      assert(out.contains("foobar.scala:1:1"))
    }
  )

  check(
    name = "--exclude is respected",
    originalLayout = s"""|/ignoreme.scala
      |$original
      |/fixme.scala
      |$original""".stripMargin,
    args = Array(
      "--exclude",
      "**ignoreme.scala",
      "-r",
      "ProcedureSyntax",
      "ignoreme.scala",
      "fixme.scala"
    ),
    expectedLayout = s"""|/fixme.scala
      |$expected
      |/ignoreme.scala
      |$original""".stripMargin,
    expectedExit = ExitStatus.Ok
  )

  check(
    name = "--stdout does not write to file",
    originalLayout = s"""|/a.scala
      |$original
      |""".stripMargin,
    args = Array(
      "--stdout",
      "-r",
      "ProcedureSyntax",
      "a.scala"
    ),
    expectedLayout = s"""|/a.scala
      |$original""".stripMargin,
    expectedExit = ExitStatus.Ok,
    output => assertNoDiff(output, expected)
  )

  check(
    name = "ParseError",
    originalLayout = s"""|/a.scala
      |objec bar
      |""".stripMargin,
    args = Array(
      "-r",
      "ProcedureSyntax",
      "a.scala"
    ),
    expectedLayout = s"""|/a.scala
      |objec bar""".stripMargin,
    expectedExit = ExitStatus.ParseError,
    outputAssert = { out =>
      assert(
        out.endsWith(
          """|a.scala:1:1: error: illegal start of definition identifier
            |objec bar
            |^^^^^
            |""".stripMargin
        )
      )
    }
  )

  check(
    name = "fix sbt files",
    originalLayout = s"""|/a.sbt
      |def foo { println(1) }
      |lazy val bar = project
      |""".stripMargin,
    args = Array(
      "-r",
      "ProcedureSyntax",
      "a.sbt"
    ),
    expectedLayout = s"""|/a.sbt
      |def foo: Unit = { println(1) }
      |lazy val bar = project
      |""".stripMargin,
    expectedExit = ExitStatus.Ok
  )

  check(
    name = "deprecated name emits warning",
    originalLayout = s"""|/a.scala
      |object a {
      |}
      |""".stripMargin,
    args = Array(
      "-r",
      "OldDeprecatedName", // class:scalafix.tests.cli.DeprecatedName
      "a.scala"
    ),
    expectedLayout = s"""|/a.scala
      |object a {
      |}
      |""".stripMargin,
    expectedExit = ExitStatus.Ok,
    output => assert(output.contains("Use DeprecatedName instead"))
  )

  check(
    name = "no files to fix is error",
    originalLayout = s"""|/dir/a.java
      |package a;
      |class A
      |""".stripMargin,
    args = Array(
      "-r",
      "ProcedureSyntax",
      "dir"
    ),
    expectedLayout = s"""|/dir/a.java
      |package a;
      |class A
      |""".stripMargin,
    expectedExit = ExitStatus.NoFilesError,
    { output =>
      assert(output.contains("No files to fix"))
    }
  )

  check(
    name = "--out-from --out-to change output path",
    originalLayout = """
      |/src/shared/a.scala
      |object a { def foo { println(1) } }
      |""".stripMargin,
    args = Array(
      "-r",
      "ProcedureSyntax",
      "--out-from",
      "shared",
      "--out-to",
      "fixed",
      "src"
    ),
    expectedLayout = """
      |/src/fixed/a.scala
      |object a { def foo: Unit = { println(1) } }
      |
      |/src/shared/a.scala
      |object a { def foo { println(1) } }
      |""".stripMargin,
    expectedExit = ExitStatus.Ok
  )

  check(
    name = "skip parser when it's not needed",
    originalLayout = """
      |/src/shared/a.scala
      |object a {
      |""".stripMargin,
    args = Array(
      "-r",
      "NoOpRule"
    ),
    expectedLayout = """
      |/src/shared/a.scala
      |object a {
      |""".stripMargin,
    expectedExit = ExitStatus.Ok
  )

  check(
    name = "don't skip parser when there is a suppression",
    originalLayout = """
      |/src/shared/a.scala
      |object a { // scalafix:
      |""".stripMargin,
    args = Array(
      "-r",
      "NoOpRule"
    ),
    expectedLayout = """
      |/src/shared/a.scala
      |object a { // scalafix:
      |""".stripMargin,
    expectedExit = ExitStatus.ParseError
  )

  check(
    name = "unexpected error",
    originalLayout = """
      |/src/shared/a.scala
      |object a { }
      |""".stripMargin,
    args = Array(
      "-r",
      "CrashingRule"
    ),
    expectedLayout = """
      |/src/shared/a.scala
      |object a { }
      |""".stripMargin,
    outputAssert = { out =>
      assert(out.contains("FileException"), out)
      assert(out.contains("a.scala"), out)
      assert(out.contains("MissingSymbolException"), out)
      assert(out.contains("local63"), out)
      // Check that the stack trace doesn't include irrelevant entries.
      assert(out.contains("unsafeHandleFile"), out)
      assert(!out.contains("handleFile"), out)
    },
    expectedExit = ExitStatus.UnexpectedError
  )

  def checkCommandLineError(
      name: String,
      args: Array[String],
      fn: String => Unit
  ): Unit =
    check(
      name = name,
      originalLayout = "",
      args = args,
      expectedLayout = "",
      expectedExit = ExitStatus.CommandLineError,
      outputAssert = { out =>
        fn(out)
      }
    )

  checkCommandLineError(
    "--scala-version error",
    Array("-r", "Scala2_9", "--scala-version", "2.12.8"),
    { out =>
      assert(out.contains("must start with 2.9"))
    }
  )

  checkCommandLineError(
    "--scalac-options error",
    Array("-r", "Scala2_9", "--scala-version", "2.9.6"),
    { out =>
      assert(out.contains("must contain -Ysource:2.9"))
    }
  )
}
