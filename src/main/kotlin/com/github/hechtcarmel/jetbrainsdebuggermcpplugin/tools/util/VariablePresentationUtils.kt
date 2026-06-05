package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.util

import com.intellij.xdebugger.frame.presentation.XValuePresentation

/**
 * Utility helpers for XValue presentations.
 */
object VariablePresentationUtils {
    fun renderPresentation(presentation: XValuePresentation): String {
        return buildString {
            presentation.renderValue(object : XValuePresentation.XValueTextRenderer {
                override fun renderValue(v: String) { append(v) }
                override fun renderStringValue(v: String) { append("\"$v\"") }
                override fun renderNumericValue(v: String) { append(v) }
                override fun renderKeywordValue(v: String) { append(v) }
                override fun renderValue(
                    v: String,
                    key: com.intellij.openapi.editor.colors.TextAttributesKey
                ) { append(v) }
                override fun renderStringValue(
                    v: String,
                    additionalSpecialCharsToHighlight: String?,
                    maxLength: Int
                ) { append("\"$v\"") }
                override fun renderComment(comment: String) { append(" // $comment") }
                override fun renderSpecialSymbol(symbol: String) { append(symbol) }
                override fun renderError(error: String) { append("ERROR: $error") }
            })
        }
    }
}
