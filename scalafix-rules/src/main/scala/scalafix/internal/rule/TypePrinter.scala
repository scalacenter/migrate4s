package scalafix.internal.rule

import scala.{meta => m}

import scala.meta.internal.pc.ScalafixGlobal

import scalafix.v1

class TypePrinter {
  def toPatch(
      pos: m.Position,
      sym: v1.Symbol,
      replace: m.Token,
      defn: m.Defn,
      space: String
  ): Option[v1.Patch] = None
}

object TypePrinter {
  def apply(
      global: Option[ScalafixGlobal],
      config: ExplicitResultTypesConfig
  )(implicit ctx: v1.SemanticDocument): TypePrinter =
    global match {
      case None => new TypePrinter
      case Some(value) => new CompilerTypePrinter(value, config)
    }
}
