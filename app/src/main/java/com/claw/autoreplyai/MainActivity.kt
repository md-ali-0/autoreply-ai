package com.claw.autoreplyai

import android.content.res.ColorStateList
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.viewpager2.widget.ViewPager2
import com.claw.autoreplyai.databinding.ActivityMainBinding
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Hosts the fixed hero header, the tab strip, the pager and the save bar.
 * All editable settings live in the five [BaseSettingsFragment] tabs.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var adapter: TabsAdapter
    private val p: Prefs by lazy { Prefs.get(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        setupWindow()
        setupTabs()
        // Must be set BEFORE bindHero() attaches the listener, otherwise restoring
        // the saved state would fire it and start/stop the service spuriously.
        b.switchEnabled.isChecked = p.enabled
        b.switchMood.isChecked = p.moodEnabled
        bindHero()
        refreshHero()

        // Headless self tests, so they can be run while the phone is locked:
        //   adb shell am start -n com.claw.autoreplyai/.MainActivity --ez selftest true
        //   adb shell am start -n com.claw.autoreplyai/.MainActivity --ez notiftest true
        //   adb shell am start -n com.claw.autoreplyai/.MainActivity --ez aitest true
        //   adb shell am start -n com.claw.autoreplyai/.MainActivity --ez voicetest true
        if (intent?.getBooleanExtra(EXTRA_SELFTEST, false) == true) {
            ReplySelfTest.run(this)
        }
        if (intent?.getBooleanExtra(EXTRA_NOTIFTEST, false) == true) {
            Notify.needsYou(
                this,
                "পরীক্ষা",
                "এটা একটা পরীক্ষার নোটিফিকেশন। ফোন বাজছে/কাঁপছে মানে সব ঠিক আছে।"
            )
        }
        if (intent?.getBooleanExtra(EXTRA_AITEST, false) == true) {
            runAiCheck()
        }
        if (intent?.getBooleanExtra(EXTRA_VOICETEST, false) == true) {
            runVoiceCheck()
        }
    }

    /**
     * Headless voice check: reports what discovery can see, then actually runs a
     * transcription on the newest voice note so the whole path — permission, file
     * pick, upload, parse — is exercised without waiting for a real message.
     */
    private fun runVoiceCheck() {
        VoiceTranscriber.diagnose(this)
        ImageReader.diagnose(this)
        val p = Prefs.get(this)
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            LogStore.add(
                this@MainActivity,
                "ভয়েস টেস্ট: এন্ডপয়েন্ট=${p.transcribeBaseUrl.ifBlank { "(চ্যাট প্রোভাইডার)" }}, " +
                        "মডেল=${p.transcribeModel}, ভাষা=${p.transcribeLanguage.ifBlank { "(অটো)" }}"
            )
            val out = VoiceTranscriber.transcribeForTest(this@MainActivity, p, MessagingApps.WHATSAPP)
            LogStore.add(
                this@MainActivity,
                if (out.isNullOrBlank()) "ভয়েস টেস্ট: ব্যর্থ ✗"
                else "ভয়েস টেস্ট: সফল ✓ — ${out.take(200)}"
            )

            val img = ImageReader.loadLatestBase64(this@MainActivity, ignoreFreshness = true)
            LogStore.add(
                this@MainActivity,
                if (img == null) "ছবি টেস্ট: কোনো ছবি পাওয়া যায়নি ✗"
                else "ছবি টেস্ট: সফল ✓ (base64 ${img.length / 1024}KB)"
            )
        }
    }

    /** Headless API check that writes its result to the in-app log. */
    private fun runAiCheck() {
        val p = Prefs.get(this)
        LogStore.add(this, "AI পরীক্ষা শুরু — ${p.baseUrl} · ${p.model}")
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            val started = System.currentTimeMillis()
            try {
                val reply = AiClient.chat(
                    p.baseUrl, p.apiKey, p.model,
                    listOf(AiClient.Msg("user", "শুধু 'ঠিক আছে' লিখো।"))
                )
                val ms = System.currentTimeMillis() - started
                LogStore.add(this@MainActivity, "✓ AI ঠিক আছে (${ms}ms): ${reply.take(120)}")
            } catch (e: Exception) {
                LogStore.add(this@MainActivity, "✗ AI ব্যর্থ: ${e.message}")
            }
        }
    }

    companion object {
        const val EXTRA_SELFTEST = "selftest"
        const val EXTRA_NOTIFTEST = "notiftest"
        const val EXTRA_AITEST = "aitest"
        const val EXTRA_VOICETEST = "voicetest"
    }

    override fun onResume() {
        super.onResume()
        refreshHero()
        fragmentAt(b.pager.currentItem)?.onShown()
    }

    override fun onPause() {
        saveAllTabs()
        super.onPause()
    }

    // ------------------------------------------------------------------ window

    /** The hero gradient runs under the status bar, so go edge-to-edge and pad it back. */
    private fun setupWindow() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = true
        }
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            b.heroContainer.updatePadding(top = bars.top + dp(22))
            b.saveBar.updatePadding(bottom = bars.bottom + dp(14))
            insets
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // -------------------------------------------------------------------- tabs

    private fun setupTabs() {
        adapter = TabsAdapter(this)
        b.pager.adapter = adapter
        b.pager.offscreenPageLimit = 1

        TabLayoutMediator(b.tabs, b.pager) { tab, position ->
            tab.setText(TabsAdapter.TITLES[position])
        }.attach()

        b.pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                fragmentAt(position)?.onShown()
            }
        })
    }

    /** ViewPager2 tags its fragments "f" + itemId; our itemId is the position. */
    private fun fragmentAt(position: Int): BaseSettingsFragment? =
        supportFragmentManager.findFragmentByTag("f$position") as? BaseSettingsFragment

    private fun saveAllTabs() {
        for (i in 0 until TabsAdapter.COUNT) fragmentAt(i)?.save()
    }

    /** Refresh every live tab from prefs — used after a backup restore. */
    fun reloadAllTabs() {
        for (i in 0 until TabsAdapter.COUNT) fragmentAt(i)?.reloadIfReady()
        refreshHero()
    }

    // -------------------------------------------------------------------- hero

    private fun bindHero() {
        b.switchEnabled.setOnCheckedChangeListener { _, checked ->
            p.enabled = checked
            if (checked) {
                KeepAliveService.start(this)
                LogStore.add(this, "অটো-রিপ্লাই চালু হলো")
            } else {
                KeepAliveService.stop(this)
                LogStore.add(this, "অটো-রিপ্লাই বন্ধ হলো")
            }
            refreshHero()
            (fragmentAt(TabsAdapter.TAB_LOGS) as? LogsFragment)?.refresh()
        }

        b.switchMood.setOnCheckedChangeListener { _, checked ->
            p.moodEnabled = checked
            val msg = if (checked) "মুড চালু — ${p.assistantName} (${p.moodText})" else "মুড বন্ধ"
            LogStore.add(this, msg)
            refreshHero()
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        b.btnSave.setOnClickListener {
            saveAllTabs()
            refreshHero()
            Toast.makeText(this, getString(R.string.saved), Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshHero() {
        val on = p.enabled
        val moodOn = p.moodEnabled
        b.tvHeroStatus.text = when {
            on && moodOn -> "${p.assistantName} · ${p.moodText}"
            on -> getString(R.string.state_on)
            else -> getString(R.string.state_off)
        }
        b.dotHero.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, when {
                on && moodOn -> R.color.hero_alarm
                on -> R.color.hero_on
                else -> R.color.hero_off
            })
        )
        b.tvHeroModel.text = if (p.model.isBlank()) "" else p.model
    }
}
