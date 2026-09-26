package com.iamode.app.ui.feature.mail

import com.iamode.app.core.i18n.tr

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.iamode.app.data.mail.PdfHandle
import com.iamode.app.data.mail.Viewable
import com.iamode.app.ui.components.SkeletonBlock
import kotlinx.coroutines.launch

/** Full-screen document viewer: all PDF pages (rendered on demand), pinch/double-tap zoom, text previews. */
@Composable
fun DocumentViewer(name: String, load: suspend () -> Viewable, onClose: () -> Unit) {
    val content by produceState<Viewable?>(null) { value = load() }
    DisposableEffect(content) {
        val c = content
        onDispose { if (c is Viewable.Pdf) c.doc.close() }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color(0xFF1B1B1F))) {
            when (val c = content) {
                null -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
                is Viewable.Pdf -> PdfPages(c.doc)
                is Viewable.Image -> Zoomable { Image(c.bitmap.asImageBitmap(), name, Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth) }
                is Viewable.Text -> TextPage(c.text)
                Viewable.Locked -> Message("🔒", tr("Password-protected"), tr("IA Mode can't show this PDF. If you send it, the recipient will need the password."))
                Viewable.Unsupported -> Message("📄", tr("No preview for this file type"), tr("It will be attached exactly as it is."))
                is Viewable.Failed -> Message("⚠️", tr("Can't open this file"), c.reason)
            }
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, tr("Close"), tint = Color.White) }
                Text(name, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun PdfPages(doc: PdfHandle) {
    val list = rememberLazyListState()
    var chipVisible by remember { mutableStateOf(true) }
    LaunchedEffect(list.isScrollInProgress) {
        if (list.isScrollInProgress) chipVisible = true else { kotlinx.coroutines.delay(1500); chipVisible = false }
    }
    Box(Modifier.fillMaxSize()) {
        Zoomable {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val widthPx = with(LocalDensity.current) { maxWidth.toPx().toInt() }
                LazyColumn(state = list, contentPadding = PaddingValues(top = 72.dp, bottom = 32.dp, start = 12.dp, end = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(doc.pageCount) { i ->
                        val page by produceState<android.graphics.Bitmap?>(null, i, widthPx) { value = runCatching { doc.render(i, widthPx) }.getOrNull() }
                        Box(Modifier.fillMaxWidth().aspectRatio(1f / doc.firstAspect).shadow(6.dp, RoundedCornerShape(4.dp))
                            .clip(RoundedCornerShape(4.dp)).background(Color.White)) {
                            val bmp = page
                            if (bmp == null) SkeletonBlock(Modifier.fillMaxSize(), height = 1000.dp)
                            else Image(bmp.asImageBitmap(), tr("Page %1\$s", (i + 1)), Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                        }
                    }
                }
            }
        }
        AnimatedVisibility(chipVisible, Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp), enter = fadeIn(), exit = fadeOut()) {
            Surface(shape = RoundedCornerShape(50), color = Color.Black.copy(alpha = 0.7f)) {
                Text("${list.firstVisibleItemIndex + 1} / ${doc.pageCount}", color = Color.White,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/** Pinch to zoom (1x–4x), drag while zoomed, double-tap toggles 1x / 2.5x with a spring. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Zoomable(content: @Composable () -> Unit) {
    val scale = remember { Animatable(1f) }
    val offset = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    val scope = rememberCoroutineScope()
    val state = rememberTransformableState { zoom, pan, _ ->
        scope.launch {
            val s = (scale.value * zoom).coerceIn(1f, 4f)
            scale.snapTo(s)
            offset.snapTo(if (s == 1f) Offset.Zero else offset.value + pan)
        }
    }
    Box(
        Modifier.fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { tap ->
                    scope.launch {
                        val zoomIn = scale.value < 1.5f
                        launch { scale.animateTo(if (zoomIn) 2.5f else 1f, spring(dampingRatio = 0.8f)) }
                        offset.animateTo(if (zoomIn) Offset((size.width / 2f - tap.x) * 1.5f, (size.height / 2f - tap.y) * 1.5f) else Offset.Zero,
                            spring(dampingRatio = 0.8f))
                    }
                })
            }
            .transformable(state, canPan = { scale.value > 1f })
            .graphicsLayer { scaleX = scale.value; scaleY = scale.value; translationX = offset.value.x; translationY = offset.value.y },
    ) { content() }
}

@Composable
private fun TextPage(text: String) = Box(Modifier.fillMaxSize().padding(top = 64.dp)) {
    Surface(Modifier.padding(12.dp).fillMaxSize(), shape = RoundedCornerShape(6.dp), color = Color.White, shadowElevation = 6.dp) {
        Text(text, color = Color(0xFF1F1F1F), style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(24.dp))
    }
}

@Composable
private fun Message(glyph: String, title: String, body: String) = Column(
    Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
) {
    Text(glyph, style = MaterialTheme.typography.displayMedium)
    Text(title, color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 12.dp))
    Text(body, color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
}
