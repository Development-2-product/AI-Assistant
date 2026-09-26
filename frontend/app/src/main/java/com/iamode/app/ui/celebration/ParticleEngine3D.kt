package com.iamode.app.ui.celebration

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.iamode.app.domain.mail.CelebrationType
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Visual recipe per celebration: bigger news gets a bigger, richer blast. */
data class BlastStyle(
    val palette: List<Color>,
    val particles: Int,
    val burstSpeed: Float,
    val confettiShare: Float,
    val sparkShare: Float,
    val rings: Boolean,
) {
    companion object {
        fun of(type: CelebrationType): BlastStyle = when (type) {
            CelebrationType.SELECTION -> BlastStyle(
                listOf(Color(0xFFFFD166), Color(0xFFFFB703), Color(0xFFFFF3C4), Color(0xFFF4A261), Color.White),
                particles = 320, burstSpeed = 2100f, confettiShare = 0.45f, sparkShare = 0.2f, rings = false)
            CelebrationType.OFFER -> BlastStyle(
                listOf(Color(0xFF06D6A0), Color(0xFF2EC4B6), Color(0xFFFFD166), Color(0xFFE8FFF6), Color.White),
                particles = 300, burstSpeed = 1900f, confettiShare = 0.4f, sparkShare = 0.25f, rings = false)
            CelebrationType.INTERVIEW -> BlastStyle(
                listOf(Color(0xFF7B61FF), Color(0xFFA78BFA), Color(0xFF60A5FA), Color(0xFFE0E7FF)),
                particles = 190, burstSpeed = 1300f, confettiShare = 0.15f, sparkShare = 0.35f, rings = true)
            CelebrationType.APPLICATION -> BlastStyle(
                listOf(Color(0xFF3B82F6), Color(0xFF60A5FA), Color(0xFF93C5FD), Color.White),
                particles = 110, burstSpeed = 1000f, confettiShare = 0.2f, sparkShare = 0.3f, rings = false)
            CelebrationType.CONGRATULATIONS -> BlastStyle(
                listOf(Color(0xFFFF6B6B), Color(0xFFFFD166), Color(0xFF06D6A0), Color(0xFF4CC9F0), Color(0xFFB388EB)),
                particles = 240, burstSpeed = 1600f, confettiShare = 0.65f, sparkShare = 0.1f, rings = false)
        }
    }
}

/**
 * A real 3D particle system: positions/velocities in world space, gravity + drag, a moving camera,
 * perspective projection and depth sorting. Struct-of-arrays storage and pre-rendered glow sprites mean
 * zero allocations per frame, so it stays smooth and never triggers garbage collection mid-animation.
 */
class ParticleEngine3D(private val style: BlastStyle, qualityScale: Float, seed: Int) {
    private val n = max(24, (style.particles * qualityScale).toInt())
    private val rnd = Random(seed)

    // world state
    private val px = FloatArray(n); private val py = FloatArray(n); private val pz = FloatArray(n)
    private val sx0 = FloatArray(n); private val sy0 = FloatArray(n); private val sz0 = FloatArray(n)
    private val vx = FloatArray(n); private val vy = FloatArray(n); private val vz = FloatArray(n)
    private val psize = FloatArray(n); private val life = FloatArray(n); private val decay = FloatArray(n)
    private val rot = FloatArray(n); private val rotSpeed = FloatArray(n); private val phase = FloatArray(n)
    private val kind = IntArray(n)      // 0 glow orb, 1 confetti, 2 spark
    private val colorIx = IntArray(n)
    // per-frame projection
    private val scrX = FloatArray(n); private val scrY = FloatArray(n); private val scale = FloatArray(n); private val depth = FloatArray(n)
    private val prevX = FloatArray(n); private val prevY = FloatArray(n)
    private val order = IntArray(n) { it }

    val burstTime = 0.52f
    private var spriteCache: Array<ImageBitmap>? = null
    private var burst = false
    var time = 0f; private set

    init {
        for (i in 0 until n) {
            // start on a wide shell around the viewer, then gather into the centre
            val u = rnd.nextFloat() * 2f - 1f
            val t = rnd.nextFloat() * 2f * PI.toFloat()
            val r = 650f + rnd.nextFloat() * 450f
            val s = sqrt(1f - u * u)
            sx0[i] = r * s * cos(t); sy0[i] = r * u; sz0[i] = r * s * sin(t)
            px[i] = sx0[i]; py[i] = sy0[i]; pz[i] = sz0[i]
            val roll = rnd.nextFloat()
            kind[i] = when {
                roll < style.confettiShare -> 1
                roll < style.confettiShare + style.sparkShare -> 2
                else -> 0
            }
            psize[i] = when (kind[i]) { 1 -> 9f + rnd.nextFloat() * 9f; 2 -> 3f + rnd.nextFloat() * 3f; else -> 14f + rnd.nextFloat() * 26f }
            life[i] = 1f
            decay[i] = when (kind[i]) { 1 -> 0.16f + rnd.nextFloat() * 0.08f; 2 -> 0.55f + rnd.nextFloat() * 0.3f; else -> 0.35f + rnd.nextFloat() * 0.25f }
            rot[i] = rnd.nextFloat() * 360f
            rotSpeed[i] = (rnd.nextFloat() - 0.5f) * 720f
            phase[i] = rnd.nextFloat() * 6.28f
            colorIx[i] = rnd.nextInt(style.palette.size)
        }
    }

    /** Glow sprites are rendered once (radial gradient -> bitmap) and reused every frame. */
    private fun sprites(): Array<ImageBitmap> = spriteCache ?: Array(style.palette.size) { i ->
        val dim = 64
        val bmp = Bitmap.createBitmap(dim, dim, Bitmap.Config.ARGB_8888)
        val c = style.palette[i]
        val argb = android.graphics.Color.argb((c.alpha * 255).toInt(), (c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(dim / 2f, dim / 2f, dim / 2f,
                intArrayOf(android.graphics.Color.WHITE, argb, android.graphics.Color.TRANSPARENT), floatArrayOf(0f, 0.28f, 1f), Shader.TileMode.CLAMP)
        }
        android.graphics.Canvas(bmp).drawCircle(dim / 2f, dim / 2f, dim / 2f, paint)
        bmp.asImageBitmap()
    }.also { spriteCache = it }


    /** Advances the simulation. [dt] is clamped by the caller so a paused app doesn't jump. */
    fun step(dt: Float) {
        time += dt
        if (time < burstTime) {
            // gather: ease-in toward the centre with a slight swirl
            val k = time / burstTime
            val e = k * k * k
            val swirl = k * 2.2f
            val cs = cos(swirl); val sn = sin(swirl)
            for (i in 0 until n) {
                val x = sx0[i] * (1f - e); val z = sz0[i] * (1f - e)
                px[i] = x * cs - z * sn; pz[i] = x * sn + z * cs; py[i] = sy0[i] * (1f - e)
            }
            return
        }
        if (!burst) {
            burst = true
            for (i in 0 until n) {
                val u = rnd.nextFloat() * 2f - 1f
                val t = rnd.nextFloat() * 2f * PI.toFloat()
                val s = sqrt(1f - u * u)
                val speed = style.burstSpeed * (0.35f + rnd.nextFloat() * 0.75f) * (if (kind[i] == 2) 1.35f else 1f)
                vx[i] = s * cos(t) * speed; vy[i] = u * speed - (if (kind[i] == 1) 380f else 0f); vz[i] = s * sin(t) * speed
                px[i] = vx[i] * 0.004f; py[i] = vy[i] * 0.004f; pz[i] = vz[i] * 0.004f
            }
        }
        val dragOrb = exp(-2.2f * dt); val dragConf = exp(-1.7f * dt); val dragSpark = exp(-1.2f * dt)
        for (i in 0 until n) {
            if (life[i] <= 0f) continue
            when (kind[i]) {
                1 -> { // confetti: gravity + flutter
                    vy[i] += 620f * dt
                    vx[i] += sin(time * 6f + phase[i]) * 90f * dt
                    vx[i] *= dragConf; vy[i] *= dragConf; vz[i] *= dragConf
                    rot[i] += rotSpeed[i] * dt
                }
                2 -> { vx[i] *= dragSpark; vy[i] *= dragSpark; vz[i] *= dragSpark }
                else -> { vy[i] += 40f * dt; vx[i] *= dragOrb; vy[i] *= dragOrb; vz[i] *= dragOrb }
            }
            px[i] += vx[i] * dt; py[i] += vy[i] * dt; pz[i] += vz[i] * dt
            life[i] -= decay[i] * dt * (if (time > 2.6f) 1.8f else 1f)
        }
    }

    /** Projects every particle through the moving camera and draws far-to-near. */
    fun draw(scope: DrawScope, dimAlpha: Float) = with(scope) {
        val cx = size.width / 2f
        val cy = size.height * 0.42f
        val focal = min(size.width, size.height) * 1.15f
        val yaw = sin(time * 0.55f) * 0.14f
        val pitch = -0.12f + cos(time * 0.4f) * 0.05f
        val camZ = 1250f - min(time, 1.6f) * 90f // gentle dolly-in
        val cyw = cos(yaw); val syw = sin(yaw); val cp = cos(pitch); val sp = sin(pitch)
        val spr = sprites()

        for (i in 0 until n) {
            prevX[i] = scrX[i]; prevY[i] = scrY[i]
            val x1 = px[i] * cyw + pz[i] * syw
            val z1 = -px[i] * syw + pz[i] * cyw
            val y2 = py[i] * cp - z1 * sp
            val z2 = py[i] * sp + z1 * cp
            val d = z2 + camZ
            depth[i] = d
            val s = if (d > 60f) focal / d else 0f
            scale[i] = s
            scrX[i] = cx + x1 * s
            scrY[i] = cy + y2 * s
        }
        // insertion sort by depth (nearly sorted frame to frame -> ~O(n), no allocation)
        for (a in 1 until n) {
            val key = order[a]; var b = a - 1
            while (b >= 0 && depth[order[b]] < depth[key]) { order[b + 1] = order[b]; b-- }
            order[b + 1] = key
        }

        // core glow + burst flash
        if (time < burstTime + 0.6f) {
            val g = if (time < burstTime) (time / burstTime) else max(0f, 1f - (time - burstTime) / 0.6f)
            drawCircle(
                brush = androidx.compose.ui.graphics.Brush.radialGradient(
                    listOf(Color.White.copy(alpha = 0.95f * g), style.palette[0].copy(alpha = 0.55f * g), Color.Transparent),
                    center = Offset(cx, cy), radius = 60f + g * 220f + (if (time > burstTime) (time - burstTime) * 900f else 0f),
                ),
                radius = 60f + g * 220f + (if (time > burstTime) (time - burstTime) * 900f else 0f), center = Offset(cx, cy),
                blendMode = BlendMode.Plus,
            )
        }

        for (o in 0 until n) {
            val i = order[o]
            val s = scale[i]
            if (s <= 0f || life[i] <= 0f) continue
            val alpha = min(1f, life[i] * 1.4f) * (if (time < burstTime) 0.4f + 0.6f * (time / burstTime) else 1f)
            val c = style.palette[colorIx[i]]
            when (kind[i]) {
                0 -> {
                    val r = (psize[i] * s * 2.2f).toInt().coerceIn(2, 220)
                    drawImage(spr[colorIx[i]], dstOffset = IntOffset((scrX[i] - r / 2f).toInt(), (scrY[i] - r / 2f).toInt()),
                        dstSize = IntSize(r, r), alpha = alpha, blendMode = BlendMode.Plus)
                }
                1 -> {
                    val w = psize[i] * s; val h = w * 0.45f
                    // fake 3D spin: the ribbon's visible width follows its rotation
                    val flip = 0.25f + 0.75f * kotlin.math.abs(cos((rot[i] + phase[i] * 57f) * 0.0174f))
                    rotate(rot[i], Offset(scrX[i], scrY[i])) {
                        drawRect(c.copy(alpha = alpha), Offset(scrX[i] - w / 2f, scrY[i] - h * flip / 2f), Size(w, h * flip))
                    }
                }
                else -> if (time > burstTime + 0.02f) {
                    drawLine(c.copy(alpha = alpha), Offset(prevX[i], prevY[i]), Offset(scrX[i], scrY[i]),
                        strokeWidth = max(1.5f, psize[i] * s), blendMode = BlendMode.Plus)
                }
            }
        }
    }
}
