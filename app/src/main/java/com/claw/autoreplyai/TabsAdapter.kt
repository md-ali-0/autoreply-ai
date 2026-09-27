package com.claw.autoreplyai

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter

class TabsAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {

    override fun getItemCount(): Int = COUNT

    override fun createFragment(position: Int): Fragment = when (position) {
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
        const val TAB_PERMISSIONS = 0
        const val TAB_AI = 1
        const val TAB_CONTACTS = 2
        const val TAB_CONTEXT = 3
        const val TAB_TIMING = 4
        const val TAB_LOGS = 5
        const val COUNT = 6

        val TITLES = intArrayOf(
            R.string.tab_permissions,
            R.string.tab_ai,
            R.string.tab_contacts,
            R.string.tab_context,
            R.string.tab_timing,
            R.string.tab_logs
        )
    }
}
