package test.organizeImports

import test.organizeImports.Implicits.a.nonImplicit
import test.organizeImports.Implicits.b._

import scala.concurrent.ExecutionContext

import ExecutionContext.Implicits.global
import test.organizeImports.Implicits.a.intImplicit
import test.organizeImports.Implicits.a.stringImplicit

// Unsupported on Scala 3
// https://github.com/scala/scala3/issues/12766
// https://github.com/scalacenter/scalafix/blob/f51cb4a/docs/rules/OrganizeImports.md?plain=1#L440-L444
object ExplicitlyImportedImplicits {
  def f1()(implicit i: Int) = ???
  def f2()(implicit s: String) = ???
  f1()
  f2()
}
