package com.iamode.app.ui.components

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.iamode.app.ui.theme.Motion
import kotlinx.coroutines.delay
import kotlin.math.min

/** Cards sink slightly under the finger and spring back: physical, not flashy. */
fun Modifier.pressScale(interaction: InteractionSource, pressed: Float = 0.975f): Modifier = composed {
    val isPressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (isPressed) pressed else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium), label = "press",
    )
    graphicsLayer { scaleX = scale; scaleY = scale }
}

/** A soft highlight sweeping across placeholders while real content loads. */
@Composable
fun shimmerBrush(): Brush {
    val base = MaterialTheme.colorScheme.surfaceContainerHighest
    val highlight = MaterialTheme.colorScheme.surfaceContainerLow
    val t = rememberInfiniteTransition(label = "shimmer")
    val x by t.animateFloat(-400f, 1400f, infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Restart), label = "x")
    return Brush.linearGradient(listOf(base, highlight, base), start = Offset(x, 0f), end = Offset(x + 400f, 200f))
}

@Composable
fun SkeletonBlock(modifier: Modifier = Modifier, height: Dp = 14.dp, brush: Brush = shimmerBrush()) =
    Box(modifier.height(height).clip(RoundedCornerShape(8.dp)).background(brush))

/** Placeholder shaped like a mail card, so the layout doesn't jump when mail arrives. */
@Composable
fun MailCardSkeleton(modifier: Modifier = Modifier) {
    val brush = shimmerBrush()
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(brush))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SkeletonBlock(Modifier.fillMaxWidth(0.45f), brush = brush)
                SkeletonBlock(Modifier.fillMaxWidth(0.75f), brush = brush)
            }
        }
        SkeletonBlock(Modifier.fillMaxWidth(), brush = brush)
        SkeletonBlock(Modifier.fillMaxWidth(0.6f), brush = brush)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SkeletonBlock(Modifier.width(88.dp), height = 24.dp, brush = brush)
            SkeletonBlock(Modifier.width(64.dp), height = 24.dp, brush = brush)
        }
    }
}

/**
 * Items rise and fade in once, staggered by position. [seen] remembers keys that already played,
 * so scrolling back or data updates never replay the entrance.
 */
@Composable
fun Entrance(key: String, index: Int, seen: MutableSet<String>, content: @Composable () -> Unit) {
    val first = remember(key) { key !in seen }
    val p = remember(key) { Animatable(if (first) 0f else 1f) }
    LaunchedEffect(key) {
        if (first) {
            seen += key
            delay(min(index, 8) * 45L)
            p.animateTo(1f, tween(Motion.LONG, easing = Motion.EmphasizedDecelerate))
        }
    }
    Box(Modifier.graphicsLayer { alpha = p.value; translationY = (1f - p.value) * 24.dp.toPx() }) { content() }
}

/** Numbers roll up or down instead of snapping. */
@Composable
fun AnimatedCounter(value: Int, style: TextStyle = MaterialTheme.typography.labelMedium, color: Color = Color.Unspecified) {
    AnimatedContent(
        targetState = value,
        transitionSpec = {
            val up = targetState > initialState
            (slideInVertically(tween(Motion.MEDIUM, easing = Motion.EmphasizedDecelerate)) { if (up) it else -it } + fadeIn()) togetherWith
                (slideOutVertically(tween(Motion.SHORT)) { if (up) -it else it } + fadeOut())
        },
        label = "counter",
    ) { Text("$it", style = style, color = color) }
}

/** Ring sweeps, then the check draws itself, with a springy settle. */
@Composable
fun SuccessCheck(modifier: Modifier = Modifier, size: Dp = 96.dp, color: Color = Color(0xFF12B886)) {
    val ring = remember { Animatable(0f) }
    val check = remember { Animatable(0f) }
    val pop = remember { Animatable(0.6f) }
    val haptics = rememberHaptics()
    LaunchedEffect(Unit) {
        ring.animateTo(1f, tween(420, easing = Motion.EmphasizedDecelerate))
        haptics.confirm()
        pop.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMedium))
    }
    LaunchedEffect(Unit) { delay(320); check.animateTo(1f, tween(360, easing = Motion.EmphasizedDecelerate)) }
    Canvas(modifier.size(size).graphicsLayer { scaleX = pop.value; scaleY = pop.value }) {
        val stroke = this.size.minDimension * 0.07f
        drawCircle(color.copy(alpha = 0.12f * ring.value))
        drawArc(color, -90f, 360f * ring.value, false, style = Stroke(stroke, cap = StrokeCap.Round),
            topLeft = Offset(stroke, stroke), size = androidx.compose.ui.geometry.Size(this.size.width - 2 * stroke, this.size.height - 2 * stroke))
        val a = Offset(this.size.width * 0.30f, this.size.height * 0.52f)
        val b = Offset(this.size.width * 0.44f, this.size.height * 0.66f)
        val c = Offset(this.size.width * 0.71f, this.size.height * 0.38f)
        val p = check.value
        val first = min(1f, p * 2.2f)
        drawLine(color, a, Offset(a.x + (b.x - a.x) * first, a.y + (b.y - a.y) * first), stroke, StrokeCap.Round)
        if (p > 0.45f) {
            val second = ((p - 0.45f) / 0.55f).coerceIn(0f, 1f)
            drawLine(color, b, Offset(b.x + (c.x - b.x) * second, b.y + (c.y - b.y) * second), stroke, StrokeCap.Round)
        }
    }
}

class Haptics(private val view: android.view.View) {
    fun confirm() = view.performHapticFeedback(
        if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS,
    )
    fun tick() = view.performHapticFeedback(
        if (Build.VERSION.SDK_INT >= 27) HapticFeedbackConstants.KEYBOARD_RELEASE else HapticFeedbackConstants.VIRTUAL_KEY,
    )
    fun threshold() = view.performHapticFeedback(
        if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE else HapticFeedbackConstants.CLOCK_TICK,
    )
}

@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    return remember(view) { Haptics(view) }
}
