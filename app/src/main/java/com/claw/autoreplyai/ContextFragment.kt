package com.claw.autoreplyai

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.claw.autoreplyai.databinding.FragmentContextBinding
import com.claw.autoreplyai.databinding.ItemContextRowBinding
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Per-contact background notes, the confidentiality rule, per-contact memory
 * controls, and whole-app backup/restore.
 *
 * Isolation is structural: each contact's notes live in their own entry and only
 * that entry is loaded when replying to them, so no prompt ever contains another
 * contact's information.
 */
class ContextFragment : BaseSettingsFragment() {

    private var _b: FragmentContextBinding? = null
    private val b get() = _b!!

    private class Row(
        val root: View,
        val name: TextInputEditText,
        val body: TextInputEditText,
        val memInfo: TextView,
        val close: MaterialSwitch
    )

    private val rows = mutableListOf<Row>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _b = FragmentContextBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewsReady() {
        b.btnAddRow.setOnClickListener { addRow("", "", false) }

        b.switchCloudAuto.setOnCheckedChangeListener { _, v -> prefs.cloudAutoUpload = v }
        b.btnCloudUpload.setOnClickListener { runCloud(upload = true) }
        b.btnCloudDownload.setOnClickListener { runCloud(upload = false) }
    }

    /**
     * Both cloud calls block on the network, so they run off the main thread and
     * report through the status line rather than a toast that disappears.
     */
    private fun runCloud(upload: Boolean) {
        save() // the endpoint and token are edited on this tab
        val ctx = requireContext()

        if (!prefs.cloudConfigured()) {
            b.tvCloudStatus.text = getString(R.string.cloud_missing)
            return
        }

        b.btnCloudUpload.isEnabled = false
        b.btnCloudDownload.isEnabled = false
        b.tvCloudStatus.text = getString(if (upload) R.string.cloud_uploading else R.string.cloud_downloading)

        viewLifecycleOwner.lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                if (upload) CloudBackup.upload(ctx) else CloudBackup.download(ctx)
            }

            if (!isAdded) return@launch
            b.btnCloudUpload.isEnabled = true
            b.btnCloudDownload.isEnabled = true
            b.tvCloudStatus.text = outcome.message
            toast(outcome.message.lineSequence().first())

            if (outcome.ok) {
                // A restore replaces everything the other tabs are showing.
                if (!upload) (activity as? MainActivity)?.reloadAllTabs()
                refreshCloudStatus()
            }
        }
    }

    private fun refreshCloudStatus() {
        val at = prefs.cloudLastUpload
        val when_ = if (at <= 0L) {
            getString(R.string.cloud_never)
        } else {
            SimpleDateFormat("dd MMM, hh:mm a", Locale.US).format(Date(at))
        }
        b.tvCloudStatus.text = getString(R.string.cloud_last, when_)
    }

    override fun load() {
        b.etAssistantName.setText(prefs.assistantName)
        b.etMoodText.setText(prefs.moodText)
        b.etSafety.setText(prefs.safetyRule)

        b.etCloudToken.setText(prefs.cloudToken)
        b.switchCloudAuto.isChecked = prefs.cloudAutoUpload
        refreshCloudStatus()

        rows.clear()
        b.ctxContainer.removeAllViews()

        val entries = ContactContext.all(requireContext())
        if (entries.isEmpty()) {
            addRow("", "", false)
        } else {
            entries.forEach { addRow(it.name, it.context, it.close) }
        }
    }

    override fun save() {
        if (!viewReady) return

        prefs.assistantName = b.etAssistantName.text?.toString().orEmpty()
            .ifBlank { "ক্ল" }
        prefs.moodText = b.etMoodText.text?.toString().orEmpty()
            .ifBlank { "ঘুমাচ্ছে" }
        prefs.safetyRule = b.etSafety.text?.toString().orEmpty()
            .ifBlank { Prefs.DEFAULT_SAFETY }

        prefs.cloudToken = b.etCloudToken.text?.toString().orEmpty()

        ContactContext.save(
            requireContext(),
            rows.map {
                ContactContext.Entry(
                    it.name.text?.toString().orEmpty().trim(),
                    it.body.text?.toString().orEmpty().trim(),
                    it.close.isChecked
                )
            }
        )
    }

    // ------------------------------------------------------------------ rows

    private fun addRow(name: String, context: String, close: Boolean) {
        val row = ItemContextRowBinding.inflate(layoutInflater, b.ctxContainer, false)
        row.etCtxName.setText(name)
        row.etCtxBody.setText(context)
        row.switchCtxClose.isChecked = close

        val entry = Row(row.root, row.etCtxName, row.etCtxBody, row.tvMemInfo, row.switchCtxClose)
        updateMemory(entry)

        row.btnRemoveRow.setOnClickListener {
            b.ctxContainer.removeView(entry.root)
            rows.remove(entry)
            if (rows.isEmpty()) addRow("", "", false)
        }

        row.btnClearMem.setOnClickListener {
            val contact = entry.name.text?.toString().orEmpty().trim()
            if (contact.isEmpty()) {
                toast("আগে কন্টাক্টের নাম লিখুন")
                return@setOnClickListener
            }
            ChatMemory.clear(requireContext(), contact)
            updateMemory(entry)
            toast("$contact — মেমোরি মুছে দেওয়া হলো")
        }

        rows.add(entry)
        b.ctxContainer.addView(row.root)
    }

    private fun updateMemory(entry: Row) {
        val contact = entry.name.text?.toString().orEmpty().trim()
        entry.memInfo.text = if (contact.isEmpty()) {
            ""
        } else {
            val ctx = requireContext()
            val fresh = ChatMemory.activeCount(ctx, contact)
            val total = ChatMemory.storedCount(ctx, contact)
            // Say so explicitly when turns are being withheld for being old — otherwise
            // the number dropping looks like data loss rather than ageing.
            if (total > fresh) {
                getString(R.string.memory_count_stale, fresh, total - fresh)
            } else {
                getString(R.string.memory_count, fresh)
            }
        }
    }

    override fun onViewsGone() {
        rows.clear()
        _b = null
    }
}
