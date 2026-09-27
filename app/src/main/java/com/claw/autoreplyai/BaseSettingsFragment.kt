package com.claw.autoreplyai

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.fragment.app.Fragment

/**
 * Every settings tab reads from and writes to the same [Prefs] store.
 * The host activity drives [save] so a single Save button covers all tabs.
 */
abstract class BaseSettingsFragment : Fragment() {

    protected val prefs: Prefs get() = Prefs.get(requireContext())

    /** True while the view hierarchy exists, i.e. [save] is safe to call. */
    protected var viewReady: Boolean = false
        private set

    /** Wire click listeners here — the views exist but hold no data yet. */
    open fun onViewsReady() {}

    /** Copy [prefs] into the views. */
    abstract fun load()

    /** Copy the view values back into [prefs]. */
    abstract fun save()

    /** Called each time this tab becomes the visible one. */
    open fun onShown() {}

    /**
     * Re-read [prefs] into the views. Used after a backup restore, where
     * recreating the activity would make the outgoing fragments save their stale
     * values straight back over the data we just restored.
     */
    fun reloadIfReady() {
        if (viewReady) load()
    }

    /** Release the view binding here — the views are already saved and gone. */
    open fun onViewsGone() {}

    final override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewReady = true
        // Populate the views BEFORE wiring listeners, otherwise restoring a saved
        // value fires the listener and writes it straight back to prefs.
        load()
        onViewsReady()
    }

    final override fun onDestroyView() {
        // Persist whatever the user typed before the pager recycles this tab.
        if (viewReady) save()
        viewReady = false
        onViewsGone()
        super.onDestroyView()
    }

    protected fun toast(message: String) =
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
}
