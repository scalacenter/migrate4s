package scalafix.tests.v0

import scala.meta._

import scalafix.tests.core.BaseSemanticSuite

class LegacySyntheticsSuite extends BaseSemanticSuite("LegacySyntheticsTest") {

  test("text") {
    val synthetics = index.synthetics(input)
    val obtained = synthetics.sortBy(_.position.start).mkString("\n")
    val expected =
      """
        |[221..226): *(List.canBuildFrom[Int])
        |  [0..1): * => _star_.
        |  [2..6): List => scala/collection/immutable/List.
        |  [7..19): canBuildFrom => scala/collection/immutable/List.canBuildFrom().
        |  [20..23): Int => scala/Int#
        |[230..236): *[Int, Set[Int]]
        |  [0..1): * => _star_.
        |  [2..5): Int => scala/Int#
        |  [7..10): Set => scala/Predef.Set#
        |  [11..14): Int => scala/Int#
        |[245..253): *(Set.canBuildFrom[Int])
        |  [0..1): * => _star_.
        |  [2..5): Set => scala/collection/immutable/Set.
        |  [6..18): canBuildFrom => scala/collection/immutable/Set.canBuildFrom().
        |  [19..22): Int => scala/Int#
        |[268..283): *[Int, List]
        |  [0..1): * => _star_.
        |  [2..5): Int => scala/Int#
        |  [7..11): List => scala/collection/immutable/List#
        |[284..288): *.apply[Future[Int]]
        |  [0..1): * => _star_.
        |  [2..7): apply => scala/collection/immutable/List.apply().
        |  [8..14): Future => scala/concurrent/Future#
        |  [15..18): Int => scala/Int#
        |[289..298): *(global)
        |  [0..1): * => _star_.
        |  [2..8): global => scala/concurrent/ExecutionContext.Implicits.global.
        |[289..295): *.apply[Int]
        |  [0..1): * => _star_.
        |  [2..7): apply => scala/concurrent/Future.apply().
        |  [8..11): Int => scala/Int#
        |[301..309): *(List.canBuildFrom[Int])
        |  [0..1): * => _star_.
        |  [2..6): List => scala/collection/immutable/List.
        |  [7..19): canBuildFrom => scala/collection/immutable/List.canBuildFrom().
        |  [20..23): Int => scala/Int#
      """.stripMargin

    assertNoDiff(obtained, expected)
  }

  test("parsable") {
    val synthetics = index.synthetics(input)
    synthetics.foreach(n => n.text.parse[Term])
  }
}
