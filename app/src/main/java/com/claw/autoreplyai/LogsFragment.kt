package com.claw.autoreplyai

import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.claw.autoreplyai.databinding.FragmentLogsBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
        b.btnDigest.setOnClickListener { showDigest() }
        b.btnProbe.setOnClickListener {
            WhatsAppNotificationListener.probe(requireContext())
            ReplySelfTest.run(requireContext())
            VoiceTranscriber.diagnose(requireContext())
            refresh()
        }
    }

    /** Builds the "what did I miss" brief, posts it, and shows it on screen. */
    private fun showDigest() {
        val ctx = requireContext()
        toast(getString(R.string.digest_working))
        viewLifecycleOwner.lifecycleScope.launch {
            val summary = withContext(Dispatchers.IO) {
                try {
                    Digest.build(ctx.applicationContext, useAi = true)
                } catch (e: Exception) {
                    LogStore.add(ctx, "ডাইজেস্ট ব্যর্থ: ${e.message}")
                    null
                }
            }
            if (!isAdded) return@launch
            if (summary == null) {
                toast(getString(R.string.digest_failed))
                return@launch
            }
            Notify.digest(ctx, summary)
            refresh()
            try {
                MaterialAlertDialogBuilder(ctx)
                    .setTitle(R.string.digest_title)
                    .setMessage(summary)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            } catch (_: Exception) {
                // a dialog failure must not lose the digest — it is in the log too
            }
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
