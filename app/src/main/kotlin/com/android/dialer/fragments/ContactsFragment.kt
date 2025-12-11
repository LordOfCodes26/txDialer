package com.android.dialer.fragments

import android.content.Context
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import androidx.recyclerview.widget.RecyclerView
import com.goodwy.commons.adapters.MyRecyclerViewAdapter
import com.goodwy.commons.extensions.*
import com.goodwy.commons.helpers.*
import com.goodwy.commons.models.contacts.Contact
import com.android.dialer.R
import com.android.dialer.activities.MainActivity
import com.android.dialer.activities.SimpleActivity
import com.android.dialer.adapters.ContactsAdapter
import com.android.dialer.databinding.FragmentContactsBinding
import com.android.dialer.databinding.FragmentLettersLayoutBinding
import com.android.dialer.extensions.launchCreateNewContactIntent
import com.android.dialer.extensions.setupWithContacts
import com.android.dialer.extensions.startCallWithConfirmationCheck
import com.android.dialer.extensions.startContactDetailsIntentRecommendation
import com.android.dialer.interfaces.RefreshItemsListener
import java.util.concurrent.Executors

class ContactsFragment(context: Context, attributeSet: AttributeSet) : MyViewPagerFragment<MyViewPagerFragment.LettersInnerBinding>(context, attributeSet),
    RefreshItemsListener {
    private lateinit var binding: FragmentLettersLayoutBinding
    private var allContacts = ArrayList<Contact>()
    private val handler = Handler(Looper.getMainLooper())
    private val backgroundExecutor = Executors.newSingleThreadExecutor()
    private var searchDebounceRunnable: Runnable? = null

    override fun onFinishInflate() {
        super.onFinishInflate()
        binding = FragmentLettersLayoutBinding.bind(FragmentContactsBinding.bind(this).contactsFragment)
        innerBinding = LettersInnerBinding(binding)
    }

    override fun setupFragment() {
        val useSurfaceColor = context.isDynamicTheme() && !context.isSystemInDarkMode()
        val backgroundColor = if (useSurfaceColor) context.getSurfaceColor() else context.getProperBackgroundColor()
        binding.root.setBackgroundColor(backgroundColor)
        //binding.contactsFragment.setBackgroundColor(context.getProperBackgroundColor())

        val placeholderResId = if (context.hasPermission(PERMISSION_READ_CONTACTS)) {
            R.string.no_contacts_found
        } else {
            R.string.could_not_access_contacts
        }

        binding.fragmentPlaceholder.text = context.getString(placeholderResId)

        val placeholderActionResId = if (context.hasPermission(PERMISSION_READ_CONTACTS)) {
            R.string.create_new_contact
        } else {
            R.string.request_access
        }

        binding.fragmentPlaceholder2.apply {
            text = context.getString(placeholderActionResId)
            underlineText()
            setOnClickListener {
                if (context.hasPermission(PERMISSION_READ_CONTACTS)) {
                    activity?.launchCreateNewContactIntent()
                } else {
                    requestReadContactsPermission()
                }
            }
        }
    }

    override fun setupColors(textColor: Int, primaryColor: Int, accentColor: Int) {
        binding.apply {
            (fragmentList.adapter as? MyRecyclerViewAdapter)?.apply {
                updateTextColor(textColor)
                updatePrimaryColor()
                updateBackgroundColor(context.getProperBackgroundColor())
            }
            fragmentPlaceholder.setTextColor(textColor)
            fragmentPlaceholder2.setTextColor(accentColor)

            letterFastscroller.textColor = textColor.getColorStateList()
            letterFastscroller.pressedTextColor = accentColor
            letterFastscrollerThumb.setupWithFastScroller(letterFastscroller)
            letterFastscrollerThumb.textColor = accentColor.getContrastColor()
            letterFastscrollerThumb.thumbColor = accentColor.getColorStateList()
        }
    }

    override fun refreshItems(invalidate: Boolean, needUpdate: Boolean, callback: (() -> Unit)?) {
        val privateCursor = context?.getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true)
        ContactsHelper(context).getContacts(showOnlyContactsWithNumbers = true) { contacts ->
            allContacts = contacts

            if (SMT_PRIVATE !in context.baseConfig.ignoredContactSources) {
                val privateContacts = MyContactsContentProvider.getContacts(context, privateCursor)
                if (privateContacts.isNotEmpty()) {
                    allContacts.addAll(privateContacts)
                    allContacts.sort()
                }
            }
            (activity as MainActivity).cacheContacts()

            activity?.runOnUiThread {
                gotContacts(contacts)
                callback?.invoke()
            }
        }
    }

    private fun gotContacts(contacts: ArrayList<Contact>) {
        setupLetterFastScroller(contacts)
        if (contacts.isEmpty()) {
            binding.apply {
                fragmentPlaceholder.beVisible()
                fragmentPlaceholder2.beVisible()
                fragmentList.beGone()
            }
        } else {
            binding.apply {
                fragmentPlaceholder.beGone()
                fragmentPlaceholder2.beGone()
                fragmentList.beVisible()

//                fragmentList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
//                    override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
//                        super.onScrollStateChanged(recyclerView, newState)
//                        activity?.hideKeyboard()
//                    }
//                })
                fragmentList.setOnTouchListener { _, _ ->
                    activity?.hideKeyboard()
                    false
                }
            }

            if (binding.fragmentList.adapter == null) {
                // Optimize RecyclerView for large lists
                binding.fragmentList.apply {
                    setHasFixedSize(true)
                    setItemViewCacheSize(20) // Increase cache size for smoother scrolling
                    // Disable animations for large lists to improve performance
                    if (contacts.size > 500) {
                        itemAnimator = null
                    }
                }
                
                ContactsAdapter(
                    activity = activity as SimpleActivity,
                    contacts = contacts,
                    recyclerView = binding.fragmentList,
                    refreshItemsListener = this,
                    showIcon = false,
                    showNumber = context.baseConfig.showPhoneNumbers,
                    itemClick = {
                        activity?.startCallWithConfirmationCheck(it as Contact)
                    },
                    profileIconClick = {
                        activity?.startContactDetailsIntentRecommendation(it as Contact)
                    }
                ).apply {
                    binding.fragmentList.adapter = this
                }

                if (context.areSystemAnimationsEnabled && contacts.size <= 500) {
                    binding.fragmentList.scheduleLayoutAnimation()
                }
            } else {
                (binding.fragmentList.adapter as ContactsAdapter).updateItems(contacts)
            }

            // Move expensive letter calculation to background thread for large lists
            if (contacts.size > 500) {
                backgroundExecutor.execute {
                    try {
                        val allNotEmpty = contacts.filter { it.getNameToDisplay().isNotEmpty() }
                        val all = allNotEmpty.map { it.getNameToDisplay().substring(0, 1) }
                        val unique: Set<String> = HashSet(all)
                        val sizeUnique = unique.size
                        val textAppearanceRes = if (isHighScreenSize()) {
                            when {
                                sizeUnique > 48 -> R.style.LetterFastscrollerStyleTooTiny
                                sizeUnique > 37 -> R.style.LetterFastscrollerStyleTiny
                                else -> R.style.LetterFastscrollerStyleSmall
                            }
                        } else {
                            when {
                                sizeUnique > 36 -> R.style.LetterFastscrollerStyleTooTiny
                                sizeUnique > 30 -> R.style.LetterFastscrollerStyleTiny
                                else -> R.style.LetterFastscrollerStyleSmall
                            }
                        }
                        handler.post {
                            binding.letterFastscroller.textAppearanceRes = textAppearanceRes
                        }
                    } catch (_: Exception) { }
                }
            } else {
                try {
                    //Decrease the font size based on the number of letters in the letter scroller
                    val allNotEmpty = contacts.filter { it.getNameToDisplay().isNotEmpty() }
                    val all = allNotEmpty.map { it.getNameToDisplay().substring(0, 1) }
                    val unique: Set<String> = HashSet(all)
                    val sizeUnique = unique.size
                    if (isHighScreenSize()) {
                        if (sizeUnique > 48) binding.letterFastscroller.textAppearanceRes = R.style.LetterFastscrollerStyleTooTiny
                        else if (sizeUnique > 37) binding.letterFastscroller.textAppearanceRes = R.style.LetterFastscrollerStyleTiny
                        else binding.letterFastscroller.textAppearanceRes = R.style.LetterFastscrollerStyleSmall
                    } else {
                        if (sizeUnique > 36) binding.letterFastscroller.textAppearanceRes = R.style.LetterFastscrollerStyleTooTiny
                        else if (sizeUnique > 30) binding.letterFastscroller.textAppearanceRes = R.style.LetterFastscrollerStyleTiny
                        else binding.letterFastscroller.textAppearanceRes = R.style.LetterFastscrollerStyleSmall
                    }
                } catch (_: Exception) { }
            }
        }
    }

    private fun isHighScreenSize(): Boolean {
        return when (resources.configuration.screenLayout
            and Configuration.SCREENLAYOUT_LONG_MASK) {
            Configuration.SCREENLAYOUT_LONG_NO -> false
            else -> true
        }
    }

    private fun setupLetterFastScroller(contacts: ArrayList<Contact>) {
        binding.letterFastscroller.setupWithContacts(binding.fragmentList, contacts)
    }

    override fun onSearchClosed() {
        binding.fragmentPlaceholder.beVisibleIf(allContacts.isEmpty())
        (binding.fragmentList.adapter as? ContactsAdapter)?.updateItems(allContacts)
        setupLetterFastScroller(allContacts)
    }

    override fun onSearchQueryChanged(text: String) {
        // Cancel previous debounce
        searchDebounceRunnable?.let { handler.removeCallbacks(it) }
        
        // Debounce search for better performance with large lists
        searchDebounceRunnable = Runnable {
            val fixedText = text.trim().replace("\\s+".toRegex(), " ")
            
            // Move filtering to background thread for large lists
            if (allContacts.size > 500) {
                backgroundExecutor.execute {
                    val shouldNormalize = fixedText.normalizeString() == fixedText
                    val filtered = allContacts.filter { contact ->
                        getProperText(contact.getNameToDisplay(), shouldNormalize).contains(fixedText, true) ||
                            getProperText(contact.nickname, shouldNormalize).contains(fixedText, true) ||
                            (fixedText.toIntOrNull() != null && contact.doesContainPhoneNumber(fixedText, true)) ||
                            contact.emails.any { it.value.contains(fixedText, true) } ||
                            contact.relations.any { it.name.contains(fixedText, true) } ||
                            contact.addresses.any { getProperText(it.value, shouldNormalize).contains(fixedText, true) } ||
                            contact.IMs.any { it.value.contains(fixedText, true) } ||
                            getProperText(contact.notes, shouldNormalize).contains(fixedText, true) ||
                            getProperText(contact.organization.company, shouldNormalize).contains(fixedText, true) ||
                            getProperText(contact.organization.jobPosition, shouldNormalize).contains(fixedText, true) ||
                            contact.websites.any { it.contains(fixedText, true) }
                    } as ArrayList

                    filtered.sortBy {
                        val nameToDisplay = it.getNameToDisplay()
                        !getProperText(nameToDisplay, shouldNormalize).startsWith(fixedText, true) && !nameToDisplay.contains(fixedText, true)
                    }

                    handler.post {
                        binding.fragmentPlaceholder.beVisibleIf(filtered.isEmpty())
                        (binding.fragmentList.adapter as? ContactsAdapter)?.updateItems(filtered, fixedText)
                        setupLetterFastScroller(filtered)
                    }
                }
            } else {
                // For smaller lists, filter on main thread
                val shouldNormalize = fixedText.normalizeString() == fixedText
                val filtered = allContacts.filter { contact ->
                    getProperText(contact.getNameToDisplay(), shouldNormalize).contains(fixedText, true) ||
                        getProperText(contact.nickname, shouldNormalize).contains(fixedText, true) ||
                        (fixedText.toIntOrNull() != null && contact.doesContainPhoneNumber(fixedText, true)) ||
                        contact.emails.any { it.value.contains(fixedText, true) } ||
                        contact.relations.any { it.name.contains(fixedText, true) } ||
                        contact.addresses.any { getProperText(it.value, shouldNormalize).contains(fixedText, true) } ||
                        contact.IMs.any { it.value.contains(fixedText, true) } ||
                        getProperText(contact.notes, shouldNormalize).contains(fixedText, true) ||
                        getProperText(contact.organization.company, shouldNormalize).contains(fixedText, true) ||
                        getProperText(contact.organization.jobPosition, shouldNormalize).contains(fixedText, true) ||
                        contact.websites.any { it.contains(fixedText, true) }
                } as ArrayList

                filtered.sortBy {
                    val nameToDisplay = it.getNameToDisplay()
                    !getProperText(nameToDisplay, shouldNormalize).startsWith(fixedText, true) && !nameToDisplay.contains(fixedText, true)
                }

                binding.fragmentPlaceholder.beVisibleIf(filtered.isEmpty())
                (binding.fragmentList.adapter as? ContactsAdapter)?.updateItems(filtered, fixedText)
                setupLetterFastScroller(filtered)
            }
        }
        
        // Debounce delay: 150ms for small lists, 300ms for large lists
        val delay = if (allContacts.size > 500) 300L else 150L
        searchDebounceRunnable?.let { handler.postDelayed(it, delay) }
    }

    private fun requestReadContactsPermission() {
        activity?.handlePermission(PERMISSION_READ_CONTACTS) {
            if (it) {
                binding.fragmentPlaceholder.text = context.getString(R.string.no_contacts_found)
                binding.fragmentPlaceholder2.text = context.getString(R.string.create_new_contact)
                ContactsHelper(context).getContacts { contacts ->
                    activity?.runOnUiThread {
                        gotContacts(contacts)
                    }
                }
            }
        }
    }

    override fun myRecyclerView() = binding.fragmentList
}
