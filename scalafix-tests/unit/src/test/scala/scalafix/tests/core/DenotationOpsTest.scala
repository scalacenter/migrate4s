package scalafix.tests.core

import scala.meta._
import scala.meta.contrib._
import scalafix.syntax._

object DenotationOpsTest extends BaseSemanticTest("DenotationOpsTest") {

  test("resultType") {
    val source = docs.input.parse[Source].get
    source.collect {
      case t @ Pat.Var(Name("x")) =>
        for {
          symbol <- t.symbol
          resultType <- symbol.resultType
        } yield assert(resultType isEqual t"Boolean")

      case t @ Pat.Var(Name("y")) =>
        for {
          symbol <- t.symbol
          resultType <- symbol.resultType
        } yield assert(resultType isEqual t"List[String]")

      case t: Defn.Def if t.name.value == "m" =>
        for {
          symbol <- t.symbol
          resultType <- symbol.resultType
        } yield assert(resultType isEqual t"List[String]")
    }
  }

}
