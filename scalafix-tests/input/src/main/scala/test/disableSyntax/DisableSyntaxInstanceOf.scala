/*
rules = DisableSyntax
DisableSyntax.noIsInstanceOf = true
DisableSyntax.noAsInstanceOf = true
 */
package test.disableSyntax

class DisableSyntaxInstanceOf {
  1.isInstanceOf[Int] // assert: DisableSyntax.isInstanceOf
  "".asInstanceOf[Int] // assert: DisableSyntax.asInstanceOf
}