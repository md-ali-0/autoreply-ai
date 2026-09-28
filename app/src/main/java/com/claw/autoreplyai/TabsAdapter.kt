package com.claw.autoreplyai

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter

/**
 * Every screen in the app, in one pager.
 *
 * The pages are grouped into four *sections* that the bottom navigation switches
 * between. A section with more than one page shows a secondary tab row; a section
 * with a single page hides it. That keeps the six original destinations exactly as
 * they were — nothing moved, nothing was merged — while replacing six cramped tabs
 * in one row with four legible ones plus a home screen.
 */
class TabsAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {

    override fun getItemCount(): Int = COUNT

    override fun createFragment(position: Int): Fragment = when (position) {
        TAB_HOME -> HomeFragment()
        TAB_PERMISSIONS -> PermissionsFragment()
        TAB_AI -> AiFragment()
        TAB_CONTACTS -> ContactsFragment()
        TAB_CONTEXT -> ContextFragment()
        TAB_TIMING -> TimingFragment()
        else -> LogsFragment()
    }

    override fun getItemId(position: Int): Long = position.toLong()

    override fun containsItem(itemId: Long): Boolean = itemId in 0L until COUNT.toLong()

    companion object {
        // ---- pages ----
        const val TAB_HOME = 0
        const val TAB_PERMISSIONS = 1
        const val TAB_AI = 2
        const val TAB_CONTACTS = 3
        const val TAB_CONTEXT = 4
        const val TAB_TIMING = 5
        const val TAB_LOGS = 6
        const val COUNT = 7

        // ---- sections (one per bottom-navigation item) ----
        const val SECTION_HOME = 0
        const val SECTION_SETUP = 1
        const val SECTION_PEOPLE = 2
        const val SECTION_ACTIVITY = 3
        const val SECTION_COUNT = 4

        /** First page of each section, indexed by section. */
        val SECTION_FIRST_PAGE = intArrayOf(TAB_HOME, TAB_PERMISSIONS, TAB_CONTACTS, TAB_TIMING)

        /** How many pages each section holds. */
        val SECTION_PAGES = intArrayOf(1, 2, 2, 2)

        /** Which section a page belongs to. */
        fun sectionOf(page: Int): Int {
            for (section in SECTION_COUNT - 1 downTo 0) {
                if (page >= SECTION_FIRST_PAGE[section]) return section
            }
            return SECTION_HOME
        }

        val TITLES = intArrayOf(
            R.string.nav_home,
            R.string.tab_permissions,
            R.string.tab_ai,
            R.string.tab_contacts,
            R.string.tab_context,
            R.string.tab_timing,
            R.string.tab_logs
        )
    }
}
