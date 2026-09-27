package com.claw.autoreplyai

import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.claw.autoreplyai.databinding.FragmentLogsBinding

class LogsFragment : BaseSettingsFragment() {

    private var _b: FragmentLogsBinding? = null
    private val b get() = _b!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _b = FragmentLogsBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewsReady() {
        b.btnRefreshLogs.setOnClickListener { refresh() }
        b.btnClearLogs.setOnClickListener {
            LogStore.clear(requireContext())
            refresh()
        }
        b.btnProbe.setOnClickListener {
            WhatsAppNotificationListener.probe(requireContext())
            ReplySelfTest.run(requireContext())
            refresh()
        }
    }

    override fun load() = refresh()

    override fun save() {
        // logs are already persisted by LogStore
    }

    override fun onShown() = refresh()

    fun refresh() {
        if (!viewReady) return
        val ctx = requireContext()
        val logs = LogStore.read(ctx)
        b.logContainer.removeAllViews()
        if (logs.isEmpty()) {
            b.logContainer.addView(line(getString(R.string.no_logs), R.color.text_dim))
            return
        }
        for (entry in logs) {
            b.logContainer.addView(line(entry, R.color.text))
        }
    }

    private fun line(text: String, colorRes: Int): TextView = TextView(requireContext()).apply {
        this.text = text
        textSize = 11.5f
        typeface = Typeface.MONOSPACE
        setTextColor(ContextCompat.getColor(requireContext(), colorRes))
        setPadding(8, 7, 8, 7)
    }

    override fun onViewsGone() {
        _b = null
    }
}
