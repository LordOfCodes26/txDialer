package com.goodwy.commons.compose.liquid

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.goodwy.commons.compose.liquid.utils.DampedDragAnimation
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.capsule.ContinuousCapsule
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@Composable
fun LiquidToggle(
    selected: () -> Boolean,
    onSelect: (Boolean) -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val isLightTheme = !isSystemInDarkTheme()
    val accentColor =
        if (isLightTheme) Color(0xFF34C759)
        else Color(0xFF30D158)
    val trackColor =
        if (isLightTheme) Color(0xFF787878).copy(0.2f)
        else Color(0xFF787880).copy(0.36f)

    val density = LocalDensity.current
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val dragWidth = with(density) { 10f.dp.toPx() }
    val animationScope = rememberCoroutineScope()

    var didDrag by remember { mutableStateOf(false) }
    var fraction by remember { mutableFloatStateOf(if (selected()) 1f else 0f) }

    val dampedDragAnimation = remember(animationScope) {
        DampedDragAnimation(
            animationScope = animationScope,
            initialValue = fraction,
            valueRange = 0f..1f,
            visibilityThreshold = 0.001f,
            initialScale = 1f,
            pressedScale = if (enabled) 1.5f else 1f,
            onDragStarted = {},
            onDragStopped = {
                if (!enabled) return@DampedDragAnimation

                if (didDrag) {
                    fraction = if (targetValue >= 0.5f) 1f else 0f
                    onSelect(fraction == 1f)
                    didDrag = false
                } else {
                    fraction = if (selected()) 0f else 1f
                    onSelect(fraction == 1f)
                }
            },
            onDrag = { _, dragAmount ->
                if (!enabled) return@DampedDragAnimation

                if (!didDrag) {
                    didDrag = dragAmount.x != 0f
                }
                val delta = dragAmount.x / dragWidth
                fraction =
                    if (isLtr) (fraction + delta).fastCoerceIn(0f, 1f)
                    else (fraction - delta).fastCoerceIn(0f, 1f)
            }
        )
    }

    // Sync animation with fraction
    LaunchedEffect(dampedDragAnimation) {
        snapshotFlow { fraction }
            .collectLatest { fraction ->
                dampedDragAnimation.updateValue(fraction)
            }
    }

    // External selected() change
    LaunchedEffect(selected) {
        snapshotFlow { selected() }
            .collectLatest { isSelected ->
                val target = if (isSelected) 1f else 0f
                if (target != fraction) {
                    fraction = target
                    dampedDragAnimation.animateToValue(target)
                }
            }
    }

    val trackBackdrop = rememberLayerBackdrop()

    Box(
        modifier,
        contentAlignment = Alignment.CenterStart
    ) {

        // TRACK
        Box(
            Modifier
                .layerBackdrop(trackBackdrop)
                .clip(ContinuousCapsule)
                .drawBehind {
                    val f = dampedDragAnimation.value
                    drawRect(
                        lerp(trackColor, accentColor, f).copy(
                            alpha = if (enabled) 1f else 0.4f
                        )
                    )
                }
                .size(44.dp, 20.dp)
        )

        // THUMB
        Box(
            Modifier
                .graphicsLayer {
                    val f = dampedDragAnimation.value
                    val padding = 2.dp.toPx()
                    translationX =
                        if (isLtr) lerp(padding, padding + dragWidth, f)
                        else lerp(-padding, -(padding + dragWidth), f)

                    if (!enabled) alpha = 0.55f
                }
                .semantics {
                    role = Role.Switch
                }
                .then(
                    if (enabled) dampedDragAnimation.modifier
                    else Modifier   // disable drag + press
                )
                .drawBackdrop(
                    backdrop = rememberCombinedBackdrop(
                        backdrop,
                        rememberBackdrop(trackBackdrop) { drawBackdrop ->
                            val p = dampedDragAnimation.pressProgress
                            val sX = lerp(2f / 3f, 0.75f, p)
                            val sY = lerp(0f, 0.75f, p)

                            scale(sX, sY) {
                                drawBackdrop()
                            }
                        }
                    ),
                    shape = { ContinuousCapsule },

                    // ✔ EFFECTS (skip cleanly if disabled)
                    effects = {
                        if (enabled) {
                            val p = dampedDragAnimation.pressProgress
                            blur(8.dp.toPx() * (1f - p))
                            lens(
                                5.dp.toPx() * p,
                                5.dp.toPx() * p,
                                chromaticAberration = true
                            )
                        }
                    },

                    // ✔ HIGHLIGHT (return null cleanly)
                    highlight = {
                        if (!enabled) {
                            null
                        } else {
                            val p = dampedDragAnimation.pressProgress
                            Highlight.Ambient.copy(
                                width = Highlight.Ambient.width / 1.5f,
                                blurRadius = Highlight.Ambient.blurRadius / 1.5f,
                                alpha = p
                            )
                        }
                    },

                    shadow = {
                        Shadow(
                            radius = 4.dp,
                            color = Color.Black.copy(alpha = 0.05f)
                        )
                    },

                    // ✔ INNER SHADOW
                    innerShadow = {
                        if (!enabled) {
                            null
                        } else {
                            val p = dampedDragAnimation.pressProgress
                            InnerShadow(
                                radius = 4.dp * p,
                                alpha = p
                            )
                        }
                    },

                    // ✔ LAYER BLOCK
                    layerBlock = {
                        if (enabled) {
                            scaleX = dampedDragAnimation.scaleX
                            scaleY = dampedDragAnimation.scaleY
                            val v = dampedDragAnimation.velocity / 50f
                            scaleX /= 1f - (v * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                            scaleY *= 1f - (v * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                        }
                    },

                    onDrawSurface = {
                        val p = dampedDragAnimation.pressProgress
                        drawRect(
                            if (enabled) Color.White.copy(alpha = 1f - p)
                            else Color.White.copy(alpha = 0.55f)
                        )
                    }
                )
                .size(30.dp, 16.dp)
        )
    }
}
