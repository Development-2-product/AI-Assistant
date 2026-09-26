package com.iamode.app.ui.components

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.iamode.app.ui.theme.Motion

@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedTransitionScope = compositionLocalOf<SharedTransitionScope?> { null }
val LocalNavAnimatedVisibilityScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

@OptIn(ExperimentalSharedTransitionApi::class)
private val sharedBounds = BoundsTransform { _, _ -> tween(Motion.LONG, easing = Motion.Emphasized) }

/**
 * Moves this element between screens (e.g. a contact's avatar and name fly from the list into the
 * conversation header). Does nothing when there is no navigation transition around it.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedTransition(key: String): Modifier {
    val shared = LocalSharedTransitionScope.current ?: return this
    val animated = LocalNavAnimatedVisibilityScope.current ?: return this
    return with(shared) {
        this@sharedTransition.sharedElement(
            rememberSharedContentState(key = key),
            animatedVisibilityScope = animated,
            boundsTransform = sharedBounds,
        )
    }
}

/** Keeps content at a comfortable reading width on tablets, foldables and landscape. */
fun Modifier.readableWidth(max: Int = 720): Modifier =
    fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = max.dp).fillMaxWidth()
