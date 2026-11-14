package com.mindtravel.deepseekassistant.feature.chat.model.tools

import com.mindtravel.deepseekassistant.core.stringOrNull
import com.mindtravel.deepseekassistant.feature.chat.model.ToolSpec
import org.json.JSONObject

class CalculatorTool : ChatTool {
    override val spec = ToolSpec(
        name = "calculator",
        description = "精确计算数学表达式。支持 + - * / % ^、括号、sqrt、sin、cos、tan、log、ln、abs、pi、e；三角函数使用弧度。",
        parameterName = "expression",
        parameterDescription = "要计算的数学表达式"
    )

    override fun execute(argumentsJson: String): String {
        val expression = JSONObject(argumentsJson).stringOrNull("expression")?.trim().orEmpty()
        if (expression.isBlank()) return "计算失败：expression 为空"
        return Calculator.evaluate(expression)
    }
}
