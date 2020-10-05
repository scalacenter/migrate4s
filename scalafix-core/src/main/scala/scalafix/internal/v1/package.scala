package scalafix.internal

import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths

import scala.meta.inputs.Input
import scala.meta.internal.io.FileIO
import scala.meta.internal.{semanticdb => s}
import scala.meta.io.AbsolutePath

package object v1 {

  implicit class XtensionTextDocumentFix(sdoc: s.TextDocument) {
    def abspath(sourceroot: URI): AbsolutePath = {
      val absuri = sourceroot.resolve(URI.create(sdoc.uri))
      AbsolutePath(Paths.get(absuri))
    }
    def input(abspath: AbsolutePath): Input = {
      val text = FileIO.slurp(abspath, StandardCharsets.UTF_8)
      val textMD5 = FingerprintOps.md5(text)
      require(
        textMD5 == sdoc.md5,
        s"MD5 fingerprint mismatch. " +
          s"Obtained (on disk) $textMD5. " +
          s"Expected (in TextDocument) ${sdoc.md5}"
      )
      val input = Input.VirtualFile(sdoc.uri, text)
      input
    }
  }

  implicit class XtensionTextDocumentsCompanionFix(`_`: s.TextDocuments.type) {
    def parseFromFile(file: AbsolutePath): s.TextDocuments = {
      val in = Files.newInputStream(file.toNIO)
      val sdocs =
        try {
          s.TextDocuments
            .parseFrom(in)
            .mergeDiagnosticOnlyDocuments
        } finally in.close()
      sdocs.documents.foreach { doc =>
        require(
          doc.schema.isSemanticdb4,
          s"$file " +
            s"Expected TextDocument.schema=SEMANTICDB4. " +
            s"Obtained ${doc.schema}"
        )
      }
      sdocs
    }
  }

  implicit class XtensionTextDocumentsFix(sdocs: s.TextDocuments) {

    def mergeDiagnosticOnlyDocuments: s.TextDocuments = {
      // returns true if this document contains only diagnostics and nothing else.
      // deprecation messages are reported in refchecks and get persisted
      // as standalone documents that need to be merged with their typer-phase
      // document during loading. It seems there's no way to merge the documents
      // during compilation without introducing a lot of memory pressure.
      def isOnlyMessages(sdocument: s.TextDocument): Boolean =
        sdocument.diagnostics.nonEmpty &&
          sdocument.text.isEmpty &&
          sdocument.symbols.isEmpty &&
          sdocument.occurrences.isEmpty &&
          sdocument.synthetics.isEmpty
      if (sdocs.documents.lengthCompare(1) <= 0) {
        // NOTE(olafur) the most common case is that there is only a single database
        // per document so we short-circuit here if that's the case.
        sdocs
      } else {
        sdocs.documents match {
          case Seq(smaindoc, sdiagdoc)
              if smaindoc.uri == sdiagdoc.uri &&
                isOnlyMessages(sdiagdoc) =>
            val smaindoc1 = smaindoc.addDiagnostics(sdiagdoc.diagnostics: _*)
            s.TextDocuments(smaindoc1 :: Nil)
          case _ =>
            sdocs
        }
      }
    }

  }

}
