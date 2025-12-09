package com.goodwy.commons.helpers

import android.graphics.Canvas
import android.widget.EdgeEffect
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import androidx.recyclerview.widget.RecyclerView

class BouncyEdgeEffectFactory : RecyclerView.EdgeEffectFactory() {

    override fun createEdgeEffect(recyclerView: RecyclerView, direction: Int): EdgeEffect {

        return object : EdgeEffect(recyclerView.context) {

            private val spring = SpringAnimation(
                recyclerView,
                DynamicAnimation.TRANSLATION_Y
            ).apply {
                spring = SpringForce(0f).apply {
                    stiffness = SpringForce.STIFFNESS_LOW
                    dampingRatio = SpringForce.DAMPING_RATIO_LOW_BOUNCY
                }
            }

            // How far the list can stretch before springing back
            private val MAX_PULL_DISTANCE = 0.35f

            // How strong the stretching is
            private val PULL_MULTIPLIER = 1200f

            override fun onPull(deltaDistance: Float) {
                handlePull(deltaDistance)
            }

            override fun onPull(deltaDistance: Float, displacement: Float) {
                handlePull(deltaDistance)
            }

            private fun handlePull(deltaDistance: Float) {
                // ❗ Do NOT call super.onPull() → causes infinite recursion on Android 11+
                val sign = if (direction == DIRECTION_TOP) 1 else -1

                val pull = sign * PULL_MULTIPLIER *
                    deltaDistance.coerceIn(-MAX_PULL_DISTANCE, MAX_PULL_DISTANCE)

                spring.cancel()
                recyclerView.translationY += pull
            }

            override fun onRelease() {
                // ❗ No super.onRelease()
                spring.start() // return to 0 translation
            }

            override fun onAbsorb(velocity: Int) {
                // ❗ No super.onAbsorb()
                spring.cancel()

                // Kick the list a bit before returning to neutral
                recyclerView.translationY =
                    if (direction == DIRECTION_TOP) 120f else -120f

                spring.start()
            }

            override fun draw(canvas: Canvas?): Boolean {
                // Remove system glow effect
                return false
            }
        }
    }
}
