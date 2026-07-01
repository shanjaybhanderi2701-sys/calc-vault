package com.appblish.calculatorvault.calculator

/**
 * Pure, side-effect-free evaluation of the calculator's four-function input. Kept
 * separate from Android so it is fully unit-testable and can back both the disguise
 * UI and any headless checks. Not a full expression parser — left-to-right with no
 * operator precedence is deliberate: it must behave like a cheap pocket calculator,
 * which is exactly the disguise we want.
 */
object CalculatorEngine {
    private val operators = setOf('+', '-', '*', '/', '×', '÷')

    /**
     * Evaluate [expression] left-to-right. Returns null for empty/malformed input
     * or division by zero so the UI can simply show the previous value or an error
     * glyph without throwing.
     */
    fun evaluate(expression: String): Double? {
        val normalized = expression.replace('×', '*').replace('÷', '/').trim()
        if (normalized.isEmpty()) return null

        val tokens = tokenize(normalized) ?: return null
        if (tokens.isEmpty()) return null

        var acc = (tokens.first() as? Token.Number)?.value ?: return null
        var i = 1
        while (i < tokens.size) {
            val op = tokens[i] as? Token.Op ?: return null
            val rhs = (tokens.getOrNull(i + 1) as? Token.Number)?.value ?: return null
            acc =
                when (op.symbol) {
                    '+' -> acc + rhs
                    '-' -> acc - rhs
                    '*' -> acc * rhs
                    '/' -> if (rhs == 0.0) return null else acc / rhs
                    else -> return null
                }
            i += 2
        }
        return acc
    }

    private sealed interface Token {
        data class Number(
            val value: Double
        ) : Token

        data class Op(
            val symbol: Char
        ) : Token
    }

    private fun tokenize(input: String): List<Token>? {
        val tokens = mutableListOf<Token>()
        val number = StringBuilder()

        fun flushNumber(): Boolean {
            if (number.isEmpty()) return true
            val value = number.toString().toDoubleOrNull() ?: return false
            tokens.add(Token.Number(value))
            number.clear()
            return true
        }

        for ((index, ch) in input.withIndex()) {
            when {
                ch.isDigit() || ch == '.' -> number.append(ch)
                // Leading '-' or one right after an operator is a unary sign, not a binary op.
                ch == '-' && (index == 0 || input[index - 1].let { it in operators }) ->
                    number.append(ch)
                ch in operators -> {
                    if (!flushNumber()) return null
                    tokens.add(Token.Op(ch))
                }
                ch == ' ' -> Unit
                else -> return null
            }
        }
        if (!flushNumber()) return null
        return tokens
    }
}
