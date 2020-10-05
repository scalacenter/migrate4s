package scalafix.internal.patch

import scala.meta.Classpath

import scalafix.v0.Document
import scalafix.v0.SemanticdbIndex

trait CrashingSemanticdbIndex extends SemanticdbIndex {
  final override def classpath: Classpath =
    throw new UnsupportedOperationException
  final override def withDocuments(documents: Seq[Document]): SemanticdbIndex =
    throw new UnsupportedOperationException
}
