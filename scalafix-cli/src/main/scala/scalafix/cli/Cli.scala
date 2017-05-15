package scalafix
package cli

import scala.collection.GenSeq
import scala.collection.immutable.Seq
import scala.meta._
import scala.meta.dialects
import scala.meta.inputs.Input
import scala.meta.io.AbsolutePath
import scala.util.Success
import scala.util.Try
import scala.util.control.NonFatal
import scalafix.cli.termdisplay.TermDisplay
import scalafix.config._
import scalafix.reflect.ScalafixCompilerDecoder
import scalafix.reflect.ScalafixToolbox
import scalafix.rewrite.ProcedureSyntax
import scalafix.util.FileOps

import java.io.File
import java.io.InputStream
import java.io.OutputStreamWriter
import java.io.PrintStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.function.UnaryOperator
import java.util.regex.Pattern

import caseapp._
import caseapp.core.WithHelp
import com.martiansoftware.nailgun.NGContext
import metaconfig.Conf
import metaconfig.ConfError
import metaconfig.Configured
import metaconfig.Configured.Ok

case class CommonOptions(
    @Hidden workingDirectory: String = System.getProperty("user.dir"),
    @Hidden out: PrintStream = System.out,
    @Hidden in: InputStream = System.in,
    @Hidden err: PrintStream = System.err,
    @Hidden stackVerbosity: Int = 20
) {
  def workingDirectoryFile = new File(workingDirectory)
}

@AppName("scalafix")
@AppVersion(scalafix.Versions.version)
@ProgName("scalafix")
case class ScalafixOptions(
    @HelpMessage(
      "Scalafix configuration, either a file path or a hocon string"
    ) @ValueDescription(
      ".scalafix.conf OR imports.organize=false"
    ) @ExtraName("c") config: Option[String] = None,
    @HelpMessage(
      """java.io.File.pathSeparator separated list of jar files or directories
        |        containing classfiles and `semanticdb` files. The `semanticdb`
        |        files are emitted by the scalahost-nsc compiler plugin and
        |        are necessary for the semantic API to function. The
        |        classfiles + jar files are necessary forruntime compilation
        |        of quasiquotes when extracting symbols (that is,
        |        `q"scala.Predef".symbol`).""".stripMargin
    ) @ValueDescription(
      "entry1.jar:entry2.jar"
    ) classpath: Option[String] = None,
    @HelpMessage(
      """java.io.File.pathSeparator separated list of Scala source files OR
        |        directories containing Scala source files.""".stripMargin
    ) @ValueDescription(
      "File2.scala:File1.scala:src/main/scala"
    ) sourcepath: Option[String] = None,
    @HelpMessage(
      s"""Rewrite rules to run.
         |        NOTE. rewrite.rules = [ .. ] from --config will also run.""".stripMargin
    ) @ValueDescription(
      s"""$ProcedureSyntax OR
         |               file:LocalFile.scala OR
         |               scala:full.Name OR
         |               https://gist.com/.../Rewrite.scala""".stripMargin
    ) rewrites: List[String] = Nil,
    @HelpMessage(
      "Files to fix. Runs on all *.scala files if given a directory."
    ) @ValueDescription(
      "File1.scala File2.scala"
    ) @ExtraName("f") files: List[String] = List.empty[String],
    @HelpMessage(
      "If true, writes changes to files instead of printing to stdout."
    ) @ExtraName("i") inPlace: Boolean = false,
    @HelpMessage(
      """Regex that is passed as first argument to
        |        fileToFix.replaceAll(outFrom, outTo).""".stripMargin
    ) @ValueDescription("/shared/") outFrom: String = "",
    @HelpMessage(
      """Replacement string that is passed as second argument to
        |        fileToFix.replaceAll(outFrom, outTo)""".stripMargin
    ) @ValueDescription("/custom/") outTo: String = "",
    @HelpMessage(
      "If true, run on single thread. If false (default), use all available cores."
    ) singleThread: Boolean = false,
    @HelpMessage(
      "If true, prints out debug information."
    ) debug: Boolean = false,
    // NOTE: This option could be avoided by adding another entrypoint like `Cli.safeMain`
    // or SafeCli.main. However, I opted for a cli flag since that plays nicely
    // with default `run.in(Compile).toTask("--no-sys-exit").value` in sbt.
    // Another other option would be to do
    // `runMain.in(Compile).toTask("scalafix.cli.SafeMain")` but I prefer to
    // keep only one main function if possible since that plays nicely with
    // automatic detection of `main` functions in tools like `coursier launch`.
    @HelpMessage(
      "If true, does not sys.exit at the end. Useful for example in sbt-scalafix."
    ) noSysExit: Boolean = false,
    @Recurse common: CommonOptions = CommonOptions()
) {

  object InputSource {
    def unapply(source: Source): Option[AbsolutePath] =
      for {
        tok <- source.tokens.headOption
        input = tok.input
        if input.isInstanceOf[Input.File]
      } yield input.asInstanceOf[Input.File].path
  }

  lazy val resolvedFiles: GenSeq[String] = {
    val scalaFiles = {
      if (files.nonEmpty) files.flatMap(FileOps.listFiles)
      else {
        resolvedMirror
          .map {
            case Some(x) =>
              x.sources.collect { case InputSource(path) => path.toString() }
            case _ => Nil
          }
          .getOrElse(Nil)
      }
    }.filter(Cli.isScalaPath _)
    if (singleThread) scalaFiles
    else scalaFiles.par
  }
  lazy val absoluteFiles: List[File] = files.map { f =>
    val file = new File(f)
    if (file.isAbsolute) file
    else new File(new File(common.workingDirectory), f)
  }

  /** Returns ScalafixConfig from .scalafix.conf, it exists and --config was not passed. */
  lazy val resolvedRewriteAndConfig: Configured[(Rewrite, ScalafixConfig)] =
    for {
      mirror <- resolvedMirror
      decoder = ScalafixCompilerDecoder.fromMirrorOption(mirror)
      cliArgRewrite <- rewrites
        .foldLeft(ScalafixToolbox.emptyRewrite) {
          case (rewrite, next) =>
            rewrite
              .product(decoder.read(Conf.Str(next)))
              .map { case (a, b) => a.andThen(b) }
        }
      configuration <- {
        val input: Option[Input] =
          ScalafixConfig
            .auto(common.workingDirectoryFile)
            .orElse(config.map { x =>
              if (new File(x).isFile) Input.File(new File(x))
              else Input.String(x)
            })
        input
          .map(x => ScalafixConfig.fromInput(x, mirror)(decoder))
          .getOrElse(
            Configured.Ok(
              Rewrite.emptyFromMirrorOpt(mirror) ->
                ScalafixConfig.default
            ))
      }
      // TODO(olafur) implement withFilter on Configured
      (configRewrite, scalafixConfig) = configuration
      finalConfig = scalafixConfig.copy(
        reporter = scalafixConfig.reporter match {
          case r: PrintStreamReporter => r.copy(outStream = common.err)
          case _ => ScalafixReporter.default.copy(outStream = common.err)
        }
      )
      finalRewrite = cliArgRewrite.andThen(configRewrite)
    } yield finalRewrite -> finalConfig
  lazy val resolvedRewrite = resolvedRewriteAndConfig.map(_._1)
  lazy val resolvedConfig = resolvedRewriteAndConfig.map(_._2)

  lazy val resolvedSbtConfig: Configured[ScalafixConfig] =
    resolvedConfig.map(_.copy(dialect = dialects.Sbt0137))

  lazy val resolvedMirror: Configured[Option[Mirror]] =
    (classpath, sourcepath) match {
      case (Some(cp), Some(sp)) =>
        val tryMirror = for {
          mirror <- Try {
            val mirror = Mirror(Classpath(cp), Sourcepath(sp))
            mirror.sources // ugh. need to trigger lazy to validate that all files parse
            mirror
          }
        } yield Option(mirror)
        tryMirror match {
          case Success(x) => Ok(x)
          case scala.util.Failure(e) => ConfError.msg(e.toString).notOk
        }
      case (None, None) =>
        Ok(Try(Mirror()).toOption)
      case _ =>
        ConfError
          .msg(
            "The semantic API was partially configured: " +
              "both a classpath and sourcepath are required.")
          .notOk
    }

  lazy val outFromPattern: Pattern = Pattern.compile(outFrom)
  def replacePath(file: File): File =
    new File(outFromPattern.matcher(file.getPath).replaceAll(outTo))
}

object Cli {
  import ArgParserImplicits._
  private val withHelp = OptionsMessages.withHelp
  val helpMessage: String = withHelp.helpMessage +
    s"""|
        |Examples:
        |  $$ scalafix --rewrites ProcedureSyntax Code.scala # print fixed file to stdout
        |  $$ cat .scalafix.conf
        |  rewrites = [ProcedureSyntax]
        |  $$ scalafix Code.scala # Same as --rewrites ProcedureSyntax
        |  $$ scalafix -i --rewrites ProcedureSyntax Code.scala # write fixed file in-place
        |
        |Exit status codes:
        | ${ExitStatus.all.mkString("\n ")}
        |""".stripMargin
  val usageMessage: String = withHelp.usageMessage
  val default = ScalafixOptions()
  // Run this at the end of the world, calls sys.exit.

  case class NonZeroExitCode(n: Int)
      extends Exception(s"Expected exit code 0. Got exit code $n")
  def main(args: Array[String]): Unit = {
    val code = runMain(args.to[Seq], CommonOptions())
    if (args.contains("--no-sys-exit")) {
      if (code != 0) throw NonZeroExitCode(code)
      else ()
    } else sys.exit(code)
  }

  def safeHandleFile(file: File, options: ScalafixOptions): ExitStatus = {
    try handleFile(file, options)
    catch {
      case NonFatal(e) =>
        reportError(file, e, options)
        ExitStatus.UnexpectedError
    }
  }

  def reportError(file: File,
                  cause: Throwable,
                  options: ScalafixOptions): Unit = {
    options.common.err.println(
      s"""Error fixing file: $file
         |Cause: $cause""".stripMargin
    )
    cause.setStackTrace(
      cause.getStackTrace.take(options.common.stackVerbosity))
    cause.printStackTrace(options.common.err)
  }

  def handleFile(file: File, options: ScalafixOptions): ExitStatus = {
    val config =
      if (file.getAbsolutePath.endsWith(".sbt")) options.resolvedSbtConfig
      else options.resolvedConfig
    val fixed =
      Try(options.resolvedRewrite.get.apply(Input.File(file), config.get))
    fixed match {
      case scala.util.Success(code) =>
        if (options.inPlace) {
          val outFile = options.replacePath(file)
          FileOps.writeFile(outFile, code)
        } else options.common.out.write(code.getBytes)
        ExitStatus.Ok
      case scala.util.Failure(e @ ParseException(_, _)) =>
        if (options.absoluteFiles.exists(
              _.getAbsolutePath == file.getAbsolutePath)) {
          // Only log if user explicitly specified that file.
          reportError(file, e, options)
        }
        ExitStatus.ParseError
      case scala.util.Failure(e) =>
        reportError(file, e, options)
        ExitStatus.ScalafixError
    }
  }

  def isScalaPath(path: String): Boolean =
    path.endsWith(".scala") || path.endsWith(".sbt")

  def runOn(config: ScalafixOptions): ExitStatus = {
    val workingDirectory = new File(config.common.workingDirectory)
    val display = new TermDisplay(new OutputStreamWriter(System.out))
    val filesToFix = config.resolvedFiles
    if (filesToFix.length > 10) display.init()
    val msg = "Running scalafix..."
    display.startTask(msg, workingDirectory)
    display.taskLength(msg, filesToFix.length, 0)
    val exitCode = new AtomicReference(ExitStatus.Ok)
    val counter = new AtomicInteger()
    filesToFix.foreach { x =>
      val code = safeHandleFile(new File(x), config)
      val progress = counter.incrementAndGet()
      exitCode.getAndUpdate(new UnaryOperator[ExitStatus] {
        override def apply(t: ExitStatus): ExitStatus =
          ExitStatus.merge(code, t)
      })
      display.taskProgress(msg, progress)
      code
    }
    display.stop()
    exitCode.get()
  }

  def parse(args: Seq[String]): Either[String, WithHelp[ScalafixOptions]] =
    OptionsParser.withHelp.detailedParse(args) match {
      case Right((help, extraFiles, ls)) =>
        val configured = for {
          _ <- help.base.resolvedMirror // validate
          _ <- help.base.resolvedConfig // validate
        } yield help.map(_.copy(files = help.base.files ++ extraFiles))
        configured.toEither.left.map(_.toString())
      case Left(x) => Left(x)
    }

  def runMain(args: Seq[String], commonOptions: CommonOptions): Int = {
    parse(args) match {
      case Right(WithHelp(usage @ true, _, _)) =>
        commonOptions.out.println(usageMessage)
        0
      case Right(WithHelp(_, help @ true, _)) =>
        commonOptions.out.println(helpMessage)
        0
      case Right(WithHelp(_, _, options)) =>
        runOn(options.copy(common = commonOptions)).code
      case Left(error) =>
        commonOptions.err.println(error)
        1
    }
  }

  def nailMain(nGContext: NGContext): Unit = {
    val code =
      runMain(
        nGContext.getArgs.to[Seq],
        CommonOptions(
          workingDirectory = nGContext.getWorkingDirectory,
          out = nGContext.out,
          in = nGContext.in,
          err = nGContext.err
        )
      )
    nGContext.exit(code)
  }
}
