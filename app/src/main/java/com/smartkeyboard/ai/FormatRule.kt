package com.smartkeyboard.ai

/**
 * A single Smart Clipboard Formatter rule.
 *
 * [config] holds type-specific parameters as JSON, e.g.:
 *  REMOVE_FIRST_N        {"n": 3}
 *  REMOVE_LAST_N         {"n": 2}
 *  REMOVE_PLUS           {}
 *  REMOVE_COUNTRY_CODE   {"codes": "+91,+63,+1,+44"}
 *  DIGITS_ONLY           {}
 *  REMOVE_SPACES         {}
 *  REMOVE_SPECIAL_CHARS  {}
 *  REPLACE_TEXT          {"find": "+91", "replace": ""}
 *  TRIM_SPACES           {}
 *  UPPERCASE             {}
 *  LOWERCASE             {}
 *  CUSTOM_REGEX          {"pattern": "\\D", "replacement": ""}
 */
data class FormatRule(
    val id: Long,
    val name: String,
    val type: String,
    val enabled: Boolean,
    val priority: Int,
    val phoneOnly: Boolean,
    val config: String
) {
    companion object {
        const val REMOVE_FIRST_N = "REMOVE_FIRST_N"
        const val REMOVE_LAST_N = "REMOVE_LAST_N"
        const val REMOVE_PLUS = "REMOVE_PLUS"
        const val REMOVE_COUNTRY_CODE = "REMOVE_COUNTRY_CODE"
        const val DIGITS_ONLY = "DIGITS_ONLY"
        const val REMOVE_SPACES = "REMOVE_SPACES"
        const val REMOVE_SPECIAL_CHARS = "REMOVE_SPECIAL_CHARS"
        const val REPLACE_TEXT = "REPLACE_TEXT"
        const val TRIM_SPACES = "TRIM_SPACES"
        const val UPPERCASE = "UPPERCASE"
        const val LOWERCASE = "LOWERCASE"
        const val CUSTOM_REGEX = "CUSTOM_REGEX"

        val ALL_TYPES = listOf(
            REMOVE_FIRST_N, REMOVE_LAST_N, REMOVE_PLUS, REMOVE_COUNTRY_CODE,
            DIGITS_ONLY, REMOVE_SPACES, REMOVE_SPECIAL_CHARS, REPLACE_TEXT,
            TRIM_SPACES, UPPERCASE, LOWERCASE, CUSTOM_REGEX
        )
    }
}
