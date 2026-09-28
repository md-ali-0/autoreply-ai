package com.claw.autoreplyai

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.claw.autoreplyai.databinding.FragmentTimingBinding

class TimingFragment : BaseSettingsFragment() {

    private var _b: FragmentTimingBinding? = null
    private val b get() = _b!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _b = FragmentTimingBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewsReady() {
        b.switchHours.setOnCheckedChangeListener { _, v -> prefs.hoursEnabled = v }
    }

    override fun load() {
        b.switchHours.isChecked = prefs.hoursEnabled
        b.etHourStart.setText(prefs.hourStart.toString())
        b.etMinStart.setText(prefs.hourStartMin.toString())
        b.etHourEnd.setText(prefs.hourEnd.toString())
        b.etMinEnd.setText(prefs.hourEndMin.toString())
        b.etDelayMin.setText(prefs.delayMinSec.toString())
        b.etDelayMax.setText(prefs.delayMaxSec.toString())
        b.etCooldown.setText(prefs.cooldownSec.toString())
        b.etMaxChars.setText(prefs.replyMaxChars.toString())
        b.etSignature.setText(prefs.signature)
    }

    override fun save() {
        if (!viewReady) return
        prefs.hourStart = b.etHourStart.text?.toString()?.toIntOrNull() ?: 9
        prefs.hourStartMin = b.etMinStart.text?.toString()?.toIntOrNull() ?: 45
        prefs.hourEnd = b.etHourEnd.text?.toString()?.toIntOrNull() ?: 19
        prefs.hourEndMin = b.etMinEnd.text?.toString()?.toIntOrNull() ?: 30
        prefs.delayMinSec = b.etDelayMin.text?.toString()?.toIntOrNull() ?: 3
        prefs.delayMaxSec = b.etDelayMax.text?.toString()?.toIntOrNull() ?: 5
        prefs.cooldownSec = b.etCooldown.text?.toString()?.toIntOrNull() ?: 60
        prefs.replyMaxChars = b.etMaxChars.text?.toString()?.toIntOrNull() ?: 90
        prefs.signature = b.etSignature.text?.toString().orEmpty()
    }

    override fun onViewsGone() {
        _b = null
    }
}
