package com.claw.autoreplyai

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.claw.autoreplyai.databinding.FragmentContactsBinding

class ContactsFragment : BaseSettingsFragment() {

    private var _b: FragmentContactsBinding? = null
    private val b get() = _b!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _b = FragmentContactsBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewsReady() {
        b.switchOnlyContacts.setOnCheckedChangeListener { _, v -> prefs.onlyContacts = v }
        b.switchSkipGroups.setOnCheckedChangeListener { _, v -> prefs.skipGroups = v }
        b.switchSkipUnknown.setOnCheckedChangeListener { _, v -> prefs.skipUnknown = v }
    }

    override fun load() {
        b.switchOnlyContacts.isChecked = prefs.onlyContacts
        b.switchSkipGroups.isChecked = prefs.skipGroups
        b.switchSkipUnknown.isChecked = prefs.skipUnknown
        b.etContactList.setText(prefs.contactList)
        b.etNeverReply.setText(prefs.neverReply)
        b.etCountryCode.setText(prefs.countryCode)
    }

    override fun save() {
        if (!viewReady) return
        // The switches also persist on toggle, but writing them here as well means a
        // global Save always stores what is actually on screen. Without this, a
        // switch could sit unchecked while prefs still said otherwise — which for a
        // safety setting like "never reply to unknown numbers" is the wrong way round.
        prefs.onlyContacts = b.switchOnlyContacts.isChecked
        prefs.skipGroups = b.switchSkipGroups.isChecked
        prefs.skipUnknown = b.switchSkipUnknown.isChecked

        prefs.contactList = b.etContactList.text?.toString().orEmpty()
        prefs.neverReply = b.etNeverReply.text?.toString().orEmpty()
        prefs.countryCode = b.etCountryCode.text?.toString().orEmpty().ifBlank { "880" }
    }

    override fun onViewsGone() {
        _b = null
    }
}
