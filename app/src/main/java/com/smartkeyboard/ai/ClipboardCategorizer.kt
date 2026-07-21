package com.smartkeyboard.ai

object ClipboardCategorizer {

    const val ALL = "ALL"
    const val PHONE = "PHONE"
    const val OTP = "OTP"
    const val EMAIL = "EMAIL"
    const val URL = "URL"
    const val ADDRESS = "ADDRESS"
    const val NOTES = "NOTES"
    const val OTHERS = "OTHERS"

    val ALL_CATEGORIES = listOf(ALL, PHONE, OTP, EMAIL, URL, ADDRESS, NOTES, OTHERS)

    private val EMAIL_REGEX = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")
    private val PHONE_REGEX = Regex("^[+]?[0-9][0-9\\-\\s()]{7,16}$")
    private val URL_REGEX = Regex(
        "^(https?://|www\\.)\\S+$|^[A-Za-z0-9-]+\\.(com|org|net|in|io|co)([/?#]\\S*)?$",
        RegexOption.IGNORE_CASE
    )
    private val ADDRESS_KEYWORDS = listOf(
        "street", "road", "nagar", "colony", "sector", "avenue",
        "lane", "block", "apartment", "pin code", "zip"
    )

    fun categorize(rawText: String): String {
        val text = rawText.trim()
        if (text.isEmpty()) return OTHERS

        val lower = text.lowercase()
        val digitsOnly = text.filter { it.isDigit() }
        val compact = text.replace(" ", "")

        if (lower.contains("otp") ||
            lower.contains("verification code") ||
            (digitsOnly == compact && digitsOnly.length in 4..8)
        ) {
            return OTP
        }

        if (EMAIL_REGEX.matches(text)) return EMAIL

        if (URL_REGEX.matches(text)) return URL

        if (PHONE_REGEX.matches(text) && digitsOnly.length >= 10) return PHONE

        if (ADDRESS_KEYWORDS.any { lower.contains(it) }) return ADDRESS

        if (text.length > 60 || text.contains("\n")) return NOTES

        return OTHERS
    }
}
