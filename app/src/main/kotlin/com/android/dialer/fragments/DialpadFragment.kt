package com.android.dialer.fragments

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.CallLog.Calls
import android.telephony.PhoneNumberFormattingTextWatcher
import android.telephony.TelephonyManager
import android.util.AttributeSet
import android.util.TypedValue
import android.view.*
import android.view.View.OnFocusChangeListener
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.setPadding
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.behaviorule.arturdumchev.library.pixels
import com.behaviorule.arturdumchev.library.setHeight
import com.goodwy.commons.dialogs.CallConfirmationDialog
import com.goodwy.commons.dialogs.ConfirmationAdvancedDialog
import com.goodwy.commons.dialogs.ConfirmationDialog
import com.goodwy.commons.extensions.*
import com.goodwy.commons.helpers.*
import com.goodwy.commons.models.contacts.Contact
import com.goodwy.commons.views.MyRecyclerView
import com.android.dialer.BuildConfig
import com.android.dialer.R
import com.android.dialer.activities.CallActivity
import com.android.dialer.activities.CallHistoryActivity
import com.android.dialer.activities.ManageSpeedDialActivity
import com.android.dialer.activities.SettingsActivity
import com.android.dialer.activities.SettingsDialpadActivity
import com.android.dialer.activities.SimpleActivity
import com.android.dialer.adapters.ContactsAdapter
import com.android.dialer.adapters.RecentCallsAdapter
import com.android.dialer.databinding.ActivityDialpadBinding
import com.android.dialer.extensions.*
import com.android.dialer.helpers.*
import com.android.dialer.interfaces.RefreshItemsListener
import com.android.dialer.models.Events
import com.android.dialer.models.RecentCall
import com.android.dialer.models.SpeedDial
import com.google.gson.Gson
import com.mikhaellopez.rxanimation.RxAnimation
import com.mikhaellopez.rxanimation.shake
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.grantland.widget.AutofitHelper
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.io.InputStreamReader
import java.text.Collator
import java.util.Locale
import kotlin.math.roundToInt

class DialpadFragment(
    context: Context,
    attributeSet: AttributeSet
) : MyViewPagerFragment<DialpadFragment.DialpadInnerBinding>(context, attributeSet), RefreshItemsListener {

    private lateinit var binding: ActivityDialpadBinding
    var allContacts = ArrayList<Contact>()
    private var speedDialValues = ArrayList<SpeedDial>()
    private var privateCursor: Cursor? = null
    private var toneGeneratorHelper: ToneGeneratorHelper? = null
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
    private val longPressHandler = Handler(Looper.getMainLooper())
    private val pressedKeys = mutableSetOf<Char>()
    private var hasBeenScrolled = false
    private var storedDialpadStyle = 0
    private var storedBackgroundColor = 0
    private var storedToneVolume = 0
    private var allRecentCalls = listOf<RecentCall>()
    private var recentsAdapter: RecentCallsAdapter? = null
    private var recentsHelper: RecentsHelper? = null
    private var isTalkBackOn = false
    private var initSearch = true
    private var isInitialized = false

    class DialpadInnerBinding(val dialpadList: MyRecyclerView, val dialpadRecentsList: MyRecyclerView) : MyViewPagerFragment.InnerBinding {
        override val fragmentList: MyRecyclerView? = dialpadList
        override val recentsList: MyRecyclerView? = dialpadRecentsList
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        val coordinatorLayout = findViewById<androidx.coordinatorlayout.widget.CoordinatorLayout>(R.id.dialpadCoordinator)
        binding = ActivityDialpadBinding.bind(coordinatorLayout)
        innerBinding = DialpadInnerBinding(binding.dialpadList, binding.dialpadRecentsList)
    }

    override fun setupFragment() {
        if (isInitialized) return
        isInitialized = true

        recentsHelper = RecentsHelper(context)

        binding.apply {
            (activity as? SimpleActivity)?.let { act ->
                act.updateMaterialActivityViews(
                    mainCoordinatorLayout = dialpadCoordinator,
                    nestedView = dialpadHolder,
                    useTransparentNavigation = true,
                    useTopSearchMenu = false
                )
            }
        }

        EventBus.getDefault().register(this)

        (activity as? android.app.Activity)?.let {
            if (it.checkAppSideloading()) {
                return
            }
        }

        if (context.config.hideDialpadNumbers) {
            binding.dialpadClearWrapper.apply {
                dialpad1Holder.isVisible = false
                dialpad2Holder.isVisible = false
                dialpad3Holder.isVisible = false
                dialpad4Holder.isVisible = false
                dialpad5Holder.isVisible = false
                dialpad6Holder.isVisible = false
                dialpad7Holder.isVisible = false
                dialpad8Holder.isVisible = false
                dialpad9Holder.isVisible = false
                dialpad0Holder.visibility = View.INVISIBLE
            }

            binding.dialpadRoundWrapper.apply {
                dialpad1IosHolder.isVisible = false
                dialpad2IosHolder.isVisible = false
                dialpad3IosHolder.isVisible = false
                dialpad4IosHolder.isVisible = false
                dialpad5IosHolder.isVisible = false
                dialpad6IosHolder.isVisible = false
                dialpad7IosHolder.isVisible = false
                dialpad8IosHolder.isVisible = false
                dialpad9IosHolder.isVisible = false
                dialpad0IosHolder.visibility = View.INVISIBLE
            }
            binding.dialpadRectWrapper.apply {
                dialpad1Holder.isVisible = false
                dialpad2Holder.isVisible = false
                dialpad3Holder.isVisible = false
                dialpad4Holder.isVisible = false
                dialpad5Holder.isVisible = false
                dialpad6Holder.isVisible = false
                dialpad7Holder.isVisible = false
                dialpad8Holder.isVisible = false
                dialpad9Holder.isVisible = false
                dialpad0Holder.visibility = View.INVISIBLE
            }
        }

        speedDialValues = context.config.getSpeedDialValues()
        privateCursor = context.getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true)

        toneGeneratorHelper = ToneGeneratorHelper(context, DIALPAD_TONE_LENGTH_MS)

        binding.dialpadInput.apply {
            if (context.config.formatPhoneNumbers) {
                addTextChangedListener(PhoneNumberFormattingTextWatcher(Locale.getDefault().country))
            }
            onTextChangeListener { dialpadValueChanged(it) }
            requestFocus()
            AutofitHelper.create(this@apply)
            disableKeyboard()
        }

        ContactsHelper(context).getContacts(showOnlyContactsWithNumbers = true) { allContacts ->
            gotContacts(allContacts)
        }
        storedDialpadStyle = context.config.dialpadStyle
        storedToneVolume = context.config.toneVolume
        storedBackgroundColor = context.getProperBackgroundColor()

        setupResume()
    }

    private fun setupResume() {
        if (storedDialpadStyle != context.config.dialpadStyle || context.config.needRestart || storedBackgroundColor != context.getProperBackgroundColor()) {
            // For fragment, we can't restart like activity, so just update
            storedDialpadStyle = context.config.dialpadStyle
            storedBackgroundColor = context.getProperBackgroundColor()
        }

        if (storedToneVolume != context.config.toneVolume) {
            toneGeneratorHelper = ToneGeneratorHelper(context, DIALPAD_TONE_LENGTH_MS)
        }
        isTalkBackOn = context.isTalkBackOn()

        if (context.isDynamicTheme() && !context.isSystemInDarkMode()) binding.dialpadHolder.setBackgroundColor(context.getSurfaceColor())
        speedDialValues = context.config.getSpeedDialValues()
        initStyle()
        updateDialpadSize()
        if (context.config.dialpadStyle == DIALPAD_GRID || context.config.dialpadStyle == DIALPAD_ORIGINAL) updateCallButtonSize()
        setupOptionsMenu()
        refreshMenuItems()
        context.updateTextColors(binding.dialpadCoordinator)

        val properTextColor = context.getProperTextColor()
        val properBackgroundColor =
            if (context.isDynamicTheme() && !context.isSystemInDarkMode()) context.getSurfaceColor() else context.getProperBackgroundColor()
        val properPrimaryColor = context.getProperPrimaryColor()
        (activity as? SimpleActivity)?.let { act ->
            act.setupToolbar(
                toolbar = binding.dialpadToolbar,
                toolbarNavigationIcon = NavigationIcon.None,
                statusBarColor = properBackgroundColor,
                navigationClick = false
            )
        }
        binding.dialpadRecentsList.setBackgroundColor(properBackgroundColor)
        binding.dialpadList.setBackgroundColor(properBackgroundColor)

        binding.dialpadRectWrapper.root.setBackgroundColor(properBackgroundColor)
        binding.dialpadAddNumber.setTextColor(properPrimaryColor)
        binding.dialpadList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                if (!hasBeenScrolled) {
                    hasBeenScrolled = true
                    slideDown(dialpadView())
                }
            }
        })
        binding.dialpadRecentsList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                if (!hasBeenScrolled) {
                    hasBeenScrolled = true
                    slideDown(dialpadView())
                }
            }
        })

        binding.dialpadRoundWrapperUp.setOnClickListener { dialpadHide() }
        val view = dialpadView()
        binding.dialpadInput.setOnClickListener {
            if (view.isGone) dialpadHide()
        }
        binding.dialpadInput.onFocusChangeListener = OnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                if (view.isGone) dialpadHide()
            }
        }

        binding.letterFastscroller.textColor = properTextColor.getColorStateList()
        binding.letterFastscroller.pressedTextColor = properPrimaryColor
        binding.letterFastscrollerThumb.setupWithFastScroller(binding.letterFastscroller)
        binding.letterFastscrollerThumb.textColor = properPrimaryColor.getContrastColor()
        binding.letterFastscrollerThumb.thumbColor = properPrimaryColor.getColorStateList()

        (activity as? SimpleActivity)?.invalidateOptionsMenu()

        if (context.config.showRecentCallsOnDialpad) refreshItems(false, false, null)

        if (binding.dialpadInput.value.isEmpty()) {
            binding.dialpadRecentsList.beVisibleIf(
                binding.dialpadInput.value.isEmpty() && context.config.showRecentCallsOnDialpad
            )
        }
    }

    override fun setupColors(textColor: Int, primaryColor: Int, accentColor: Int) {
        // Colors are set up in setupResume
    }

    override fun onSearchClosed() {
        // Not used in dialpad
    }

    override fun onSearchQueryChanged(text: String) {
        // Not used in dialpad
    }

    override fun myRecyclerView(): MyRecyclerView {
        return binding.dialpadRecentsList
    }

    fun onResume() {
        storedDialpadStyle = context.config.dialpadStyle
        storedToneVolume = context.config.toneVolume
        storedBackgroundColor = context.getProperBackgroundColor()
        context.config.needRestart = false
        setupResume()
    }

    fun onPause() {
        storedDialpadStyle = context.config.dialpadStyle
        storedToneVolume = context.config.toneVolume
        storedBackgroundColor = context.getProperBackgroundColor()
        context.config.needRestart = false
    }

    fun onDestroy() {
        EventBus.getDefault().unregister(this)
    }

    private fun initStyle() {
        if (!DialpadT9.Initialized) {
            val reader = InputStreamReader(context.resources.openRawResource(R.raw.t9languages))
            DialpadT9.readFromJson(reader.readText())
        }
        when (context.config.dialpadStyle) {
            DIALPAD_IOS -> {
                val properBackgroundColor =
                    if (context.isDynamicTheme() && !context.isSystemInDarkMode()) context.getSurfaceColor() else context.getProperBackgroundColor()
                (activity as? SimpleActivity)?.updateNavigationBarColor(properBackgroundColor)

                binding.dialpadClearWrapper.root.beGone()
                binding.dialpadRectWrapper.root.beGone()
                binding.dialpadRoundWrapper.apply {
                    dialpadVoicemail.beVisibleIf(context.config.showVoicemailIcon)
                    dialpadIosHolder.beVisible()
                    dialpadIosHolder.setBackgroundColor(properBackgroundColor)

                    arrayOf(
                        dialpad0IosHolder, dialpad1IosHolder, dialpad2IosHolder, dialpad3IosHolder, dialpad4IosHolder,
                        dialpad5IosHolder, dialpad6IosHolder, dialpad7IosHolder, dialpad8IosHolder, dialpad9IosHolder,
                        dialpadAsteriskIosHolder, dialpadHashtagIosHolder
                    ).forEach {
                        it.foreground.applyColorFilter(Color.GRAY)
                        it.foreground.alpha = 60
                    }

                    val properTextColor = context.getProperTextColor()
                    arrayOf(dialpadAsteriskIos, dialpadHashtagIos, dialpadVoicemail
                    ).forEach {
                        it.applyColorFilter(properTextColor)
                    }

                    dialpadBottomMargin.apply {
                        setBackgroundColor(properBackgroundColor)
                        setHeight(100)
                    }
                }

                initLettersIos()
            }

            DIALPAD_CONCEPT -> {
                val properBackgroundColor =
                    if (context.isDynamicTheme() && !context.isSystemInDarkMode()) context.getSurfaceColor() else context.getProperBackgroundColor()
                (activity as? SimpleActivity)?.updateNavigationBarColor(properBackgroundColor)

                binding.dialpadRectWrapper.apply {
                    dialpadVoicemail.beVisibleIf(context.config.showVoicemailIcon)
                    dialpadGridWrapper.setBackgroundColor(properBackgroundColor)

                    arrayOf(
                        dividerHorizontalZero, dividerHorizontalOne, dividerHorizontalTwo, dividerHorizontalThree,
                        dividerHorizontalFour, dividerVerticalOne, dividerVerticalTwo, dividerVerticalStart, dividerVerticalEnd
                    ).forEach {
                        it.beInvisible()
                    }

                    val properTextColor = context.getProperTextColor()
                    arrayOf(
                        dialpadAsterisk, dialpadHashtag, dialpadVoicemail
                    ).forEach {
                        it.applyColorFilter(properTextColor)
                    }

                    dialpadBottomMargin.apply {
                        setBackgroundColor(properBackgroundColor)
                        setHeight(100)
                    }
                }
                binding.dialpadRoundWrapper.root.beGone()
                binding.dialpadClearWrapper.root.beGone()
                initLettersConcept()
            }

            DIALPAD_GRID -> {
                val surfaceColor =
                    if (context.isDynamicTheme() && !context.isSystemInDarkMode()) context.getProperBackgroundColor() else context.getSurfaceColor()
                (activity as? SimpleActivity)?.updateNavigationBarColor(surfaceColor)

                binding.dialpadRoundWrapper.root.beGone()
                binding.dialpadRectWrapper.root.beGone()
                binding.dialpadClearWrapper.apply {
                    dialpadVoicemail.beVisibleIf(context.config.showVoicemailIcon)
                    dialpadGridHolder.beVisible()
                    dialpadGridHolder.setBackgroundColor(surfaceColor)

                    if (isPiePlus()) {
                        val textColor = context.getProperTextColor()
                        dialpadGridHolder.outlineAmbientShadowColor = textColor
                        dialpadGridHolder.outlineSpotShadowColor = textColor
                    }

                    arrayOf(
                        dividerHorizontalZero, dividerHorizontalOne, dividerHorizontalTwo, dividerHorizontalThree,
                        dividerHorizontalFour, dividerVerticalOne, dividerVerticalTwo, dividerVerticalStart, dividerVerticalEnd
                    ).forEach {
                        it.beVisible()
                    }

                    val properTextColor = context.getProperTextColor()
                    arrayOf(dialpadAsterisk, dialpadHashtag, dialpadVoicemail
                    ).forEach {
                        it.applyColorFilter(properTextColor)
                    }

                    dialpadBottomMargin.apply {
                        setBackgroundColor(surfaceColor)
                        setHeight(100)
                    }
                }
                initLetters()
            }

            else -> {
                val surfaceColor =
                    if (context.isDynamicTheme() && !context.isSystemInDarkMode()) context.getProperBackgroundColor() else context.getSurfaceColor()
                (activity as? SimpleActivity)?.updateNavigationBarColor(surfaceColor)

                binding.dialpadRoundWrapper.root.beGone()
                binding.dialpadRectWrapper.root.beGone()
                binding.dialpadClearWrapper.apply {
                    dialpadVoicemail.beVisibleIf(context.config.showVoicemailIcon)
                    dialpadGridHolder.beVisible()
                    root.setBackgroundColor(surfaceColor)

                    if (isPiePlus()) {
                        val textColor = context.getProperTextColor()
                        dialpadGridHolder.outlineAmbientShadowColor = textColor
                        dialpadGridHolder.outlineSpotShadowColor = textColor
                    }

                    arrayOf(
                        dividerHorizontalZero, dividerHorizontalOne, dividerHorizontalTwo, dividerHorizontalThree,
                        dividerHorizontalFour, dividerVerticalOne, dividerVerticalTwo, dividerVerticalStart, dividerVerticalEnd
                    ).forEach {
                        it.beInvisible()
                    }

                    val properTextColor = context.getProperTextColor()
                    arrayOf(dialpadAsterisk, dialpadHashtag, dialpadVoicemail
                    ).forEach {
                        it.applyColorFilter(properTextColor)
                    }

                    dialpadBottomMargin.apply {
                        setBackgroundColor(surfaceColor)
                        setHeight(100)
                    }
                }
                initLetters()
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun initLettersConcept() {
        val areMultipleSIMsAvailable = context.areMultipleSIMsAvailable()
        val baseColor = context.baseConfig.backgroundColor
        val buttonsColor = when {
            context.isDynamicTheme() -> ContextCompat.getColor(context, R.color.you_status_bar_color)
            baseColor == Color.WHITE -> ContextCompat.getColor(context, R.color.dark_grey)
            baseColor == Color.BLACK -> ContextCompat.getColor(context, R.color.bottom_tabs_black_background)
            else -> context.baseConfig.backgroundColor.lightenColor(4)
        }
        val textColor = buttonsColor.getContrastColor()
        binding.dialpadRectWrapper.apply {
            if (context.config.hideDialpadLetters) {
                arrayOf(
                    dialpad1Letters, dialpad2Letters, dialpad3Letters, dialpad4Letters, dialpad5Letters,
                    dialpad6Letters, dialpad7Letters, dialpad8Letters, dialpad9Letters
                ).forEach {
                    it.beGone()
                }
            } else {
                dialpad1Letters.apply {
                    beInvisible()
                    setTypeface(null, context.config.dialpadSecondaryTypeface)
                }
                arrayOf(
                    dialpad2Letters, dialpad3Letters, dialpad4Letters, dialpad5Letters, dialpad6Letters,
                    dialpad7Letters, dialpad8Letters, dialpad9Letters
                ).forEach {
                    it.beVisible()
                    it.setTypeface(null, context.config.dialpadSecondaryTypeface)
                }

                val langPref = context.config.dialpadSecondaryLanguage
                val langLocale = Locale.getDefault().language
                val isAutoLang = DialpadT9.getSupportedSecondaryLanguages().contains(langLocale) && langPref == LANGUAGE_SYSTEM
                if (langPref!! != LANGUAGE_NONE && langPref != LANGUAGE_SYSTEM || isAutoLang) {
                    val lang = if (isAutoLang) langLocale else langPref
                    dialpad1Letters.text = DialpadT9.getLettersForNumber(2, lang) + "\nABC"
                    dialpad2Letters.text = DialpadT9.getLettersForNumber(2, lang) + "\nABC"
                    dialpad3Letters.text = DialpadT9.getLettersForNumber(3, lang) + "\nDEF"
                    dialpad4Letters.text = DialpadT9.getLettersForNumber(4, lang) + "\nGHI"
                    dialpad5Letters.text = DialpadT9.getLettersForNumber(5, lang) + "\nJKL"
                    dialpad6Letters.text = DialpadT9.getLettersForNumber(6, lang) + "\nMNO"
                    dialpad7Letters.text = DialpadT9.getLettersForNumber(7, lang) + "\nPQRS"
                    dialpad8Letters.text = DialpadT9.getLettersForNumber(8, lang) + "\nTUV"
                    dialpad9Letters.text = DialpadT9.getLettersForNumber(9, lang) + "\nWXYZ"

                    val fontSizeRu = context.getTextSize() - 16f
                    arrayOf(
                        dialpad1Letters, dialpad2Letters, dialpad3Letters, dialpad4Letters, dialpad5Letters,
                        dialpad6Letters, dialpad7Letters, dialpad8Letters, dialpad9Letters
                    ).forEach {
                        it.setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSizeRu)
                    }
                } else {
                    val fontSize = context.getTextSize() - 8f
                    arrayOf(
                        dialpad1Letters, dialpad2Letters, dialpad3Letters, dialpad4Letters, dialpad5Letters,
                        dialpad6Letters, dialpad7Letters, dialpad8Letters, dialpad9Letters
                    ).forEach {
                        it.setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSize)
                    }
                }
            }

            arrayOf(
                dialpad1, dialpad2, dialpad3, dialpad4, dialpad5,
                dialpad6, dialpad7, dialpad8, dialpad9, dialpad0,
                dialpad2Letters, dialpad3Letters, dialpad4Letters, dialpad5Letters, dialpad6Letters,
                dialpad7Letters, dialpad8Letters, dialpad9Letters, dialpadPlus
            ).forEach {
                it.setTextColor(textColor)
            }

            arrayOf(
                dialpad0Holder,
                dialpad1Holder,
                dialpad2Holder,
                dialpad3Holder,
                dialpad4Holder,
                dialpad5Holder,
                dialpad6Holder,
                dialpad7Holder,
                dialpad8Holder,
                dialpad9Holder,
                dialpadAsteriskHolder,
                dialpadHashtagHolder
            ).forEach {
                it.background = ResourcesCompat.getDrawable(context.resources, R.drawable.button_dialpad_background, null)
                it.background?.applyColorFilter(buttonsColor)
                it.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    val margin = pixels(R.dimen.one_dp).toInt()
                    setMargins(margin, margin, margin, margin)
                }
            }

            arrayOf(
                dialpadDownHolder,
                dialpadCallButtonHolder,
                dialpadClearCharHolder
            ).forEach {
                it.background = ResourcesCompat.getDrawable(context.resources, R.drawable.button_dialpad_background, null)
                it.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    val margin = pixels(R.dimen.one_dp).toInt()
                    val marginBottom = pixels(R.dimen.tiny_margin).toInt()
                    setMargins(margin, margin, margin, marginBottom)
                }
            }

            dialpadGridWrapper.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                val margin = pixels(R.dimen.tiny_margin).toInt()
                setMargins(margin, margin, margin, margin)
            }

            val simOnePrimary = context.config.currentSIMCardIndex == 0
            val simTwoColor = if (areMultipleSIMsAvailable) {
                if (simOnePrimary) context.config.simIconsColors[2] else context.config.simIconsColors[1]
            } else context.getProperPrimaryColor()
            val drawableSecondary = if (simOnePrimary) R.drawable.ic_phone_two_vector else R.drawable.ic_phone_one_vector
            val dialpadIconColor = if (simTwoColor == Color.WHITE) simTwoColor.getContrastColor() else textColor
            val downIcon = if (areMultipleSIMsAvailable) context.resources.getColoredDrawableWithColor(context, drawableSecondary, dialpadIconColor)
            else context.resources.getColoredDrawableWithColor(context, R.drawable.ic_dialpad_vector, dialpadIconColor)
            dialpadDown.setImageDrawable(downIcon)
            dialpadDownHolder.apply {
                background?.applyColorFilter(simTwoColor)
                setOnClickListener {
                    if (areMultipleSIMsAvailable) {
                        initCall(binding.dialpadInput.value, handleIndex = if (simOnePrimary) 1 else 0)
                    } else {
                        dialpadHide()
                    }
                    maybePerformDialpadHapticFeedback(dialpadDownHolder)
                }
                contentDescription = context.getString(
                    if (areMultipleSIMsAvailable) {
                        if (simOnePrimary) R.string.call_from_sim_2 else R.string.call_from_sim_1
                    } else {
                        val view = dialpadView()
                        if (view.isVisible) R.string.hide_dialpad else R.string.show_dialpad
                    }
                )
            }

            val simOneColor = if (simOnePrimary) context.config.simIconsColors[1] else context.config.simIconsColors[2]
            val drawablePrimary = if (simOnePrimary) R.drawable.ic_phone_one_vector else R.drawable.ic_phone_two_vector
            val callIconId = if (areMultipleSIMsAvailable) drawablePrimary else R.drawable.ic_phone_vector
            val callIconColor = if (simOneColor == Color.WHITE) simOneColor.getContrastColor() else textColor
            val callIcon = context.resources.getColoredDrawableWithColor(context, callIconId, callIconColor)
            dialpadCallIcon.setImageDrawable(callIcon)
            dialpadCallButtonHolder.apply {
                background?.applyColorFilter(simOneColor)
                setOnClickListener {
                    initCall(binding.dialpadInput.value, handleIndex = if (simOnePrimary || !areMultipleSIMsAvailable) 0 else 1)
                    maybePerformDialpadHapticFeedback(this)
                }
                setOnLongClickListener {
                    if (binding.dialpadInput.value.isEmpty()) {
                        val text = context.getTextFromClipboard()
                        binding.dialpadInput.setText(text)
                        if (text != null && text != "") {
                            binding.dialpadInput.setSelection(text.length)
                            binding.dialpadInput.requestFocusFromTouch()
                        }; true
                    } else {
                        copyNumber(); true
                    }
                }
                contentDescription = context.getString(
                    if (areMultipleSIMsAvailable) {
                        if (simOnePrimary) R.string.call_from_sim_1 else R.string.call_from_sim_2
                    } else R.string.call
                )
            }

            dialpadClearCharHolder.beVisible()
            dialpadClearCharHolder.background?.applyColorFilter(ContextCompat.getColor(context, R.color.red_call))
            dialpadClearCharHolder.setOnClickListener { clearChar(it) }
            dialpadClearCharHolder.setOnLongClickListener { clearInput(); true }
            dialpadClearChar.alpha = 1f
            dialpadClearChar.applyColorFilter(textColor)

            setupCharClick(dialpad1Holder, '1')
            setupCharClick(dialpad2Holder, '2')
            setupCharClick(dialpad3Holder, '3')
            setupCharClick(dialpad4Holder, '4')
            setupCharClick(dialpad5Holder, '5')
            setupCharClick(dialpad6Holder, '6')
            setupCharClick(dialpad7Holder, '7')
            setupCharClick(dialpad8Holder, '8')
            setupCharClick(dialpad9Holder, '9')
            setupCharClick(dialpad0Holder, '0')
            setupCharClick(dialpadAsteriskHolder, '*')
            setupCharClick(dialpadHashtagHolder, '#')
            dialpadGridHolder.setOnClickListener { } //Do not press between the buttons
        }
        binding.dialpadAddNumber.setOnClickListener { addNumberToContact() }

        binding.dialpadRoundWrapperUp.background?.applyColorFilter(context.config.simIconsColors[1])
        binding.dialpadRoundWrapperUp.setColorFilter(textColor)
    }

    @SuppressLint("SetTextI18n")
    private fun initLettersIos() {
        val areMultipleSIMsAvailable = context.areMultipleSIMsAvailable()
        val getProperTextColor = context.getProperTextColor()
        binding.dialpadRoundWrapper.apply {
            if (context.config.hideDialpadLetters) {
                arrayOf(
                    dialpad2IosLetters, dialpad3IosLetters, dialpad4IosLetters, dialpad5IosLetters,
                    dialpad6IosLetters, dialpad7IosLetters, dialpad8IosLetters, dialpad9IosLetters,
                    dialpad1IosLetters
                ).forEach {
                    it.beGone()
                }
            } else {
                dialpad1IosLetters.apply {
                    beInvisible()
                    setTypeface(null, context.config.dialpadSecondaryTypeface)
                }
                arrayOf(
                    dialpad2IosLetters, dialpad3IosLetters, dialpad4IosLetters, dialpad5IosLetters,
                    dialpad6IosLetters, dialpad7IosLetters, dialpad8IosLetters, dialpad9IosLetters
                ).forEach {
                    it.beVisible()
                    it.setTypeface(null, context.config.dialpadSecondaryTypeface)
                }

                val langPref = context.config.dialpadSecondaryLanguage
                val langLocale = Locale.getDefault().language
                val isAutoLang = DialpadT9.getSupportedSecondaryLanguages().contains(langLocale) && langPref == LANGUAGE_SYSTEM
                if (langPref!! != LANGUAGE_NONE && langPref != LANGUAGE_SYSTEM || isAutoLang) {
                    val lang = if (isAutoLang) langLocale else langPref
                    dialpad1IosLetters.text = DialpadT9.getLettersForNumber(2, lang) + "\nABC"
                    dialpad2IosLetters.text = DialpadT9.getLettersForNumber(2, lang) + "\nABC"
                    dialpad3IosLetters.text = DialpadT9.getLettersForNumber(3, lang) + "\nDEF"
                    dialpad4IosLetters.text = DialpadT9.getLettersForNumber(4, lang) + "\nGHI"
                    dialpad5IosLetters.text = DialpadT9.getLettersForNumber(5, lang) + "\nJKL"
                    dialpad6IosLetters.text = DialpadT9.getLettersForNumber(6, lang) + "\nMNO"
                    dialpad7IosLetters.text = DialpadT9.getLettersForNumber(7, lang) + "\nPQRS"
                    dialpad8IosLetters.text = DialpadT9.getLettersForNumber(8, lang) + "\nTUV"
                    dialpad9IosLetters.text = DialpadT9.getLettersForNumber(9, lang) + "\nWXYZ"

                    val fontSizeRu = context.getTextSize() - 16f
                    arrayOf(
                        dialpad1IosLetters, dialpad2IosLetters, dialpad3IosLetters, dialpad4IosLetters, dialpad5IosLetters,
                        dialpad6IosLetters, dialpad7IosLetters, dialpad8IosLetters, dialpad9IosLetters
                    ).forEach {
                        it.setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSizeRu)
                    }
                } else {
                    val fontSize = context.getTextSize() - 8f
                    arrayOf(
                        dialpad1IosLetters, dialpad2IosLetters, dialpad3IosLetters, dialpad4IosLetters, dialpad5IosLetters,
                        dialpad6IosLetters, dialpad7IosLetters, dialpad8IosLetters, dialpad9IosLetters
                    ).forEach {
                        it.setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSize)
                    }
                }
            }

            if (areMultipleSIMsAvailable) {
                dialpadSimIosHolder.beVisible()
                dialpadSimIos.background?.applyColorFilter(Color.GRAY)
                dialpadSimIos.background?.alpha = 60
                dialpadSimIos.applyColorFilter(getProperTextColor)
                dialpadSimIosHolder.setOnClickListener {
                    if (context.config.currentSIMCardIndex == 0) context.config.currentSIMCardIndex = 1 else context.config.currentSIMCardIndex = 0
                    updateCallButtonIos()
                    maybePerformDialpadHapticFeedback(dialpadSimIosHolder)
                    RxAnimation.from(dialpadCallButtonIosHolder)
                        .shake()
                        .subscribe()
                }
                updateCallButtonIos()
                dialpadCallButtonIosHolder.setOnClickListener {
                    initCall(binding.dialpadInput.value, context.config.currentSIMCardIndex)
                    maybePerformDialpadHapticFeedback(dialpadCallButtonIosHolder)
                }
                dialpadCallButtonIosHolder.contentDescription = context.getString(if (context.config.currentSIMCardIndex == 0) R.string.call_from_sim_1 else R.string.call_from_sim_2)
            } else {
                dialpadSimIosHolder.beGone()
                val color = context.config.simIconsColors[1]
                val callIcon = context.resources.getColoredDrawableWithColor(context, R.drawable.ic_phone_vector, color.getContrastColor())
                dialpadCallButtonIosIcon.setImageDrawable(callIcon)
                dialpadCallButtonIosHolder.background?.applyColorFilter(color)
                dialpadCallButtonIosHolder.setOnClickListener {
                    initCall(binding.dialpadInput.value, 0)
                    maybePerformDialpadHapticFeedback(dialpadCallButtonIosHolder)
                }
                dialpadCallButtonIosHolder.contentDescription = context.getString(R.string.call)
            }

            dialpadCallButtonIosHolder.setOnLongClickListener {
                if (binding.dialpadInput.value.isEmpty()) {
                    val text = context.getTextFromClipboard()
                    binding.dialpadInput.setText(text)
                    if (text != null && text != "") {
                        binding.dialpadInput.setSelection(text.length)
                        binding.dialpadInput.requestFocusFromTouch()
                    }; true
                } else {
                    copyNumber(); true
                }
            }

            dialpadClearCharIos.applyColorFilter(Color.GRAY)
            dialpadClearCharIos.alpha = 0.235f
            dialpadClearCharXIos.applyColorFilter(getProperTextColor)
            dialpadClearCharIosHolder.beVisibleIf(binding.dialpadInput.value.isNotEmpty() || areMultipleSIMsAvailable)
            dialpadClearCharIosHolder.setOnClickListener { clearChar(it) }
            dialpadClearCharIosHolder.setOnLongClickListener { clearInput(); true }

            setupCharClick(dialpad1IosHolder, '1')
            setupCharClick(dialpad2IosHolder, '2')
            setupCharClick(dialpad3IosHolder, '3')
            setupCharClick(dialpad4IosHolder, '4')
            setupCharClick(dialpad5IosHolder, '5')
            setupCharClick(dialpad6IosHolder, '6')
            setupCharClick(dialpad7IosHolder, '7')
            setupCharClick(dialpad8IosHolder, '8')
            setupCharClick(dialpad9IosHolder, '9')
            setupCharClick(dialpad0IosHolder, '0')
            setupCharClick(dialpadAsteriskIosHolder, '*')
            setupCharClick(dialpadHashtagIosHolder, '#')
            dialpadIosHolder.setOnClickListener { } //Do not press between the buttons
        }
        binding.dialpadAddNumber.setOnClickListener { addNumberToContact() }

        val simOneColor = context.config.simIconsColors[1]
        binding.dialpadRoundWrapperUp.background?.applyColorFilter(simOneColor)
        binding.dialpadRoundWrapperUp.setColorFilter(simOneColor.getContrastColor())
    }

    private fun updateCallButtonIos() {
        val oneSim = context.config.currentSIMCardIndex == 0
        val simColor = if (oneSim) context.config.simIconsColors[1] else context.config.simIconsColors[2]
        val callIconId = if (oneSim) R.drawable.ic_phone_one_vector else R.drawable.ic_phone_two_vector
        val callIcon = context.resources.getColoredDrawableWithColor(context, callIconId, simColor.getContrastColor())
        binding.dialpadRoundWrapper.dialpadCallButtonIosIcon.setImageDrawable(callIcon)
        binding.dialpadRoundWrapper.dialpadCallButtonIosHolder.background?.applyColorFilter(simColor)
    }

    @SuppressLint("SetTextI18n")
    private fun initLetters() {
        val areMultipleSIMsAvailable = context.areMultipleSIMsAvailable()
        val getProperTextColor = context.getProperTextColor()
        binding.dialpadClearWrapper.apply {
            if (context.config.hideDialpadLetters) {
                arrayOf(
                    dialpad1Letters, dialpad2Letters, dialpad3Letters, dialpad4Letters, dialpad5Letters,
                    dialpad6Letters, dialpad7Letters, dialpad8Letters, dialpad9Letters
                ).forEach {
                    it.beGone()
                }
            } else {
                dialpad1Letters.apply {
                    beInvisible()
                    setTypeface(null, context.config.dialpadSecondaryTypeface)
                }
                arrayOf(
                    dialpad2Letters, dialpad3Letters, dialpad4Letters, dialpad5Letters,
                    dialpad6Letters, dialpad7Letters, dialpad8Letters, dialpad9Letters
                ).forEach {
                    it.beVisible()
                    it.setTypeface(null, context.config.dialpadSecondaryTypeface)
                }

                val langPref = context.config.dialpadSecondaryLanguage
                val langLocale = Locale.getDefault().language
                val isAutoLang = DialpadT9.getSupportedSecondaryLanguages().contains(langLocale) && langPref == LANGUAGE_SYSTEM
                if (langPref!! != LANGUAGE_NONE && langPref != LANGUAGE_SYSTEM || isAutoLang) {
                    val lang = if (isAutoLang) langLocale else langPref
                    dialpad1Letters.text = DialpadT9.getLettersForNumber(2, lang) + "\nABC"
                    dialpad2Letters.text = DialpadT9.getLettersForNumber(2, lang) + "\nABC"
                    dialpad3Letters.text = DialpadT9.getLettersForNumber(3, lang) + "\nDEF"
                    dialpad4Letters.text = DialpadT9.getLettersForNumber(4, lang) + "\nGHI"
                    dialpad5Letters.text = DialpadT9.getLettersForNumber(5, lang) + "\nJKL"
                    dialpad6Letters.text = DialpadT9.getLettersForNumber(6, lang) + "\nMNO"
                    dialpad7Letters.text = DialpadT9.getLettersForNumber(7, lang) + "\nPQRS"
                    dialpad8Letters.text = DialpadT9.getLettersForNumber(8, lang) + "\nTUV"
                    dialpad9Letters.text = DialpadT9.getLettersForNumber(9, lang) + "\nWXYZ"

                    val fontSizeRu = context.getTextSize() - 16f
                    arrayOf(
                        dialpad1Letters, dialpad2Letters, dialpad3Letters, dialpad4Letters, dialpad5Letters,
                        dialpad6Letters, dialpad7Letters, dialpad8Letters, dialpad9Letters
                    ).forEach {
                        it.setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSizeRu)
                    }
                } else {
                    val fontSize = context.getTextSize() - 8f
                    arrayOf(
                        dialpad1Letters, dialpad2Letters, dialpad3Letters, dialpad4Letters, dialpad5Letters,
                        dialpad6Letters, dialpad7Letters, dialpad8Letters, dialpad9Letters
                    ).forEach {
                        it.setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSize)
                    }
                }
            }

            val simOnePrimary = context.config.currentSIMCardIndex == 0
            dialpadCallTwoButton.apply {
                if (areMultipleSIMsAvailable) {
                    beVisible()
                    val simTwoColor = if (simOnePrimary) context.config.simIconsColors[2] else context.config.simIconsColors[1]
                    val drawableSecondary = if (simOnePrimary) R.drawable.ic_phone_two_vector else R.drawable.ic_phone_one_vector
                    val callIcon = context.resources.getColoredDrawableWithColor(context, drawableSecondary, simTwoColor.getContrastColor())
                    setImageDrawable(callIcon)
                    background?.applyColorFilter(simTwoColor)
                    beVisible()
                    setOnClickListener {
                        initCall(binding.dialpadInput.value, handleIndex = if (simOnePrimary) 1 else 0)
                        maybePerformDialpadHapticFeedback(this)
                    }
                    contentDescription = context.getString(if (simOnePrimary) R.string.call_from_sim_2 else R.string.call_from_sim_1)
                } else {
                    beGone()
                }
            }

            val simOneColor = if (simOnePrimary) context.config.simIconsColors[1] else context.config.simIconsColors[2]
            val drawablePrimary = if (simOnePrimary) R.drawable.ic_phone_one_vector else R.drawable.ic_phone_two_vector
            val callIconId = if (areMultipleSIMsAvailable) drawablePrimary else R.drawable.ic_phone_vector
            val callIcon = context.resources.getColoredDrawableWithColor(context, callIconId, simOneColor.getContrastColor())
            dialpadCallButton.apply {
                setImageDrawable(callIcon)
                background?.applyColorFilter(simOneColor)
                setOnClickListener {
                    initCall(binding.dialpadInput.value, handleIndex = if (simOnePrimary || !areMultipleSIMsAvailable) 0 else 1)
                    maybePerformDialpadHapticFeedback(this)
                }
                setOnLongClickListener {
                    if (binding.dialpadInput.value.isEmpty()) {
                        val text = context.getTextFromClipboard()
                        binding.dialpadInput.setText(text)
                        if (text != null && text != "") {
                            binding.dialpadInput.setSelection(text.length)
                            binding.dialpadInput.requestFocusFromTouch()
                        }; true
                    } else {
                        copyNumber(); true
                    }
                }
                contentDescription = context.getString(
                    if (areMultipleSIMsAvailable) {
                        if (simOnePrimary) R.string.call_from_sim_1 else R.string.call_from_sim_2
                    } else R.string.call
                )
            }

            dialpadClearCharHolder.beVisibleIf(binding.dialpadInput.value.isNotEmpty() || areMultipleSIMsAvailable)
            dialpadClearChar.applyColorFilter(Color.GRAY)
            dialpadClearChar.alpha = 0.4f
            dialpadClearCharX.applyColorFilter(getProperTextColor)
            dialpadClearCharHolder.setOnClickListener { clearChar(it) }
            dialpadClearCharHolder.setOnLongClickListener { clearInput(); true }

            setupCharClick(dialpad1Holder, '1')
            setupCharClick(dialpad2Holder, '2')
            setupCharClick(dialpad3Holder, '3')
            setupCharClick(dialpad4Holder, '4')
            setupCharClick(dialpad5Holder, '5')
            setupCharClick(dialpad6Holder, '6')
            setupCharClick(dialpad7Holder, '7')
            setupCharClick(dialpad8Holder, '8')
            setupCharClick(dialpad9Holder, '9')
            setupCharClick(dialpad0Holder, '0')
            setupCharClick(dialpadAsteriskHolder, '*')
            setupCharClick(dialpadHashtagHolder, '#')
            dialpadGridHolder.setOnClickListener { } //Do not press between the buttons
        }
        binding.dialpadAddNumber.setOnClickListener { addNumberToContact() }

        val simOneColor = context.config.simIconsColors[1]
        binding.dialpadRoundWrapperUp.background?.applyColorFilter(simOneColor)
        binding.dialpadRoundWrapperUp.setColorFilter(simOneColor.getContrastColor())
    }

    private fun dialpadView() = when (context.config.dialpadStyle) {
        DIALPAD_IOS -> binding.dialpadRoundWrapper.root
        DIALPAD_CONCEPT -> binding.dialpadRectWrapper.root
        else -> binding.dialpadClearWrapper.root
    }

    private fun updateDialpadSize() {
        val size = context.config.dialpadSize
        val view = when (context.config.dialpadStyle) {
            DIALPAD_IOS -> binding.dialpadRoundWrapper.dialpadIosWrapper
            DIALPAD_CONCEPT -> binding.dialpadRectWrapper.dialpadGridWrapper
            else -> binding.dialpadClearWrapper.dialpadGridWrapper
        }
        val dimens = if (context.config.dialpadStyle == DIALPAD_IOS) pixels(R.dimen.dialpad_ios_height) else pixels(R.dimen.dialpad_grid_height)
        view.setHeight((dimens * (size / 100f)).toInt())

        val margin = context.config.dialpadBottomMargin
        val marginView = when (context.config.dialpadStyle) {
            DIALPAD_IOS -> binding.dialpadRoundWrapper.dialpadBottomMargin
            DIALPAD_CONCEPT -> binding.dialpadRectWrapper.dialpadBottomMargin
            else -> binding.dialpadClearWrapper.dialpadBottomMargin
        }
        val start =
            if (context.config.dialpadStyle == DIALPAD_IOS) pixels(R.dimen.dialpad_margin_bottom_ios)
            else pixels(R.dimen.zero)
        marginView.setHeight((start + margin).toInt())
    }

    private fun updateCallButtonSize() {
        val size = context.config.callButtonPrimarySize
        val view = binding.dialpadClearWrapper.dialpadCallButton
        val dimens = pixels(R.dimen.dialpad_phone_button_size)
        view.setHeightAndWidth((dimens * (size / 100f)).toInt())
        view.setPadding((dimens * 0.1765 * (size / 100f)).toInt())

        if (context.areMultipleSIMsAvailable()) {
            val sizeSecondary = context.config.callButtonSecondarySize
            val viewSecondary = binding.dialpadClearWrapper.dialpadCallTwoButton
            val dimensSecondary = pixels(R.dimen.dialpad_button_size_small)
            viewSecondary.setHeightAndWidth((dimensSecondary * (sizeSecondary / 100f)).toInt())
            viewSecondary.setPadding((dimens * 0.1765 * (sizeSecondary / 100f)).toInt())
        }
    }

    private fun refreshMenuItems() {
        binding.dialpadToolbar.menu.apply {
            findItem(R.id.copy_number)?.isVisible = binding.dialpadInput.value.isNotEmpty()
            findItem(R.id.web_search)?.isVisible = binding.dialpadInput.value.isNotEmpty()
            findItem(R.id.cab_call_anonymously)?.isVisible = binding.dialpadInput.value.isNotEmpty()
            findItem(R.id.clear_call_history)?.isVisible = context.config.showRecentCallsOnDialpad
            findItem(R.id.show_blocked_numbers)?.isVisible = context.config.showRecentCallsOnDialpad
            findItem(R.id.show_blocked_numbers)?.title =
                if (context.config.showBlockedNumbers) context.getString(R.string.hide_blocked_numbers) else context.getString(R.string.show_blocked_numbers)
        }
    }

    private fun setupOptionsMenu() {
        binding.dialpadToolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.paste_number -> {
                    val text = context.getTextFromClipboard()
                    binding.dialpadInput.setText(text)
                    if (text != null && text != "") {
                        binding.dialpadInput.setSelection(text.length)
                        binding.dialpadInput.requestFocusFromTouch()
                    }
                }
                R.id.copy_number -> copyNumber()
                R.id.web_search -> webSearch()
                R.id.cab_call_anonymously -> initCallAnonymous()
                R.id.show_blocked_numbers -> showBlockedNumbers()
                R.id.clear_call_history -> clearCallHistory()
                R.id.settings_dialpad -> context.startActivity(Intent(context, SettingsDialpadActivity::class.java))
                R.id.settings -> context.startActivity(Intent(context, SettingsActivity::class.java))
                else -> return@setOnMenuItemClickListener false
            }
            return@setOnMenuItemClickListener true
        }
        binding.dialpadToolbar.setNavigationOnClickListener {
            (activity as? SimpleActivity)?.onBackPressed()
        }
    }

    private fun copyNumber() {
        val clip = binding.dialpadInput.value
        context.copyToClipboard(clip)
    }

    private fun webSearch() {
        val text = binding.dialpadInput.value
        (activity as? android.app.Activity)?.launchInternetSearch(text)
    }

    private fun checkDialIntent(): Boolean {
        // Fragments don't have intents, so this is not applicable
        return false
    }

    private fun addNumberToContact() {
        Intent().apply {
            action = Intent.ACTION_INSERT_OR_EDIT
            type = "vnd.android.cursor.item/contact"
            putExtra(KEY_PHONE, binding.dialpadInput.value)
            context.launchActivityIntent(this)
        }
    }

    private fun dialpadPressed(char: Char, view: View?) {
        binding.dialpadInput.addCharacter(char)
        maybePerformDialpadHapticFeedback(view)
    }

    private fun dialpadHide() {
        val view = dialpadView()
        if (view.isVisible) {
            slideDown(view)
        } else {
            slideUp(view)
        }
    }

    private fun slideDown(view: View) {
        view.animate()
            .translationY(view.height.toFloat())
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    view.visibility = View.GONE
                    view.translationY = 0f
                }
            })
        hasBeenScrolled = false
        if ((view == binding.dialpadRoundWrapper.root ||
                    view == binding.dialpadClearWrapper.root ||
                    view == binding.dialpadRectWrapper.root) &&
            binding.dialpadRoundWrapperUp.isGone
        ) slideUp(
            binding.dialpadRoundWrapperUp
        )
    }

    private fun slideUp(view: View) {
        view.visibility = View.VISIBLE
        if (view.height > 0) {
            slideUpNow(view)
        } else {
            view.post { slideUpNow(view) }
        }
        if (view == binding.dialpadRoundWrapper.root ||
            view == binding.dialpadClearWrapper.root ||
            view == binding.dialpadRectWrapper.root
        ) slideDown(binding.dialpadRoundWrapperUp)
    }

    private fun slideUpNow(view: View) {
        view.translationY = view.height.toFloat()
        view.animate()
            .translationY(0f)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    view.visibility = View.VISIBLE
                }
            })
    }

    private fun clearChar(view: View) {
        binding.dialpadInput.dispatchKeyEvent(binding.dialpadInput.getKeyEvent(KeyEvent.KEYCODE_DEL))
        maybePerformDialpadHapticFeedback(view)
    }

    private fun clearInput() {
        binding.dialpadInput.setText("")
    }

    private fun clearInputWithDelay() {
        (activity as? SimpleActivity)?.lifecycleScope?.launch {
            delay(1000)
            clearInput()
        }
    }

    private fun gotContacts(newContacts: ArrayList<Contact>) {
        allContacts = newContacts

        val privateContacts = MyContactsContentProvider.getContacts(context, privateCursor)
        if (privateContacts.isNotEmpty()) {
            allContacts.addAll(privateContacts)
            allContacts.sort()
        }

        activity?.runOnUiThread {
            if (!checkDialIntent() && binding.dialpadInput.value.isEmpty()) {
                dialpadValueChanged("")
            }
        }
    }

    private fun dialpadValueChanged(textFormat: String) {
        val len = textFormat.length
        val view = dialpadView()
        if (len == 0 && view.isGone) {
            slideUp(view)
        }
        if (len > 8 && textFormat.startsWith("*#*#") && textFormat.endsWith("#*#*")) {
            val secretCode = textFormat.substring(4, textFormat.length - 4)
            if (context.isDefaultDialer()) {
                context.getSystemService(TelephonyManager::class.java)?.sendDialerSpecialCode(secretCode)
            } else {
                (activity as? SimpleActivity)?.launchSetDefaultDialerIntentPublic()
            }
            return
        }

        (binding.dialpadList.adapter as? ContactsAdapter)?.finishActMode()
        (binding.dialpadRecentsList.adapter as? RecentCallsAdapter)?.finishActMode()

        val text = if (context.config.formatPhoneNumbers) textFormat.removeNumberFormatting() else textFormat
        val collator = Collator.getInstance(context.sysLocale())
        val filtered = allContacts.filter { contact ->
            val langPref = context.config.dialpadSecondaryLanguage ?: ""
            val langLocale = Locale.getDefault().language
            val isAutoLang = DialpadT9.getSupportedSecondaryLanguages().contains(langLocale) && langPref == LANGUAGE_SYSTEM
            val lang = if (isAutoLang) langLocale else langPref

            val convertedName = DialpadT9.convertLettersToNumbers(
                contact.name.normalizeString().uppercase(), lang)
            val convertedNameWithoutSpaces = convertedName.filterNot { it.isWhitespace() }
            val convertedNickname = DialpadT9.convertLettersToNumbers(
                contact.nickname.normalizeString().uppercase(), lang)
            val convertedCompany = DialpadT9.convertLettersToNumbers(
                contact.organization.company.normalizeString().uppercase(), lang)
            val convertedNameToDisplay = DialpadT9.convertLettersToNumbers(
                contact.getNameToDisplay().normalizeString().uppercase(), lang)
            val convertedNameToDisplayWithoutSpaces = convertedNameToDisplay.filterNot { it.isWhitespace() }

            contact.doesContainPhoneNumber(text, convertLetters = true, search = true)
                || (convertedName.contains(text, true))
                || (convertedNameWithoutSpaces.contains(text, true))
                || (convertedNameToDisplay.contains(text, true))
                || (convertedNameToDisplayWithoutSpaces.contains(text, true))
                || (convertedNickname.contains(text, true))
                || (convertedCompany.contains(text, true))
        }.sortedWith(compareBy(collator) {
            it.getNameToDisplay()
        }).toMutableList() as ArrayList<Contact>

        ContactsAdapter(
            activity = activity as SimpleActivity,
            contacts = filtered,
            recyclerView = binding.dialpadList,
            highlightText = text,
            refreshItemsListener = null,
            showNumber = true,
            allowLongClick = false,
            itemClick = {
                (activity as? SimpleActivity)?.startCallWithConfirmationCheck(it as Contact)
                if (context.config.showCallConfirmation) clearInputWithDelay()
            },
            profileIconClick = {
                (activity as? SimpleActivity)?.startContactDetailsIntent(it as Contact)
            }).apply {
            binding.dialpadList.adapter = this
        }

        val filteredRecents = allRecentCalls
            .filter {
                it.name.contains(text, true) ||
                    it.doesContainPhoneNumber(text) ||
                    it.nickname.contains(text, true) ||
                    it.company.contains(text, true) ||
                    it.jobPosition.contains(text, true)
            }
            .sortedWith(
                compareByDescending<RecentCall> { it.dayCode }
                    .thenByDescending { it.name.startsWith(text, true) }
                    .thenByDescending { it.startTS }
            )

        if (!initSearch) {
            recentsAdapter?.updateItems(filteredRecents)
        }
        initSearch = false

        binding.dialpadAddNumber.beVisibleIf(binding.dialpadInput.value.isNotEmpty())
        binding.dialpadAddNumber.setTextColor(context.getProperPrimaryColor())
        binding.dialpadPlaceholder.beVisibleIf(filtered.isEmpty())
        binding.dialpadList.beVisibleIf(filtered.isNotEmpty() && binding.dialpadInput.value.isNotEmpty())
        val areMultipleSIMsAvailable = context.areMultipleSIMsAvailable()
        binding.dialpadClearWrapper.dialpadClearCharHolder.beVisibleIf((binding.dialpadInput.value.isNotEmpty() && context.config.dialpadStyle != DIALPAD_IOS && context.config.dialpadStyle != DIALPAD_CONCEPT) || areMultipleSIMsAvailable)
        binding.dialpadRectWrapper.dialpadClearCharHolder.beVisibleIf(context.config.dialpadStyle == DIALPAD_CONCEPT)
        binding.dialpadRoundWrapper.dialpadClearCharIosHolder.beVisibleIf((binding.dialpadInput.value.isNotEmpty() && context.config.dialpadStyle == DIALPAD_IOS) || areMultipleSIMsAvailable)
        binding.dialpadInput.beVisibleIf(binding.dialpadInput.value.isNotEmpty())
        binding.dialpadRecentsList.beVisibleIf(
            (binding.dialpadInput.value.isEmpty() && context.config.showRecentCallsOnDialpad)
                || (binding.dialpadInput.value.isNotEmpty() && filteredRecents.isNotEmpty() && filtered.isEmpty())
        )
        refreshMenuItems()
    }

    private fun initCall(number: String = binding.dialpadInput.value, handleIndex: Int, displayName: String? = null) {
        if (number.isNotEmpty()) {
            val nameToDisplay = displayName ?: number
            if (handleIndex != -1 && context.areMultipleSIMsAvailable()) {
                (activity as? SimpleActivity)?.callContactWithSimWithConfirmationCheck(number, nameToDisplay, handleIndex == 0)
            } else {
                (activity as? SimpleActivity)?.startCallWithConfirmationCheck(number, nameToDisplay)
            }
            if (context.config.dialpadClearWhenStartCall) clearInputWithDelay()
        } else {
            recentsHelper?.getRecentCalls(queryLimit = 1) {
                val mostRecentNumber = it.firstOrNull()?.phoneNumber
                if (!mostRecentNumber.isNullOrEmpty()) {
                    activity?.runOnUiThread {
                        binding.dialpadInput.setText(mostRecentNumber)
                        binding.dialpadInput.setSelection(mostRecentNumber.length)
                    }
                }
            }
        }
    }

    private fun speedDial(id: Int): Boolean {
        if (binding.dialpadInput.value.length == 1) {
            val speedDial = speedDialValues.firstOrNull { it.id == id }
            if (speedDial?.isValid() == true) {
                initCall(speedDial.number, -1, speedDial.getName(context))
                return true
            } else {
                ConfirmationDialog(context as SimpleActivity, context.getString(R.string.open_speed_dial_manage)) {
                    context.startActivity(Intent(context, ManageSpeedDialActivity::class.java))
                }
            }
        }
        return false
    }

    private fun startDialpadTone(char: Char) {
        if (context.config.dialpadBeeps) {
            pressedKeys.add(char)
            toneGeneratorHelper?.startTone(char)
        }
    }

    private fun stopDialpadTone(char: Char) {
        if (context.config.dialpadBeeps) {
            if (!pressedKeys.remove(char)) return
            if (pressedKeys.isEmpty()) {
                toneGeneratorHelper?.stopTone()
            } else {
                startDialpadTone(pressedKeys.last())
            }
        }
    }

    private fun maybePerformDialpadHapticFeedback(view: View?) {
        if (context.config.dialpadVibration) {
            view?.performHapticFeedback()
        }
    }

    private fun performLongClick(view: View, char: Char) {
        if (char == '0') {
            clearChar(view)
            dialpadPressed('+', view)
        } else if (char == '*') {
            clearChar(view)
            dialpadPressed(',', view)
        } else if (char == '#') {
            clearChar(view)
            when (context.config.dialpadHashtagLongClick) {
                DIALPAD_LONG_CLICK_WAIT -> dialpadPressed(';', view)
                DIALPAD_LONG_CLICK_SETTINGS_DIALPAD -> {
                    context.startActivity(Intent(context, SettingsDialpadActivity::class.java))
                }
                else -> {
                    context.startActivity(Intent(context, SettingsActivity::class.java))
                }
            }
        } else {
            val result = speedDial(char.digitToInt())
            if (result) {
                stopDialpadTone(char)
                clearChar(view)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupCharClick(view: View, char: Char, longClickable: Boolean = true) {
        view.isClickable = true
        view.isLongClickable = true
        if (isTalkBackOn) {
            view.setOnClickListener {
                startDialpadTone(char)
                dialpadPressed(char, view)
                stopDialpadTone(char)
            }
            view.setOnLongClickListener { performLongClick(view, char); true }
        } else view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dialpadPressed(char, view)
                    startDialpadTone(char)
                    if (longClickable) {
                        longPressHandler.removeCallbacksAndMessages(null)
                        longPressHandler.postDelayed({
                            performLongClick(view, char)
                        }, longPressTimeout)
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    stopDialpadTone(char)
                    if (longClickable) {
                        longPressHandler.removeCallbacksAndMessages(null)
                    }
                }

                MotionEvent.ACTION_MOVE -> {
                    val viewContainsTouchEvent = if (event.rawX.isNaN() || event.rawY.isNaN()) {
                        false
                    } else {
                        view.boundingBox.contains(event.rawX.roundToInt(), event.rawY.roundToInt())
                    }

                    if (!viewContainsTouchEvent) {
                        stopDialpadTone(char)
                        if (longClickable) {
                            longPressHandler.removeCallbacksAndMessages(null)
                        }
                    }
                }
            }
            false
        }
    }

    private fun initCallAnonymous() {
        val dialpadValue = binding.dialpadInput.value
        if (context.config.showWarningAnonymousCall) {
            val text = String.format(context.getString(R.string.call_anonymously_warning), dialpadValue)
            ConfirmationAdvancedDialog(
                context as SimpleActivity,
                text,
                R.string.call_anonymously_warning,
                R.string.ok,
                R.string.do_not_show_again,
                fromHtml = true
            ) {
                if (it) {
                    initCall("#31#$dialpadValue", 0)
                } else {
                    context.config.showWarningAnonymousCall = false
                    initCall("#31#$dialpadValue", 0)
                }
            }
        } else {
            initCall("#31#$dialpadValue", 0)
        }
    }

    override fun refreshItems(invalidate: Boolean, needUpdate: Boolean, callback: (() -> Unit)?) {
        if (invalidate) {
            allRecentCalls = emptyList()
            context.config.recentCallsCache = ""
        }

        if (binding.dialpadInput.value.isNotEmpty()) {
            dialpadValueChanged(binding.dialpadInput.value)
            callback?.invoke()
        } else if (needUpdate || context.config.needUpdateRecents) {
            refreshCallLog(loadAll = false) {
                refreshCallLog(loadAll = true) {
                    callback?.invoke()
                }
            }
        } else {
            var recents = emptyList<RecentCall>()
            if (!invalidate) {
                try {
                    recents = context.config.parseRecentCallsCache()
                } catch (_: Exception) {
                    context.config.recentCallsCache = ""
                }
            }

            if (recents.isNotEmpty()) {
                refreshCallLogFromCache(recents) {
                    refreshCallLog(loadAll = true) {
                        callback?.invoke()
                    }
                }
            } else {
                refreshCallLog(loadAll = false) {
                    refreshCallLog(loadAll = true) {
                        callback?.invoke()
                    }
                }
            }
        }
    }

    private fun refreshCallLog(loadAll: Boolean = false, callback: (() -> Unit)? = null) {
        getRecentCalls(loadAll) {
            allRecentCalls = it
            activity?.runOnUiThread { gotRecents(it) }
            callback?.invoke()

            context.config.recentCallsCache = Gson().toJson(it.take(RECENT_CALL_CACHE_SIZE))

            context.callerNotesHelper.removeCallerNotes(
                it.map { recentCall -> recentCall.phoneNumber.numberForNotes() }
            )
        }

        if (loadAll) {
            with(recentsHelper!!) {
                val queryCount = context.config.queryLimitRecent
                getRecentCalls(queryLimit = queryCount, updateCallsCache = false) { it ->
                    ensureBackgroundThread {
                        val recentOutgoingNumbers = it
                            .filter { it.type == Calls.OUTGOING_TYPE }
                            .map { recentCall -> recentCall.phoneNumber }

                        context.config.recentOutgoingNumbers = recentOutgoingNumbers.toMutableSet()
                    }
                }
            }
        }
    }

    private fun refreshCallLogFromCache(cache: List<RecentCall>, callback: (() -> Unit)? = null) {
        gotRecents(cache)
        callback?.invoke()
    }

    private fun getRecentCalls(loadAll: Boolean, callback: (List<RecentCall>) -> Unit) {
        val queryCount = if (loadAll) context.config.queryLimitRecent else RecentsHelper.QUERY_LIMIT
        val existingRecentCalls = allRecentCalls

        with(recentsHelper!!) {
            if (context.config.groupSubsequentCalls) {
                getGroupedRecentCalls(existingRecentCalls, queryCount, true) {
                    prepareCallLog(it, callback)
                }
            } else {
                getRecentCalls(existingRecentCalls, queryCount, isDialpad = true, updateCallsCache = true) { it ->
                    val calls = if (context.config.groupAllCalls) it.distinctBy { it.phoneNumber } else it
                    prepareCallLog(calls, callback)
                }
            }
        }
    }

    private fun prepareCallLog(calls: List<RecentCall>, callback: (List<RecentCall>) -> Unit) {
        if (calls.isEmpty()) {
            callback(emptyList())
            return
        }

        ContactsHelper(context).getContacts(showOnlyContactsWithNumbers = true) { contacts ->
            ensureBackgroundThread {
                val privateContacts = getPrivateContacts()
                val updatedCalls = updateNamesIfEmpty(
                    calls = maybeFilterPrivateCalls(calls, privateContacts),
                    contacts = contacts,
                    privateContacts = privateContacts
                )

                callback(updatedCalls)
            }
        }
    }

    private fun getPrivateContacts(): ArrayList<Contact> {
        val privateCursor = context.getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true)
        return MyContactsContentProvider.getContacts(context, privateCursor)
    }

    private fun maybeFilterPrivateCalls(calls: List<RecentCall>, privateContacts: List<Contact>): List<RecentCall> {
        val ignoredSources = context.baseConfig.ignoredContactSources
        return if (SMT_PRIVATE in ignoredSources) {
            val privateNumbers = privateContacts.flatMap { it.phoneNumbers }.map { it.value }
            calls.filterNot { it.phoneNumber in privateNumbers }
        } else {
            calls
        }
    }

    private fun updateNamesIfEmpty(calls: List<RecentCall>, contacts: List<Contact>, privateContacts: List<Contact>): List<RecentCall> {
        if (calls.isEmpty()) return mutableListOf()

        val contactsWithNumbers = contacts.filter { it.phoneNumbers.isNotEmpty() }
        return calls.map { call ->
            if (call.phoneNumber == call.name) {
                val privateContact = privateContacts.firstOrNull { it.doesContainPhoneNumber(call.phoneNumber) }
                val contact = contactsWithNumbers.firstOrNull { it.phoneNumbers.first().normalizedNumber == call.phoneNumber }

                when {
                    privateContact != null -> withUpdatedName(call = call, name = privateContact.getNameToDisplay())
                    contact != null -> withUpdatedName(call = call, name = contact.getNameToDisplay())
                    else -> call
                }
            } else {
                call
            }
        }
    }

    private fun withUpdatedName(call: RecentCall, name: String): RecentCall {
        return call.copy(
            name = name,
            groupedCalls = call.groupedCalls
                ?.map { it.copy(name = name) }
                ?.toMutableList()
                ?.ifEmpty { null }
        )
    }

    private fun gotRecents(recents: List<RecentCall>) {
        val currAdapter = binding.dialpadRecentsList.adapter
        if (currAdapter == null) {
            recentsAdapter = RecentCallsAdapter(
                activity = activity as SimpleActivity,
                recyclerView = binding.dialpadRecentsList,
                refreshItemsListener = null,
                showOverflowMenu = true,
                hideTimeAtOtherDays = true,
                isDialpad = true,
                itemDelete = { deleted ->
                    allRecentCalls = allRecentCalls.filter { it !in deleted }
                },
                itemClick = {
                    val recentCall = it as RecentCall
                    if (context.config.showCallConfirmation) {
                        CallConfirmationDialog(activity as SimpleActivity, recentCall.name) {
                            callRecentNumber(recentCall)
                        }
                    } else {
                        callRecentNumber(recentCall)
                    }
                },
                profileInfoClick = { recentCall ->
                    val recentCalls = recentCall.groupedCalls as ArrayList<RecentCall>? ?: arrayListOf(recentCall)
                    val contact = findContactByCall(recentCall)
                    Intent(context, CallHistoryActivity::class.java).apply {
                        putExtra(CURRENT_RECENT_CALL, recentCall)
                        putExtra(CURRENT_RECENT_CALL_LIST, recentCalls)
                        putExtra(CONTACT_ID, recentCall.contactID)
                        if (contact != null) {
                            putExtra(IS_PRIVATE, contact.isPrivate())
                        }
                        context.launchActivityIntent(this)
                    }
                },
                profileIconClick = {
                    val contact = findContactByCall(it as RecentCall)
                    if (contact != null) {
                        (activity as? SimpleActivity)?.startContactDetailsIntent(contact)
                    } else {
                        addContact(it)
                    }
                }
            )

            binding.dialpadRecentsList.adapter = recentsAdapter
            recentsAdapter?.updateItems(recents)

            if (context.areSystemAnimationsEnabled) {
                binding.dialpadRecentsList.scheduleLayoutAnimation()
            }

            binding.dialpadRecentsList.endlessScrollListener = object : MyRecyclerView.EndlessScrollListener {
                override fun updateTop() = Unit
                override fun updateBottom() = refreshCallLog()
            }
        } else {
            recentsAdapter?.updateItems(recents)
        }
    }

    private fun callRecentNumber(recentCall: RecentCall) {
        if (context.config.callUsingSameSim && recentCall.simID > 0) {
            val sim = recentCall.simID == 1
            (activity as? SimpleActivity)?.callContactWithSim(recentCall.phoneNumber, sim)
        } else {
            (activity as? SimpleActivity)?.launchCallIntent(recentCall.phoneNumber, key = BuildConfig.RIGHT_APP_KEY)
        }
    }

    private fun showBlockedNumbers() {
        context.config.showBlockedNumbers = !context.config.showBlockedNumbers
        binding.dialpadToolbar.menu.findItem(R.id.show_blocked_numbers)?.title =
            if (context.config.showBlockedNumbers) context.getString(R.string.hide_blocked_numbers) else context.getString(R.string.show_blocked_numbers)
        context.config.needUpdateRecents = true
        activity?.runOnUiThread {
            refreshItems(false, false, null)
        }
    }

    private fun clearCallHistory() {
        val confirmationText = "${context.getString(R.string.clear_history_confirmation)}\n\n${context.getString(R.string.cannot_be_undone)}"
        ConfirmationDialog(context as SimpleActivity, confirmationText) {
            RecentsHelper(context).removeAllRecentCalls(context as SimpleActivity) {
                allRecentCalls = emptyList()
                activity?.runOnUiThread {
                    refreshItems(invalidate = true, needUpdate = false, callback = null)
                }
            }
        }
    }

    private fun findContactByCall(recentCall: RecentCall): Contact? {
        return allContacts
            .find { it.doesHavePhoneNumber(recentCall.phoneNumber) }
    }

    private fun addContact(recentCall: RecentCall) {
        Intent().apply {
            action = Intent.ACTION_INSERT_OR_EDIT
            type = "vnd.android.cursor.item/contact"
            putExtra(KEY_PHONE, recentCall.phoneNumber)
            context.launchActivityIntent(this)
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun refreshCallLog(event: Events.RefreshCallLog) {
        context.config.needUpdateRecents = true
        refreshItems(invalidate = false, needUpdate = true, callback = null)
    }
}
