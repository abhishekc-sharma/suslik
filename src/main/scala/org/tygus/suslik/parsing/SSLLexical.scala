package org.tygus.suslik.parsing

import scala.util.parsing.combinator.lexical.StdLexical

/**
  * @author Ilya Sergey
  */
class SSLLexical extends StdLexical {

  // Add keywords
  reserved += ("if", "then", "else", "true", "false", "emp", "not", "return", "predicate", "in", "lower", "upper")
  reserved += ("error","magic","malloc", "free", "let", "assume")
  reserved += ("null")

  // Types
  reserved += ("int", "bool", "loc", "set", "multiset", "void", "interval")

  delimiters += ("(", ")", "=", ";", "**", "*", ":->", "=i", "<=i", "=m", "<=m", "++", "--", "..",
      "{", "}", "#{", "/\\", "&&", "\\/", "||", "\n", "\r", "=>", "?", ":",
      "<", ">", ",", "/",   "+", "-", "==", "!=", "==>", "<=", ">=", "[", "]", "|", "??"
  )

}
