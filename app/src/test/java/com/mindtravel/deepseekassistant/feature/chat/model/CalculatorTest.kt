package com.mindtravel.deepseekassistant.feature.chat.model

import com.mindtravel.deepseekassistant.core.ApiException
import com.mindtravel.deepseekassistant.feature.chat.model.tools.Calculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CalculatorTest {

    @Test
    fun basicPrecedence() {
        assertEquals("7", Calculator.evaluate("1 + 2 * 3"))
    }

    @Test
    fun parentheses() {
        assertEquals("9", Calculator.evaluate("(1 + 2) * 3"))
    }

    @Test
    fun power() {
        assertEquals("512", Calculator.evaluate("2^3^2"))
        assertEquals("-4", Calculator.evaluate("-2^2"))
        assertEquals("0.25", Calculator.evaluate("2^-2"))
    }

    @Test
    fun functions() {
        assertEquals("3", Calculator.evaluate("sqrt(9)"))
        assertEquals("2", Calculator.evaluate("log(100)"))
    }

    @Test
    fun constants() {
        assertEquals("3.1415926536", Calculator.evaluate("pi"))
    }

    @Test
    fun divisionByZero() {
        assertThrows(ApiException::class.java) {
            Calculator.evaluate("1 / 0")
        }
    }

    @Test
    fun invalidExpression() {
        assertThrows(ApiException::class.java) {
            Calculator.evaluate("1 +")
        }
    }
}
