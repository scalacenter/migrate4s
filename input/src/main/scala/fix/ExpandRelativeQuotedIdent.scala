/*
rules = [OrganizeImports]
OrganizeImports {
  expandRelative = true
  groupedImports = Explode
}
 */
package fix

import QuotedIdent.`a.b`
import QuotedIdent.`macro`
import `a.b`.c
import `a.b`.`{ d }`

object ExpandRelativeQuotedIdent
