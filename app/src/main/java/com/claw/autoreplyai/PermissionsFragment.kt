package com.claw.autoreplyai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.claw.autoreplyai.databinding.FragmentPermissionsBinding

class PermissionsFragment : BaseSettingsFragment() {

    private var _b: FragmentPermissionsBinding? = null
    private val b get() = _b!!

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh() }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _b = FragmentPermissionsBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewsReady() {
        b.rowNotif.setOnClickListener { openNotificationSettings() }
        b.rowAcc.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (e: Exception) {
                toast("সেটিংস খোলা যায়নি")
            }
        }
        b.rowContacts.setOnClickListener { askContacts() }
        b.rowBattery.setOnClickListener { requestBatteryExemption() }
        b.rowOverlay.setOnClickListener { requestOverlay() }
        askRuntimePermissions()
    }

    override fun load() = refresh()

    override fun save() {
        // nothing to persist — permissions live in the system
    }

    override fun onShown() = refresh()

    override fun onViewsGone() {
        _b = null
    }

    // ---------------------------------------------------------------- status

    fun refresh() {
        if (!viewReady) return
        val notif = isNotificationAccessEnabled()
        val acc = isAccessibilityEnabled()
        val contacts = ContactResolver.hasPermission(requireContext())
        val battery = isIgnoringBattery()
        val overlay = Settings.canDrawOverlays(requireContext())

        setState(b.tvNotifState, b.dotNotif, notif)
        setState(b.tvAccState, b.dotAcc, acc)
        setState(b.tvContactsState, b.dotContacts, contacts)
        setState(b.tvBatteryState, b.dotBattery, battery)
        setState(b.tvOverlayState, b.dotOverlay, overlay)

        val done = listOf(notif, acc, contacts, battery, overlay).count { it }
        b.tvPermSummary.text = if (done == 5) {
            getString(R.string.perm_all_done)
        } else {
            getString(R.string.perm_summary, done, 5)
        }
    }

    private fun setState(tv: TextView, dot: View, ok: Boolean) {
        val color = ContextCompat.getColor(requireContext(), if (ok) R.color.ok else R.color.bad)
        tv.text = getString(if (ok) R.string.granted else R.string.not_granted)
        tv.setTextColor(color)
        dot.backgroundTintList = ColorStateList.valueOf(color)
    }

    private fun isNotificationAccessEnabled(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(requireContext())
            .contains(requireContext().packageName)

    private fun isAccessibilityEnabled(): Boolean {
        val pkg = requireContext().packageName
        val flat = Settings.Secure.getString(
            requireContext().contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return flat.split(':').any { it.startsWith(pkg) && it.contains("SendAccessibilityService") }
    }

    private fun isIgnoringBattery(): Boolean {
        val pm = requireContext().getSystemService(PowerManager::class.java)
        return pm.isIgnoringBatteryOptimizations(requireContext().packageName)
    }

    // --------------------------------------------------------------- actions

    private fun openNotificationSettings() {
        try {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun requestBatteryExemption() {
        val pkg = requireContext().packageName
        try {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$pkg")
                )
            )
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e2: Exception) {
                toast("সেটিংস খোলা যায়নি")
            }
        }
    }

    private fun requestOverlay() {
        val pkg = requireContext().packageName
        try {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$pkg"))
            )
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
            } catch (e2: Exception) {
                toast("সেটিংস খোলা যায়নি")
            }
        }
    }

    private fun askRuntimePermissions() {
        val ctx = requireContext()
        val wanted = ArrayList<String>()
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) wanted.add(Manifest.permission.READ_CONTACTS)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) wanted.add(Manifest.permission.POST_NOTIFICATIONS)

        if (wanted.isNotEmpty()) permLauncher.launch(wanted.toTypedArray())
    }

    private fun askContacts() {
        if (ContactResolver.hasPermission(requireContext())) {
            toast(getString(R.string.granted))
            return
        }
        permLauncher.launch(arrayOf(Manifest.permission.READ_CONTACTS))
    }
}
