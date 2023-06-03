package scalafix.tests.util

import scala.meta._

import org.scalatest.funsuite.AnyFunSuite
import scalafix.testkit.DiffAssertions
import scalafix.v1._

class ScalametaStructureSuite extends AnyFunSuite with DiffAssertions {
  test("pretty(t)") {
    val obtained = Term
      .Select(
        Term
          .Select(Term.Select(Term.Name("a"), Term.Name("b")), Term.Name("c")),
        Term.Name("d")
      )
      .structureWidth(1)
    val expected =
      """|Term.Select(
        |  Term.Select(
        |    Term.Select(
        |      Term.Name("a"),
        |      Term.Name("b")
        |    ),
        |    Term.Name("c")
        |  ),
        |  Term.Name("d")
        |)
        |""".stripMargin
    assertNoDiff(obtained, expected)
  }

  test("pretty(t, showFieldNames = true)") {
    val obtained = Term
      .Select(
        Term
          .Select(Term.Select(Term.Name("a"), Term.Name("b")), Term.Name("c")),
        Term.Name("d")
      )
      .structureLabeled(1)
    val expected =
      """|
        |Term.Select(
        |  qual = Term.Select(
        |    qual = Term.Select(
        |      qual = Term.Name("a"),
        |      name = Term.Name("b")
        |    ),
        |    name = Term.Name("c")
        |  ),
        |  name = Term.Name("d")
        |)
        |""".stripMargin
    assertNoDiff(obtained, expected)
  }

  test("option") {
    assertNoDiff(
      Defn.Def
        .After_4_7_3(
          List(),
          Term.Name("foo"),
          Member.ParamClauseGroup(Type.ParamClause(Nil), Nil) :: Nil,
          Some(Type.Name("A")),
          Term.Name("???")
        )
        .decltpe
        .structureWidth(1),
      """|
        |Some(Type.Name("A"))
        |""".stripMargin
    )
  }

  test("list") {
    assertNoDiff(
      // NOTE(olafur): need downcast because List is no longer a Product in 2.13.
      Term.Apply
        .After_4_6_0(Term.Name("foo"), Term.ArgClause(List(Term.Name("a"))))
        .argClause
        .values
        .asInstanceOf[Product]
        .structureWidth(1),
      """|List(
        |  Term.Name("a")
        |)
        |""".stripMargin
    )
  }
}
