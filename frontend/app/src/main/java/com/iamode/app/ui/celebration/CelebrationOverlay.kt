package com.iamode.app.ui.celebration

import com.iamode.app.core.i18n.tr

import android.app.ActivityManager
import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.provider.Settings
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import com.iamode.app.core.database.entity.CelebrationEntity
import com.iamode.app.data.mail.CelebrationCoordinator
import com.iamode.app.domain.mail.CelebrationType
import com.iamode.app.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

data class ActiveCelebration(val entity: CelebrationEntity, val type: CelebrationType, val replay: Boolean)

@HiltViewModel
class CelebrationViewModel @Inject constructor(
    private val coordinator: CelebrationCoordinator,
    settings: SettingsRepository,
) : ViewModel() {
    var active by mutableStateOf<ActiveCelebration?>(null)
        private set
    val sound = settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        viewModelScope.launch {
            coordinator.next.collect { c ->
                if (c == null || active != null) return@collect
                val type = CelebrationType.entries.firstOrNull { it.name == c.type } ?: return@collect
                // Claim first: only the caller that flips ELIGIBLE -> SHOWN plays it. Same email = once.
                if (coordinator.claim(c.id)) active = ActiveCelebration(c, type, replay = false)
            }
        }
        viewModelScope.launch {
            coordinator.replays.collect { c ->
                val type = CelebrationType.entries.firstOrNull { it.name == c.type } ?: return@collect
                active = ActiveCelebration(c, type, replay = true)
            }
        }
    }

    fun dismiss() { active = null }
}

/** Device capability for the effect: fewer particles on low-end phones, a static card when motion is off. */
private data class MotionProfile(val reducedMotion: Boolean, val quality: Float)

private fun motionProfile(context: Context): MotionProfile {
    val scale = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val quality = when {
        am.isLowRamDevice -> 0.35f
        am.memoryClass < 192 -> 0.55f
        Runtime.getRuntime().availableProcessors() <= 4 -> 0.7f
        else -> 1f
    }
    return MotionProfile(reducedMotion = scale == 0f, quality = quality)
}

/**
 * Wraps the whole app. When a celebration is active the app behind dims and blurs (API 31+),
 * the 3D blast plays, then the achievement card animates in. Nothing here blocks the screens below
 * from loading; the overlay only draws on top.
 */
@Composable
fun CelebrationHost(onOpenEmail: (String) -> Unit, content: @Composable (Modifier) -> Unit) {
    val vm: CelebrationViewModel = hiltViewModel()
    val active = vm.active
    val blur = remember { Animatable(0f) }
    LaunchedEffect(active) { blur.animateTo(if (active != null) 14f else 0f, tween(if (active != null) 380 else 260)) }

    Box(Modifier.fillMaxSize()) {
        content(if (blur.value > 0.1f) Modifier.fillMaxSize().blur(blur.value.dp) else Modifier.fillMaxSize())
        if (active != null) {
            val settings by vm.sound.collectAsStateWithLifecycle()
            CelebrationOverlay(
                celebration = active,
                soundEnabled = settings?.celebrationSound == true,
                onDetails = { vm.dismiss(); onOpenEmail(active.entity.emailId) },
                onClose = vm::dismiss,
            )
        }
    }
}

@Composable
private fun CelebrationOverlay(celebration: ActiveCelebration, soundEnabled: Boolean, onDetails: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val profile = remember { motionProfile(context) }
    val style = remember(celebration.type) { BlastStyle.of(celebration.type) }
    val engine = remember(celebration) {
        if (profile.reducedMotion) null else ParticleEngine3D(style, profile.quality, celebration.entity.id.hashCode())
    }
    var frame by remember { mutableLongStateOf(0L) }  // read only in the draw phase -> no recomposition per frame

    val dim = remember { Animatable(0f) }
    val card = remember { Animatable(0f) }
    val flip = remember { Animatable(if (celebration.type == CelebrationType.OFFER) 180f else 70f) }
    val text = remember { Animatable(0f) }
    val exit = remember { Animatable(1f) }
    var closing by remember { mutableStateOf(false) }

    fun close(then: () -> Unit) {
        if (closing) return
        closing = true
        then()
    }

    // Frame loop: runs only while the app is RESUMED (pauses in background), with a clamped time step.
    LaunchedEffect(engine) {
        engine ?: return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var last = 0L
            var burstFelt = false
            while (isActive && engine.time < 5.5f) {
                androidx.compose.runtime.withFrameNanos { now ->
                    val dt = if (last == 0L) 0.016f else min(0.033f, (now - last) / 1e9f)
                    last = now
                    engine.step(dt)
                    frame = now
                }
                if (!burstFelt && engine.time >= engine.burstTime) {
                    burstFelt = true
                    view.performHapticFeedback(
                        if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS,
                    )
                    if (soundEnabled) launch { // never inside the frame loop
                        runCatching {
                            val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 55)
                            tone.startTone(ToneGenerator.TONE_PROP_ACK, 160)
                            delay(250)
                            tone.release()
                        }
                    }
                }
            }
        }
    }

    // Choreography: dim -> (blast) -> card reveal -> staggered text -> auto-dismiss.
    LaunchedEffect(celebration) {
        launch { dim.animateTo(1f, tween(320)) }
        delay(if (engine == null) 60 else 600)
        launch { card.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMediumLow)) }
        launch { flip.animateTo(0f, tween(if (celebration.type == CelebrationType.OFFER) 900 else 560, easing = FastOutSlowInEasing)) }
        delay(220)
        text.animateTo(1f, tween(700, easing = FastOutSlowInEasing))
        delay(if (engine == null) 4000 else 4400)
        exit.animateTo(0f, tween(320))
        close(onClose)
    }

    val scrim = Brush.radialGradient(listOf(Color(0xE6120A24), Color(0xF2050309)))
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = exit.value }
            .background(scrim, alpha = 0.85f * dim.value)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { close(onClose) }
            .semantics { liveRegion = LiveRegionMode.Polite },
        contentAlignment = Alignment.Center,
    ) {
        if (engine != null) {
            Canvas(Modifier.fillMaxSize()) {
                if (frame < 0L) return@Canvas // reading the tick subscribes only the draw phase
                if (style.rings && engine.time > engine.burstTime) drawTargetRings(engine.time - engine.burstTime, style.palette)
                engine.draw(this, dim.value)
            }
        }
        AchievementCard(celebration, style, card.value, flip.value, text.value, onDetails = { close(onDetails) }, onClose = { close(onClose) })
    }
}

/** Interview variant: tilted concentric "target" rings expanding from the burst. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTargetRings(t: Float, palette: List<Color>) {
    val cx = size.width / 2f
    val cy = size.height * 0.42f
    for (k in 0 until 3) {
        val local = t - k * 0.18f
        if (local <= 0f) continue
        val r = 90f + local * 520f
        val a = max(0f, 1f - local / 1.6f)
        drawOval(
            color = palette[k % palette.size].copy(alpha = 0.65f * a),
            topLeft = Offset(cx - r, cy - r * 0.42f),
            size = Size(r * 2f, r * 0.84f),
            style = Stroke(width = 5f * a + 1f),
        )
    }
}

@Composable
private fun AchievementCard(
    c: ActiveCelebration, style: BlastStyle, appear: Float, flip: Float, textIn: Float,
    onDetails: () -> Unit, onClose: () -> Unit,
) {
    val density = LocalDensity.current
    val accent = style.palette[0]
    val (badge, kicker) = when (c.type) {
        CelebrationType.SELECTION -> "🏆" to "CONGRATULATIONS"
        CelebrationType.OFFER -> "💼" to tr("IT'S OFFICIAL")
        CelebrationType.INTERVIEW -> "🎯" to tr("GREAT NEWS")
        CelebrationType.APPLICATION -> "✓" to "SUCCESS"
        CelebrationType.CONGRATULATIONS -> "🎉" to tr("WELL DONE")
    }
    val lines = c.entity.subtitle?.split('\n')?.filter { it.isNotBlank() }.orEmpty()
    Column(
        Modifier
            .padding(28.dp)
            .widthIn(max = 360.dp)
            .graphicsLayer {
                alpha = appear.coerceIn(0f, 1f)
                scaleX = 0.72f + 0.28f * appear; scaleY = 0.72f + 0.28f * appear
                if (c.type == CelebrationType.OFFER) rotationY = flip else rotationX = flip
                cameraDistance = 14f * density.density
            }
            .background(
                Brush.verticalGradient(listOf(Color(0xFF221A3D), Color(0xFF120E22))), RoundedCornerShape(32.dp),
            )
            .border(1.dp, Brush.linearGradient(listOf(accent.copy(alpha = 0.9f), Color.White.copy(alpha = 0.12f), accent.copy(alpha = 0.5f))),
                RoundedCornerShape(32.dp))
            .padding(horizontal = 26.dp, vertical = 30.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(96.dp), contentAlignment = Alignment.Center) {
            if (c.type == CelebrationType.APPLICATION) {
                // Checkmark draws itself in.
                Canvas(Modifier.size(96.dp)) {
                    drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = 0.55f), Color.Transparent)))
                    drawCircle(accent, radius = size.minDimension * 0.34f, style = Stroke(6f))
                    val p = textIn.coerceIn(0f, 1f)
                    val a = Offset(size.width * 0.34f, size.height * 0.52f)
                    val b = Offset(size.width * 0.46f, size.height * 0.64f)
                    val e = Offset(size.width * 0.68f, size.height * 0.40f)
                    val first = min(1f, p * 2f)
                    drawLine(Color.White, a, Offset(a.x + (b.x - a.x) * first, a.y + (b.y - a.y) * first), 9f, StrokeCap.Round)
                    if (p > 0.5f) {
                        val second = (p - 0.5f) * 2f
                        drawLine(Color.White, b, Offset(b.x + (e.x - b.x) * second, b.y + (e.y - b.y) * second), 9f, StrokeCap.Round)
                    }
                }
            } else {
                Box(
                    Modifier.size(88.dp)
                        .background(Brush.radialGradient(listOf(accent.copy(alpha = 0.55f), accent.copy(alpha = 0.08f))), CircleShape)
                        .border(2.dp, Brush.sweepGradient(listOf(accent, Color.White, accent)), CircleShape)
                        .graphicsLayer {
                            val pop = 0.6f + 0.4f * appear + 0.06f * sin(textIn * 6.28f)
                            scaleX = pop; scaleY = pop
                        },
                    contentAlignment = Alignment.Center,
                ) { Text(badge, fontSize = 40.sp) }
            }
        }
        Spacer(Modifier.height(18.dp))
        StaggeredLine(textIn, 0) {
            Text(kicker, color = accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 4.sp)
        }
        StaggeredLine(textIn, 1) {
            Text(tr(c.entity.title ?: c.type.headline), color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center, lineHeight = 36.sp, modifier = Modifier.padding(top = 6.dp))
        }
        lines.forEachIndexed { i, line ->
            StaggeredLine(textIn, 2 + i) {
                Text(line, color = Color.White.copy(alpha = if (i == 0) 0.92f else 0.7f),
                    fontSize = if (i == 0) 18.sp else 15.sp, fontWeight = if (i == 0) FontWeight.Medium else FontWeight.Normal,
                    textAlign = TextAlign.Center, modifier = Modifier.padding(top = if (i == 0) 10.dp else 2.dp))
            }
        }
        if (c.replay) Text(tr("Replay"), color = Color.White.copy(alpha = 0.4f), fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(22.dp))
        StaggeredLine(textIn, 4) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)) {
                TextButton(onClick = onClose) { Text(tr("Close"), color = Color.White.copy(alpha = 0.75f)) }
                Button(onClick = onDetails, shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color(0xFF14101F))) {
                    Text(tr("View details"), fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun StaggeredLine(progress: Float, index: Int, content: @Composable () -> Unit) {
    val local = ((progress * 1.6f) - index * 0.15f).coerceIn(0f, 1f)
    Box(Modifier.graphicsLayer { alpha = local; translationY = (1f - local) * 18f * density }) { content() }
}
