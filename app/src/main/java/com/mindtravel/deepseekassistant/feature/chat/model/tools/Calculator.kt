package com.mindtravel.deepseekassistant.feature.chat.model.tools

import com.mindtravel.deepseekassistant.core.ApiException
import java.math.BigDecimal
import java.math.RoundingMode

object Calculator {

    fun evaluate(input: String): String {
        val normalized = input
            .replace("×", "*")
            .replace("÷", "/")
            .replace("π", "pi")
            .replace("，", ",")
            .trim()
        if (normalized.isEmpty()) throw ApiException("表达式为空")
        if (normalized.length > 500) throw ApiException("表达式过长")

        val value = try {
            Parser(normalized).parse()
        } catch (t: ApiException) {
            throw t
        } catch (t: Throwable) {
            throw ApiException("计算失败：${t.message ?: "表达式无效"}")
        }

        if (!value.isFinite()) throw ApiException("计算结果不是有限数")
        return format(value)
    }

    private fun format(value: Double): String {
        if (value == value.toLong().toDouble()) return value.toLong().toString()
        return BigDecimal.valueOf(value)
            .setScale(10, RoundingMode.HALF_UP)
            .stripTrailingZeros()
            .toPlainString()
    }

    private class Parser(private val text: String) {
        private var pos = 0

        fun parse(): Double {
            val value = expression()
            skipSpaces()
            if (pos != text.length) {
                throw ApiException("无法识别的字符：${text[pos]}")
            }
            return value
        }

        private fun expression(): Double {
            var value = term()
            while (true) {
                skipSpaces()
                value = when {
                    peek('+') -> { pos++; value + term() }
                    peek('-') -> { pos++; value - term() }
                    else -> return value
                }
            }
        }

        private fun term(): Double {
            var value = unary()
            while (true) {
                skipSpaces()
                value = when {
                    peek('*') -> { pos++; value * unary() }
                    peek('/') -> {
                        pos++
                        val divisor = unary()
                        if (divisor == 0.0) throw ApiException("不能除以 0")
                        value / divisor
                    }
                    peek('%') -> {
                        pos++
                        val divisor = unary()
                        if (divisor == 0.0) throw ApiException("不能对 0 取模")
                        value % divisor
                    }
                    else -> return value
                }
            }
        }

        private fun unary(): Double {
            skipSpaces()
            return when {
                peek('+') -> { pos++; unary() }
                peek('-') -> { pos++; -unary() }
                else -> power()
            }
        }

        private fun power(): Double {
            val base = primary()
            skipSpaces()
            if (peek('^')) {
                pos++
                return Math.pow(base, unary())
            }
            return base
        }

        private fun primary(): Double {
            skipSpaces()
            if (pos >= text.length) throw ApiException("表达式不完整")

            if (peek('(')) {
                pos++
                val value = expression()
                skipSpaces()
                if (!peek(')')) throw ApiException("缺少右括号")
                pos++
                return value
            }

            if (text[pos].isDigit() || text[pos] == '.') {
                return number()
            }

            if (text[pos].isLetter()) {
                val name = identifier().lowercase()
                skipSpaces()
                if (name == "pi") return Math.PI
                if (name == "e") return Math.E
                if (!peek('(')) throw ApiException("函数 $name 缺少括号")
                pos++
                val argument = expression()
                skipSpaces()
                if (!peek(')')) throw ApiException("函数 $name 缺少右括号")
                pos++
                return function(name, argument)
            }

            throw ApiException("无法识别的字符：${text[pos]}")
        }

        private fun number(): Double {
            val start = pos
            var seenDot = false
            while (pos < text.length) {
                val c = text[pos]
                if (c.isDigit()) { pos++; continue }
                if (c == '.' && !seenDot) { seenDot = true; pos++; continue }
                if ((c == 'e' || c == 'E') && pos > start) {
                    pos++
                    if (pos < text.length && (text[pos] == '+' || text[pos] == '-')) pos++
                    continue
                }
                break
            }
            val raw = text.substring(start, pos)
            return raw.toDoubleOrNull() ?: throw ApiException("数字格式错误：$raw")
        }

        private fun identifier(): String {
            val start = pos
            while (pos < text.length && (text[pos].isLetter() || text[pos] == '_')) {
                pos++
            }
            return text.substring(start, pos)
        }

        private fun function(name: String, value: Double): Double = when (name) {
            "sqrt" -> {
                if (value < 0) throw ApiException("sqrt 不能处理负数")
                Math.sqrt(value)
            }
            "abs" -> Math.abs(value)
            "sin" -> Math.sin(value)
            "cos" -> Math.cos(value)
            "tan" -> Math.tan(value)
            "log" -> {
                if (value <= 0) throw ApiException("log 需要正数")
                Math.log10(value)
            }
            "ln" -> {
                if (value <= 0) throw ApiException("ln 需要正数")
                Math.log(value)
            }
            else -> throw ApiException("不支持的函数：$name")
        }

        private fun skipSpaces() {
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }

        private fun peek(char: Char): Boolean = pos < text.length && text[pos] == char
    }
}
