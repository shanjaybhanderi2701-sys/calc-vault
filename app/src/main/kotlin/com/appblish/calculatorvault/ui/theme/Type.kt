package com.appblish.calculatorvault.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Type ramp from the deck: very large bold numerals (calculator display), bold
// large-title headers, medium titles, regular body, muted labels. Uses the
// platform default family (system-native, matches the deck's neutral sans);
// swap FontFamily here when a bespoke face is licensed.
private val Family = FontFamily.Default

internal val CalculatorVaultTypography =
    Typography(
        // Calculator display numeral.
        displayLarge =
            TextStyle(
                fontFamily = Family,
                fontWeight = FontWeight.Light,
                fontSize = 64.sp,
                lineHeight = 72.sp,
            ),
        displayMedium =
            TextStyle(
                fontFamily = Family,
                fontWeight = FontWeight.Normal,
                fontSize = 48.sp,
                lineHeight = 56.sp,
            ),
        // Large-title screen headers ("CalcVault", "Create PIN").
        headlineMedium =
            TextStyle(
                fontFamily = Family,
                fontWeight = FontWeight.Bold,
                fontSize = 28.sp,
                lineHeight = 34.sp,
            ),
        headlineSmall =
            TextStyle(
                fontFamily = Family,
                fontWeight = FontWeight.SemiBold,
                fontSize = 22.sp,
                lineHeight = 28.sp,
            ),
        // Section titles / card titles.
        titleLarge =
            TextStyle(
                fontFamily = Family,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
                lineHeight = 24.sp,
            ),
        titleMedium =
            TextStyle(
                fontFamily = Family,
                fontWeight = FontWeight.Medium,
                fontSize = 16.sp,
                lineHeight = 22.sp,
            ),
        // List-row primary / body.
        bodyLarge =
            TextStyle(
                fontFamily = Family,
                fontWeight = FontWeight.Normal,
                fontSize = 16.sp,
                lineHeight = 22.sp,
            ),
        bodyMedium =
            TextStyle(
                fontFamily = Family,
                fontWeight = FontWeight.Normal,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            ),
        // Muted secondary / captions / counts.
        labelLarge =
            TextStyle(
                fontFamily = Family,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                lineHeight = 18.sp,
            ),
        labelMedium =
            TextStyle(
                fontFamily = Family,
                fontWeight = FontWeight.Normal,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            ),
    )
