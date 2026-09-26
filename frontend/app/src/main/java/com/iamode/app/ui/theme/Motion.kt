package com.iamode.app.ui.theme

import androidx.compose.animation.core.CubicBezierEasing

/**
 * One motion vocabulary for the whole app (Material 3 emphasized curves), so every
 * transition feels like part of the same system instead of a mix of defaults.
 */
object Motion {
    val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    const val SHORT = 150
    const val MEDIUM = 300
    const val LONG = 450
}
