package com.answer.launcher.ui.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.answer.launcher.R
import com.answer.launcher.core.bridge.GameBridgeClient
import com.answer.launcher.databinding.FragmentAboutBinding
import com.answer.launcher.utils.CrashHandler

/**
 * About page. Under the crash test button it mirrors what the `:game` process
 * reports over the AIDL bridge: runtime state, hook accounting and the plugin
 * lists.
 *
 * Every value here is read from the other process - the UI process never
 * assembles the Minecraft runtime, and merely opening this page does not start
 * `:game` either (GameBridgeClient.bind() parks a read-only binding; only the
 * launch paths call wake()). So an empty page with "未运行" is the honest answer
 * when the game has not been started yet.
 */
class AboutFragment : Fragment() {

    lateinit var binding: FragmentAboutBinding

    private val bridge = GameBridgeClient.get()

    private val listener = object : GameBridgeClient.Listener {
        override fun onBridgeChanged() {
            // Binder thread, and the page may already be gone: hop back through the
            // view's own handler instead of FragmentActivity#runOnUiThread, which the
            // 1.9 Kotlin compiler here cannot type-check against androidx.core 1.16.
            if (!isAdded) return
            try {
                binding.runtimeStatus.post { if (isAdded) refresh() }
            } catch (ignored: Exception) {
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?): View? {
        binding = FragmentAboutBinding.inflate(inflater, container, false)

        binding.test.setOnClickListener {
            CrashHandler.crash()
        }

        // Ask :game to re-read the shared plugin directories and load whatever
        // appeared there; onPluginsChanged() calls back and refresh() runs again.
        binding.reloadPlugins.setOnClickListener {
            bridge.reloadPlugins()
            Toast.makeText(requireContext(), R.string.about_rescan_requested, Toast.LENGTH_SHORT).show()
            refresh()
        }

        return binding.root
    }

    override fun onResume() {
        super.onResume()
        bridge.addListener(listener)
        refresh()
    }

    override fun onPause() {
        bridge.removeListener(listener)
        super.onPause()
    }

    private fun refresh() {
        if (!::binding.isInitialized) return

        val text = StringBuilder()
        val pid = bridge.gamePid()
        text.append(getString(R.string.about_label_game_process)).append(if (pid > 0) "pid $pid" else getString(R.string.about_not_running)).append('\n')
        text.append(getString(R.string.about_label_runtime_state)).append(bridge.runtimeState()).append('\n')
        text.append(getString(R.string.about_label_engine_hooks)).append(bridge.hookStats()).append('\n')
        text.append(getString(R.string.about_label_menu_state)).append(bridge.menuState()).append('\n')
        text.append(getString(R.string.about_label_game_version)).append(bridge.minecraftVersion()).append('\n')
        // M4 license obligation (docs/levi-compat-design.md 4.2): the menu-state
        // hooks derive from LiteLDev/preloader-android signature rules, treated as
        // Apache-2.0; attribution ships with the app, full text in NOTICE.
        text.append(getString(R.string.about_label_third_party)).append("preloader-android / LeviLaunchroid (LiteLDev, Apache-2.0) · MinecraftAuth (RaphiMC, LGPL-3.0) — ").append(getString(R.string.about_see_notice))

        appendFiles(text, getString(R.string.about_label_dex_plugins), bridge.pluginFiles())
        appendFiles(text, getString(R.string.about_label_native_plugins), bridge.nativePluginFiles())

        val infos = bridge.pluginInfos()
        text.append(getString(R.string.about_loaded_plugin_count, infos.size)).append('\n')
        if (infos.isEmpty()) {
            text.append(getString(R.string.about_none_loaded)).append('\n')
        } else {
            for (info in infos) {
                // name|author|version|description - the shape PluginInfo.info() reports
                val parts = info.split('|')
                val name = parts.getOrElse(0) { "?" }
                val author = parts.getOrElse(1) { "-" }
                val version = parts.getOrElse(2) { "-" }
                val description = parts.getOrElse(3) { "" }
                text.append("  · ").append(name)
                    .append(" v").append(version)
                    .append("  ").append(author)
                if (description.isNotEmpty()) text.append("  —  ").append(description)
                text.append('\n')
            }
        }

        binding.runtimeStatus.text = text.toString()
    }

    private fun appendFiles(text: StringBuilder, title: String, items: List<String>) {
        text.append('\n').append(title).append(' ').append(items.size).append('\n')
        if (items.isEmpty()) {
            text.append(getString(R.string.about_none_loaded)).append('\n')
            return
        }
        for (item in items) text.append("  · ").append(item).append('\n')
    }
}
