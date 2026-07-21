package com.smartkeyboard.ai

import android.os.Build
import android.text.InputType
import android.view.inputmethod.EditorInfo

enum class FieldType { PHONE, OTP, EMAIL, URL, PASSWORD, NORMAL }

/**
 * Best-effort field-type detection from EditorInfo. OTP detection in
 * particular is a heuristic (Android has no universal "this is an OTP
 * field" signal) based on hint/label text where the app provides one -
 * apps that don't set a hint won't be detected as OTP and will simply
 * fall through to PHONE/NORMAL, which is a safe default either way.
 */
object FieldDetector {

    fun detect(info: EditorInfo?): FieldType {
        if (info == null) return FieldType.NORMAL

        return try {
            val inputType = info.inputType
            val cls = inputType and InputType.TYPE_MASK_CLASS
            val variation = inputType and InputType.TYPE_MASK_VARIATION

            if (cls == InputType.TYPE_CLASS_TEXT) {
                if (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
                ) {
                    return FieldType.PASSWORD
                }
                if (variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
                ) {
                    return FieldType.EMAIL
                }
                if (variation == InputType.TYPE_TEXT_VARIATION_URI) {
                    return FieldType.URL
                }
            }

            if (cls == InputType.TYPE_CLASS_NUMBER) {
                if (variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD) {
                    return FieldType.PASSWORD
                }
                if (looksLikeOtp(info)) {
                    return FieldType.OTP
                }
            }

            if (cls == InputType.TYPE_CLASS_PHONE) {
                return FieldType.PHONE
            }

            FieldType.NORMAL
        } catch (e: Exception) {
            FieldType.NORMAL
        }
    }

    private fun looksLikeOtp(info: EditorInfo): Boolean {
        return try {
            val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                info.hintText?.toString()
            } else null
            val label = info.label?.toString()
            val combined = ((hint ?: "") + " " + (label ?: "")).lowercase()
            combined.contains("otp") || combined.contains("verification") || combined.contains("one-time")
        } catch (e: Exception) {
            false
        }
    }
}
