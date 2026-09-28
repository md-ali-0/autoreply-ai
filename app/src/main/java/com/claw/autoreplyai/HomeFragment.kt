package com.claw.autoreplyai

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import com.claw.autoreplyai.databinding.FragmentHomeBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The landing screen: is the app healthy, what happened today, and what still
 * needs doing.
 *
 * Before this existed the app opened straight onto the permissions list, so the
 * first thing a user saw was a chore rather than an answer to "is it working?".
 *
 * Read-only by design — every number here is derived from state that lives
 * somewhere else, and the shortcuts simply switch sections.
 */
class HomeFragment : BaseSettingsFragment() {

    private var _b: FragmentHomeBinding? = null
    private val b get() = _b!!

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
        _b = FragmentHomeBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewsReady() {
        b.btnHomeFix.setOnClickListener { (activity as? MainActivity)?.goToSection(TabsAdapter.SECTION_SETUP) }
        b.btnHomeSetup.setOnClickListener { (activity as? MainActivity)?.goToSection(TabsAdapter.SECTION_SETUP) }
        b.btnHomePeople.setOnClickListener { (activity as? MainActivity)?.goToSection(TabsAdapter.SECTION_PEOPLE) }
        b.btnHomeLogs.setOnClickListener { (activity as? MainActivity)?.goToSection(TabsAdapter.SECTION_ACTIVITY, TabsAdapter.TAB_LOGS) }
        b.btnHomeBackup.setOnClickListener { (activity as? MainActivity)?.goToSection(TabsAdapter.SECTION_PEOPLE, TabsAdapter.TAB_CONTEXT) }

        b.btnHomeExport.setOnClickListener {
            exportLauncher.launch(Backup.SUGGESTED_NAME)
        }
        b.btnHomeImport.setOnClickListener {
            importLauncher.launch(arrayOf("application/json"))
        }
    }

    override fun load() = refresh()

    override fun save() {
        // Read-only screen: nothing here is persisted.
    }

    override fun onShown() = refresh()

    private fun refresh() {
        if (!viewReady) return
        val ctx = requireContext()

        // ---- health ----
        val checks = listOf(
            isNotificationAccessEnabled() to getString(R.string.notif_access),
            SendAccessibilityService.isRunning() to getString(R.string.acc_service),
            ContactResolver.hasPermission(ctx) to getString(R.string.contacts_perm),
            VoiceTranscriber.hasVoiceAccess(ctx) to getString(R.string.audio_perm),
            prefs.cloudConfigured() to getString(R.string.cloud_label),
        )
        val missing = checks.filter { !it.first }

        b.tvHomeHealth.text = if (missing.isEmpty()) {
            getString(R.string.home_ready)
        } else {
            getString(R.string.home_needs, missing.size)
        }
        b.tvHomeHealthDetail.text = if (missing.isEmpty()) {
            checks.joinToString(" · ") { it.second }
        } else {
            missing.joinToString(" · ") { it.second }
        }
        b.btnHomeFix.visibility = if (missing.isEmpty()) View.GONE else View.VISIBLE

        // ---- today's numbers, from the digest ledger ----
        val since = startOfToday()
        val today = DigestStore.read(ctx).filter { it.at >= since }
        b.tvStatReplies.text = today.count { it.action == DigestStore.ACTION_REPLIED }.toString()
        b.tvStatWaiting.text = today.count {
            it.action == DigestStore.ACTION_HELD ||
                    it.action == DigestStore.ACTION_APPROVAL ||
                    it.action == DigestStore.ACTION_FAILED
        }.toString()
        b.tvStatSkipped.text = today.count { it.action == DigestStore.ACTION_BLOCKED }.toString()
        b.tvHomeStatsEmpty.visibility = if (today.isEmpty()) View.VISIBLE else View.GONE

        // ---- cloud backup status line ----
        val at = prefs.cloudLastUpload
        b.tvHomeCloudStatus.text = when {
            !prefs.cloudConfigured() -> getString(R.string.cloud_missing)
            at <= 0L -> getString(R.string.cloud_never)
            else -> getString(
                R.string.cloud_last,
                SimpleDateFormat("dd MMM, hh:mm a", Locale.US).format(Date(at))
            )
        }
    }

    private fun startOfToday(): Long {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun isNotificationAccessEnabled(): Boolean =
        androidx.core.app.NotificationManagerCompat
            .getEnabledListenerPackages(requireContext())
            .contains(requireContext().packageName)

    // ---------------------------------------------------------------- backup

    private fun writeBackup(uri: Uri) {
        try {
            val json = Backup.build(requireContext())
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
            (activity as? MainActivity)?.reloadAllTabs()
        } catch (e: Exception) {
            toast("ফিরিয়ে আনা যায়নি: ${e.message}")
        }
    }

    override fun onViewsGone() {
        _b = null
    }
}
