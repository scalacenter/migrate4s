package scala.meta.internal.scalafix

import scala.meta.Tree
import scala.meta.internal.semanticdb.Scala.Names
import scala.meta.internal.trees.Origin

object ScalafixScalametaHacks {
  def resetOrigin(tree: Tree): Tree = tree.withOrigin(Origin.None)
  def encode(name: String): String = Names.encode(name)
}
