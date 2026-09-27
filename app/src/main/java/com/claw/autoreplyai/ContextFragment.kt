package com.claw.autoreplyai

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import com.claw.autoreplyai.databinding.FragmentContextBinding
import com.claw.autoreplyai.databinding.ItemContextRowBinding
import com.google.android.material.textfield.TextInputEditText

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
        val memInfo: TextView
    )

    private val rows = mutableListOf<Row>()

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let { writeBackup(it) } }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { readBackup(it) } }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _b = FragmentContextBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewsReady() {
        b.btnAddRow.setOnClickListener { addRow("", "") }

        b.btnExport.setOnClickListener {
            save() // capture whatever is on screen before snapshotting
            exportLauncher.launch(Backup.SUGGESTED_NAME)
        }
        b.btnImport.setOnClickListener {
            importLauncher.launch(arrayOf("application/json"))
        }
    }

    override fun load() {
        b.etAssistantName.setText(prefs.assistantName)
        b.etMoodText.setText(prefs.moodText)
        b.etSafety.setText(prefs.safetyRule)

        rows.clear()
        b.ctxContainer.removeAllViews()

        val entries = ContactContext.all(requireContext())
        if (entries.isEmpty()) {
            addRow("", "")
        } else {
            entries.forEach { addRow(it.name, it.context) }
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

        ContactContext.save(
            requireContext(),
            rows.map {
                ContactContext.Entry(
                    it.name.text?.toString().orEmpty().trim(),
                    it.body.text?.toString().orEmpty().trim()
                )
            }
        )
    }

    // ------------------------------------------------------------------ rows

    private fun addRow(name: String, context: String) {
        val row = ItemContextRowBinding.inflate(layoutInflater, b.ctxContainer, false)
        row.etCtxName.setText(name)
        row.etCtxBody.setText(context)

        val entry = Row(row.root, row.etCtxName, row.etCtxBody, row.tvMemInfo)
        updateMemory(entry)

        row.btnRemoveRow.setOnClickListener {
            b.ctxContainer.removeView(entry.root)
            rows.remove(entry)
            if (rows.isEmpty()) addRow("", "")
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
            getString(R.string.memory_count, ChatMemory.count(requireContext(), contact))
        }
    }

    // ---------------------------------------------------------------- backup

    private fun writeBackup(uri: Uri) {
        try {
            val json = Backup.build(requireContext())
            // A backup that cannot be read back is worthless — check before writing.
            val summary = Backup.verify(json)
            requireContext().contentResolver.openOutputStream(uri)?.use { out ->
                out.write(json.toByteArray(Charsets.UTF_8))
            }
            toast("ব্যাকআপ সেভ হয়েছে — $summary")
        } catch (e: Exception) {
            toast("সেভ করা যায়নি: ${e.message}")
        }
    }

    private fun readBackup(uri: Uri) {
        try {
            val text = requireContext().contentResolver.openInputStream(uri)?.use {
                it.readBytes().toString(Charsets.UTF_8)
            } ?: throw IllegalStateException("ফাইল পড়া গেল না")

            val summary = Backup.restore(requireContext(), text)
            toast(summary)
            // Refresh in place. Do NOT recreate() — that would run the outgoing
            // fragments' save() and write their stale values over the restore.
            (activity as? MainActivity)?.reloadAllTabs()
        } catch (e: Exception) {
            toast("ফিরিয়ে আনা যায়নি: ${e.message}")
        }
    }

    override fun onViewsGone() {
        rows.clear()
        _b = null
    }
}
