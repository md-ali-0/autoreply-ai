package com.claw.autoreplyai

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.lifecycle.lifecycleScope
import com.claw.autoreplyai.databinding.FragmentAiBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * AI settings: provider profiles (name + endpoint + key + model), persona,
 * triage switches, and an inline API test.
 */
class AiFragment : BaseSettingsFragment() {

    private var _b: FragmentAiBinding? = null
    private val b get() = _b!!

    private var providers = mutableListOf<AiProvider>()
    private var selectedIndex = 0
    private var ignoreSelection = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _b = FragmentAiBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewsReady() {
        b.btnTestAi.setOnClickListener { testAi() }
        b.switchTriage.setOnCheckedChangeListener { _, v -> prefs.smartTriage = v }
        b.switchHold.setOnCheckedChangeListener { _, v -> prefs.holdOnEmotional = v }
        b.switchFailAlert.setOnCheckedChangeListener { _, v -> prefs.failAlert = v }

        b.btnAddProvider.setOnClickListener { addProvider() }
        b.btnDeleteProvider.setOnClickListener { deleteProvider() }

        b.spinnerProvider.setOnItemClickListener { _, _, position, _ ->
            if (ignoreSelection) return@setOnItemClickListener
            selectedIndex = position.coerceIn(0, providers.lastIndex)
            loadProviderFields(selectedIndex)
        }
    }

    override fun load() {
        // Migrate legacy single-provider config into the new list on first run.
        AiProviderStore.migrateIfNeeded(requireContext(), prefs)

        providers = AiProviderStore.all(requireContext()).toMutableList()
        selectedIndex = AiProviderStore.selectedIndex(requireContext()).coerceIn(0, providers.lastIndex)

        b.switchTriage.isChecked = prefs.smartTriage
        b.switchHold.isChecked = prefs.holdOnEmotional
        b.switchFailAlert.isChecked = prefs.failAlert
        b.etPersona.setText(prefs.persona)

        refreshProviderSpinner()
        loadProviderFields(selectedIndex)
    }

    override fun save() {
        if (!viewReady) return

        // Update the currently selected provider in the list.
        if (providers.isNotEmpty() && selectedIndex in providers.indices) {
            providers[selectedIndex] = AiProvider(
                name = b.etProviderName.text?.toString().orEmpty().ifBlank { "Provider ${selectedIndex + 1}" },
                baseUrl = b.etBase.text?.toString().orEmpty(),
                apiKey = b.etKey.text?.toString().orEmpty(),
                model = b.etModel.text?.toString().orEmpty()
            )
            AiProviderStore.save(requireContext(), providers)
            AiProviderStore.setSelected(requireContext(), selectedIndex)
        }

        // Also copy the selected provider into legacy prefs so ReplyEngine keeps working
        // without any changes.
        if (providers.isNotEmpty() && selectedIndex in providers.indices) {
            val p = providers[selectedIndex]
            prefs.baseUrl = p.baseUrl
            prefs.apiKey = p.apiKey
            prefs.model = p.model
        }

        prefs.persona = b.etPersona.text?.toString().orEmpty().ifBlank { Prefs.DEFAULT_PERSONA }
    }

    private fun refreshProviderSpinner() {
        val names = providers.map { it.name.ifBlank { "Provider" } }
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, names)
        ignoreSelection = true
        b.spinnerProvider.setAdapter(adapter)
        if (selectedIndex in names.indices) {
            b.spinnerProvider.setText(names[selectedIndex], false)
        }
        ignoreSelection = false
        b.btnDeleteProvider.isEnabled = providers.size > 1
    }

    private fun loadProviderFields(index: Int) {
        if (index !in providers.indices) return
        val p = providers[index]
        b.etProviderName.setText(p.name)
        b.etBase.setText(p.baseUrl)
        b.etKey.setText(p.apiKey)
        b.etModel.setText(p.model)
    }

    private fun addProvider() {
        save()
        val newName = "Provider ${providers.size + 1}"
        val newP = AiProvider(
            name = newName,
            baseUrl = b.etBase.text?.toString().orEmpty(),
            apiKey = b.etKey.text?.toString().orEmpty(),
            model = b.etModel.text?.toString().orEmpty()
        )
        providers.add(newP)
        AiProviderStore.save(requireContext(), providers)
        selectedIndex = providers.lastIndex
        AiProviderStore.setSelected(requireContext(), selectedIndex)
        refreshProviderSpinner()
        loadProviderFields(selectedIndex)
        toast("$newName যোগ করা হলো")
    }

    private fun deleteProvider() {
        if (providers.size <= 1) {
            toast("শেষ প্রোভাইডার মুছা যাবে না")
            return
        }
        providers.removeAt(selectedIndex)
        AiProviderStore.save(requireContext(), providers)
        selectedIndex = selectedIndex.coerceAtMost(providers.lastIndex)
        AiProviderStore.setSelected(requireContext(), selectedIndex)
        refreshProviderSpinner()
        loadProviderFields(selectedIndex)
        toast("মুছে দেওয়া হলো")
    }

    private fun testAi() {
        save()
        val p = if (providers.isNotEmpty() && selectedIndex in providers.indices)
            providers[selectedIndex] else null
        val base = p?.baseUrl ?: prefs.baseUrl
        val key = p?.apiKey ?: prefs.apiKey
        val model = p?.model ?: prefs.model

        toast("টেস্ট চলছে…")
        lifecycleScope.launch {
            var err: String? = null
            val ok = try {
                withContext(Dispatchers.IO) {
                    AiClient.chat(
                        base, key, model,
                        listOf(
                            AiClient.Msg("system", prefs.persona),
                            AiClient.Msg("user", "এক লাইনে বলো তুমি ঠিক আছো।")
                        )
                    ).isNotBlank()
                }
            } catch (e: Exception) {
                err = e.message ?: "অজানা ত্রুটি"
                false
            }
            if (isAdded) {
                if (ok) toast(getString(R.string.test_ok))
                else toast(getString(R.string.test_fail, err ?: "?"))
            }
        }
    }

    override fun onViewsGone() {
        _b = null
    }
}
