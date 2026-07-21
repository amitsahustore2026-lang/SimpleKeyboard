package com.smartkeyboard.ai

import org.json.JSONObject

/**
 * Stateless rule engine. Never throws - any bad rule (invalid regex, bad
 * config JSON, etc.) is skipped so a broken rule can never crash the
 * keyboard or corrupt a paste; it just leaves that step's text unchanged.
 */
object FormatterEngine {

    fun applyRules(text: String, isPhoneField: Boolean, rules: List<FormatRule>): String {
        var result = text
        val sorted = rules.filter { it.enabled }.sortedBy { it.priority }
        for (rule in sorted) {
            if (rule.phoneOnly && !isPhoneField) continue
            result = try {
                applySingleRule(result, rule.type, rule.config)
            } catch (e: Exception) {
                result
            }
        }
        return result
    }

    private fun applySingleRule(text: String, type: String, configJson: String): String {
        val config = try {
            JSONObject(configJson)
        } catch (e: Exception) {
            JSONObject()
        }

        return when (type) {
            FormatRule.REMOVE_FIRST_N -> {
                val n = config.optInt("n", 0)
                if (n in 0..text.length) text.substring(n) else ""
            }

            FormatRule.REMOVE_LAST_N -> {
                val n = config.optInt("n", 0)
                when {
                    n <= 0 -> text
                    n < text.length -> text.substring(0, text.length - n)
                    else -> ""
                }
            }

            FormatRule.REMOVE_PLUS -> text.replace("+", "")

            FormatRule.REMOVE_COUNTRY_CODE -> {
                val codes = config.optString("codes", "")
                    .split(",")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .sortedByDescending { it.length }
                val match = codes.firstOrNull { text.startsWith(it) }
                if (match != null) text.substring(match.length) else text
            }

            FormatRule.DIGITS_ONLY -> text.filter { it.isDigit() }

            FormatRule.REMOVE_SPACES -> text.replace(" ", "")

            FormatRule.REMOVE_SPECIAL_CHARS -> text.filter { it.isLetterOrDigit() || it == ' ' }

            FormatRule.REPLACE_TEXT -> {
                val find = config.optString("find", "")
                val replace = config.optString("replace", "")
                if (find.isNotEmpty()) text.replace(find, replace) else text
            }

            FormatRule.TRIM_SPACES -> text.trim()

            FormatRule.UPPERCASE -> text.uppercase()

            FormatRule.LOWERCASE -> text.lowercase()

            FormatRule.CUSTOM_REGEX -> {
                val pattern = config.optString("pattern", "")
                val replacement = config.optString("replacement", "")
                if (pattern.isEmpty()) {
                    text
                } else {
                    Regex(pattern).replace(text, replacement)
                }
            }

            else -> text
        }
    }

    /** Human-readable one-line preview of what a single rule does to a sample. */
    fun previewSingleRule(sample: String, type: String, configJson: String): String {
        return try {
            applySingleRule(sample, type, configJson)
        } catch (e: Exception) {
            sample
        }
    }
}
