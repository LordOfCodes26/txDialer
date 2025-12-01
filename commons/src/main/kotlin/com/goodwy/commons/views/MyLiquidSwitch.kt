    package com.goodwy.commons.views

    import android.content.Context
    import android.util.AttributeSet
    import android.widget.FrameLayout
    import androidx.compose.foundation.isSystemInDarkTheme
    import androidx.compose.foundation.layout.*
    import androidx.compose.material3.MaterialTheme
    import androidx.compose.material3.Text
    import androidx.compose.runtime.*
    import androidx.compose.ui.Alignment
    import androidx.compose.ui.Modifier
    import androidx.compose.ui.graphics.Color
    import androidx.compose.ui.platform.ComposeView
    import androidx.compose.ui.platform.ViewCompositionStrategy
    import androidx.compose.ui.unit.dp
    import androidx.lifecycle.compose.LocalLifecycleOwner
    import androidx.lifecycle.findViewTreeLifecycleOwner
    import com.kyant.backdrop.backdrops.rememberCanvasBackdrop
    import com.goodwy.commons.compose.liquid.LiquidToggle
    import androidx.core.content.withStyledAttributes

    class MyLiquidSwitch @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null
    ) : FrameLayout(context, attrs) {

        private val composeView = ComposeView(context)
        private var listener: ((Boolean) -> Unit)? = null

        // Correct: state-backed label so Compose updates
        private var labelTextState = mutableStateOf("")

        private val checkedState = mutableStateOf(false)

        init {
            // Read android:text
            attrs?.let {
                context.withStyledAttributes(
                    it,
                    intArrayOf(android.R.attr.text)
                ) {
                    labelTextState.value = getString(0) ?: ""
                }
            }

            addView(
                composeView,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            )

            composeView.setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
            )

            setupContent()
        }

        private fun setupContent() {
            composeView.setContent {
                val lifecycleOwner = findViewTreeLifecycleOwner()

                if (lifecycleOwner != null) {
                    CompositionLocalProvider(LocalLifecycleOwner provides lifecycleOwner) {
                        MaterialTheme {
                            val isLight = !isSystemInDarkTheme()
                            val textColor = if (isLight) Color.Black else Color.White
                            val backgroundColor = if (isLight) Color.White else Color(0xFF121212)

                            val backdrop = rememberCanvasBackdrop {
                                drawRect(backgroundColor)
                            }

                            val hasLabel = labelTextState.value.isNotEmpty()

                            // One final Modifier based on label visibility
                            val rowModifier = Modifier
                                .then(
                                    if (hasLabel) Modifier.fillMaxWidth() else Modifier.wrapContentWidth()
                                )
                                .padding(0.dp, 4.dp, 4.dp, 4.dp)
                                .wrapContentHeight()

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = rowModifier,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {

                                if (hasLabel) {
                                    Text(
                                        text = labelTextState.value,
                                        color = textColor,
                                        modifier = Modifier.weight(1f)
                                    )
                                }

                                LiquidToggle(
                                    selected = { checkedState.value },
                                    onSelect = {
                                        checkedState.value = it
                                        listener?.invoke(it)
                                    },
                                    backdrop = backdrop
                                )
                            }
                        }
                    }
                }
            }
        }

        var isChecked: Boolean
            get() = checkedState.value
            set(value) {
                checkedState.value = value
                listener?.invoke(value)
            }

        fun toggle() {
            isChecked = !isChecked
        }

        fun setOnCheckedChangeListener(l: (Boolean) -> Unit) {
            listener = l
        }

        fun setLabelText(text: String) {
            labelTextState.value = text   // recomposes correctly
        }
    }
