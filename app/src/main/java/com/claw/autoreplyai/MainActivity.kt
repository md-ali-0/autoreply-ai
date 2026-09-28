package com.claw.autoreplyai

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.viewpager2.widget.ViewPager2
import com.claw.autoreplyai.databinding.ActivityMainBinding
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Hosts the hero header, the bottom navigation, the per-section sub-tabs, the pager
 * and the save bar.
 *
 * Four bottom-navigation sections hold the six original screens: setup
 * (permissions, AI), people (contacts, context) and activity (timing, logs) each
 * show a secondary tab row, while home is a single page.
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
        setupNavigation()
        // Strip retired AI providers (currently apinex.bond) from storage on every
        // launch, so an already-installed device is cleaned without the user having
        // to open the AI settings screen and delete the entry by hand.
        AiProviderStore.purgeBlocked(this)
        // Must be set BEFORE bindHero() attaches the listener, otherwise restoring
        // the saved state would fire it and start/stop the service spuriously.
        b.switchEnabled.isChecked = p.enabled
        b.switchMood.isChecked = p.moodEnabled
        bindHero()
        bindMoodEditor()
        refreshHero()

        // Headless self tests, so they can be run while the phone is locked:
        //   adb shell am start -n com.claw.autoreplyai/.MainActivity --ez selftest true
        //   adb shell am start -n com.claw.autoreplyai/.MainActivity --ez notiftest true
        //   adb shell am start -n com.claw.autoreplyai/.MainActivity --ez aitest true
        //   adb shell am start -n com.claw.autoreplyai/.MainActivity --ez moodtest true
        //   adb shell am start -n com.claw.autoreplyai/.MainActivity --ez voicetest true
        //   adb shell am start -n com.claw.autoreplyai/.MainActivity --ez navtest true
        //
        // All dispatched through one place. Two copies of this list is how `moodtest`
        // came to exist in only one of them and silently never ran.
        handleTestExtras(intent)
    }

    /**
     * Headless test extras, handled here so they work whether the activity was just
     * created or was already on top.
     *
     * `onCreate` alone is not enough: `adb shell am start --ez navtest true` delivered
     * to an already-running MainActivity goes to `onNewIntent`, so the extra was
     * silently ignored and the test looked like it had passed when it never ran.
     */
    private fun handleTestExtras(intent: Intent?) {
        if (intent == null) return

        if (intent.getBooleanExtra(EXTRA_SELFTEST, false)) ReplySelfTest.run(this)

        if (intent.getBooleanExtra(EXTRA_NOTIFTEST, false)) {
            Notify.needsYou(
                this,
                "পরীক্ষা",
                "এটা একটা পরীক্ষার নোটিফিকেশন। ফোন বাজছে/কাঁপছে মানে সব ঠিক আছে।"
            )
        }
        if (intent.getBooleanExtra(EXTRA_AITEST, false)) runAiCheck()
        if (intent.getBooleanExtra(EXTRA_MOODTEST, false)) runMoodCheck()
        if (intent.getBooleanExtra(EXTRA_VOICETEST, false)) runVoiceCheck()
        if (intent.getBooleanExtra(EXTRA_CLOUDTEST, false)) runCloudCheck()
        if (intent.getBooleanExtra(EXTRA_NAVTEST, false)) {
            // After layout: switching sections drives the pager and the nav bar, and
            // both need real views to talk to.
            b.root.post { runNavCheck() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleTestExtras(intent)
    }

    /**
     * Walks every section in turn. This is the exact path that recursed until the
     * stack overflowed, so a headless run is a real regression test for it.
     */
    private fun runNavCheck() {
        for (section in 0 until TabsAdapter.SECTION_COUNT) {
            try {
                goToSection(section)
                LogStore.add(this, "নেভ টেস্ট: section $section → ঠিক আছে")
            } catch (t: Throwable) {
                LogStore.add(this, "নেভ টেস্ট: section $section → ব্যর্থ: ${t.javaClass.simpleName}")
            }
        }
        // ...and once more from the end, to catch a loop that only closes on the
        // second pass through the same section.
        for (section in TabsAdapter.SECTION_COUNT - 1 downTo 0) {
            try {
                goToSection(section)
            } catch (t: Throwable) {
                LogStore.add(this, "নেভ টেস্ট (উল্টো): $section → ব্যর্থ: ${t.javaClass.simpleName}")
                return
            }
        }
        LogStore.add(this, "নেভ টেস্ট: সব সেকশন ✓")
    }

    /** Headless cloud-backup check: uploads the current snapshot and logs the result. */
    private fun runCloudCheck() {
        val p = Prefs.get(this)
        LogStore.add(this, "ক্লাউড টেস্ট: ${p.cloudUrl} · টোকেন ${if (p.cloudToken.isBlank()) "নেই" else "আছে"}")
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            val up = CloudBackup.upload(this@MainActivity)
            LogStore.add(this@MainActivity, "ক্লাউড আপলোড: ${if (up.ok) "✓" else "✗"} ${up.message}")
            if (up.ok) {
                val down = CloudBackup.download(this@MainActivity)
                LogStore.add(this@MainActivity, "ক্লাউড ডাউনলোড: ${if (down.ok) "✓" else "✗"} ${down.message}")
            }
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

    /**
     * Headless check of the mood/gatekeeper path, which builds its own prompt and
     * parses a different JSON shape from the normal reply path — an ordinary AI test
     * passing says nothing about it.
     */
    private fun runMoodCheck() {
        val p = Prefs.get(this)
        LogStore.add(this, "মুড পরীক্ষা শুরু — moodEnabled=${p.moodEnabled} · ${p.model}")
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            val result = try {
                ReplyEngine.moodSelfTest(this@MainActivity)
            } catch (e: Exception) {
                "✗ মুড পরীক্ষা ব্যর্থ: ${e.message}"
            }
            LogStore.add(this@MainActivity, result)
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
        const val EXTRA_CLOUDTEST = "cloudtest"
        const val EXTRA_NAVTEST = "navtest"
        const val EXTRA_MOODTEST = "moodtest"

        /** Bottom-navigation menu id for each section, indexed by section. */
        val SECTION_MENU_ID = intArrayOf(
            R.id.nav_home,
            R.id.nav_setup,
            R.id.nav_people,
            R.id.nav_activity
        )
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
            b.bottomNav.updatePadding(bottom = bars.bottom)
            insets
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // -------------------------------------------------------------- navigation

    /**
     * Guards the bottom-nav / pager feedback loop while we set one from the other.
     *
     * A plain Boolean is not enough here. The chain is
     * `goToSection -> setSelectedItemId -> listener -> goToSection`, and the inner
     * call runs `renderSectionChrome`, which clears the flag on its way out. That
     * clears the *outer* call's flag too, so the guard is already down by the time
     * the outer call sets `setCurrentItem` — and the recursion runs unchecked until
     * the stack overflows. Five real crashes on 28 Sep, all with this frame:
     *
     *     goToSection -> setupNavigation$lambda$3 -> onNavigationItemSelected -> goToSection ...
     *
     * A depth counter is reentrancy-safe: the inner call increments and decrements
     * symmetrically, so the flag is still up when control returns to the outer call.
     */
    private var syncingDepth = 0
    private val syncing: Boolean get() = syncingDepth > 0

    private inline fun guarded(block: () -> Unit) {
        syncingDepth++
        try {
            block()
        } finally {
            syncingDepth--
        }
    }

    private fun setupNavigation() {
        adapter = TabsAdapter(this)
        b.pager.adapter = adapter
        b.pager.offscreenPageLimit = 1

        b.bottomNav.setOnItemSelectedListener { item ->
            // `setSelectedItemId` fires this listener synchronously, so without the
            // guard goToSection -> select -> listener -> goToSection recurses until
            // the stack overflows.
            if (syncing) {
                return@setOnItemSelectedListener true
            }
            goToSection(menuIdToSection(item.itemId))
            true
        }

        // Sub-tabs are rebuilt per section, so they are wired by hand rather than
        // with a mediator that would insist on showing all seven.
        b.tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                if (syncing) return
                val section = TabsAdapter.sectionOf(b.pager.currentItem)
                val target = TabsAdapter.SECTION_FIRST_PAGE[section] + tab.position
                if (target != b.pager.currentItem) b.pager.setCurrentItem(target, true)
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })

        b.pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                if (!syncing) {
                    val section = TabsAdapter.sectionOf(position)
                    renderSectionChrome(section, position)

                    val menuId = SECTION_MENU_ID[section]
                    if (b.bottomNav.selectedItemId != menuId) {
                        guarded { b.bottomNav.selectedItemId = menuId }
                    }
                }
                fragmentAt(position)?.onShown()
            }
        })

        goToSection(TabsAdapter.SECTION_HOME)
    }

    /** Jump to a section, optionally to one specific page inside it. */
    fun goToSection(section: Int, page: Int = -1) {
        val first = TabsAdapter.SECTION_FIRST_PAGE[section]
        val target = if (page < 0) first else page

        // Selecting a bottom-nav item invokes its listener synchronously, so the
        // flag must be held across the call, not around the whole method.
        if (b.bottomNav.selectedItemId != SECTION_MENU_ID[section]) {
            guarded { b.bottomNav.selectedItemId = SECTION_MENU_ID[section] }
        }

        // setCurrentItem fires onPageSelected synchronously, which also drives the
        // bottom nav — hold the guard across it for the same reason as above.
        if (b.pager.currentItem != target) {
            guarded { b.pager.setCurrentItem(target, false) }
        }
        renderSectionChrome(section, target)
    }

    /** Show the sub-tab row only when the section actually holds several pages. */
    private fun renderSectionChrome(section: Int, page: Int) {
        val first = TabsAdapter.SECTION_FIRST_PAGE[section]
        val count = TabsAdapter.SECTION_PAGES[section]

        guarded {
            b.tabs.removeAllTabs()
            if (count > 1) {
                for (i in 0 until count) {
                    b.tabs.addTab(b.tabs.newTab().setText(TabsAdapter.TITLES[first + i]))
                }
                b.tabs.visibility = View.VISIBLE
                b.tabDivider.visibility = View.VISIBLE
                val index = (page - first).coerceIn(0, count - 1)
                b.tabs.getTabAt(index)?.select()
            } else {
                b.tabs.visibility = View.GONE
                b.tabDivider.visibility = View.GONE
            }
        }

        // Home is read-only, so a Save button there would be a lie.
        b.saveBar.visibility = if (section == TabsAdapter.SECTION_HOME) View.GONE else View.VISIBLE
    }

    private fun menuIdToSection(menuId: Int): Int = when (menuId) {
        R.id.nav_setup -> TabsAdapter.SECTION_SETUP
        R.id.nav_people -> TabsAdapter.SECTION_PEOPLE
        R.id.nav_activity -> TabsAdapter.SECTION_ACTIVITY
        else -> TabsAdapter.SECTION_HOME
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
            (fragmentAt(TabsAdapter.TAB_HOME) as? HomeFragment)?.onShown()
        }

        b.switchMood.setOnCheckedChangeListener { _, checked ->
            p.moodEnabled = checked
            val msg = if (checked) "মুড চালু — ${p.assistantName} (${p.moodText})" else "মুড বন্ধ"
            LogStore.add(this, msg)
            refreshHero()
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        // Tapping the mood pill opens a quick editor right in the hero — no need to
        // navigate to মানুষ → কন্টেক্সট just to change "এখন কী করছি".
        b.moodPill.setOnClickListener {
            if (b.moodEditor.visibility == View.VISIBLE) {
                saveMoodEditor()
                b.moodEditor.visibility = View.GONE
            } else {
                b.etQuickMood.setText(p.moodText)
                b.etQuickAssistant.setText(p.assistantName)
                b.moodEditor.visibility = View.VISIBLE
                b.etQuickMood.requestFocus()
                b.etQuickMood.setSelection(b.etQuickMood.text?.length ?: 0)
            }
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
        b.tvHeroModel.text = p.model.ifBlank { getString(R.string.hero_subtitle) }
        b.tvMoodLabel.text = if (moodOn) p.moodText else getString(R.string.mood_sleeping)
    }

    // ------------------------------------------------------------ mood editor

    /**
     * The quick mood editor in the hero: typing here saves on focus loss or when
     * the pill is tapped to collapse. No Save button, no navigation.
     */
    private fun bindMoodEditor() {
        // Save when focus leaves either field — the user is done typing.
        val saveOnFocusLoss = View.OnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) saveMoodEditor()
        }
        b.etQuickMood.onFocusChangeListener = saveOnFocusLoss
        b.etQuickAssistant.onFocusChangeListener = saveOnFocusLoss
    }

    private fun saveMoodEditor() {
        val mood = b.etQuickMood.text?.toString()?.trim().orEmpty().ifBlank { "ঘুমাচ্ছে" }
        val name = b.etQuickAssistant.text?.toString()?.trim().orEmpty().ifBlank { "ক্ল" }
        if (mood != p.moodText || name != p.assistantName) {
            p.moodText = mood
            p.assistantName = name
            LogStore.add(this, "মুড বদলানো হলো: $name · $mood")
            refreshHero()
            // The Context tab also shows these fields — refresh it.
            (fragmentAt(TabsAdapter.TAB_CONTEXT) as? ContextFragment)?.reloadIfReady()
        }
    }
}
