package com.answer.launcher.ui.fragment

import android.content.pm.PackageManager
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
 * About page, PCL-style: a compact left sidebar swaps between four cards —
 * 关于 (identity), 运行时 (live runtime state), 插件 (loaded plugins) and
 * 鸣谢 (third-party attribution).
 *
 * The runtime and plugin values are read from the `:game` process over the AIDL
 * bridge, never from this process: the UI process only parks a read-only binding, so
 * opening this page cannot cold-boot the runtime. An empty "未运行" answer is honest
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

        bindRow(binding.navAbout, R.id.group_about)
        bindRow(binding.navRuntime, R.id.group_runtime)
        bindRow(binding.navPlugins, R.id.group_plugins)
        bindRow(binding.navCredits, R.id.group_credits)
        selectRow(binding.navAbout)
        binding.appVersion.text = versionLine()

        return binding.root
    }

    private fun bindRow(row: View, groupId: Int) {
        row.setOnClickListener { selectRow(row) }
        row.tag = groupId
    }

    private fun selectRow(row: View) {
        rows().forEach { it.isActivated = (it === row) }
        val show = row.tag as Int
        binding.groupAbout.visibility = if (show == R.id.group_about) View.VISIBLE else View.GONE
        binding.groupRuntime.visibility = if (show == R.id.group_runtime) View.VISIBLE else View.GONE
        binding.groupPlugins.visibility = if (show == R.id.group_plugins) View.VISIBLE else View.GONE
        binding.groupCredits.visibility = if (show == R.id.group_credits) View.VISIBLE else View.GONE
    }

    private fun rows(): List<View> =
        listOf(binding.navAbout, binding.navRuntime, binding.navPlugins, binding.navCredits)

    private fun versionLine(): String {
        return try {
            val info = requireContext().packageManager.getPackageInfo(requireContext().packageName, 0)
            getString(R.string.about_version_line, info.versionName ?: "?", info.longVersionCode)
        } catch (ignored: PackageManager.NameNotFoundException) {
            getString(R.string.about_version_unknown)
        }
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

        val runtime = StringBuilder()
        val pid = bridge.gamePid()
        runtime.append(getString(R.string.about_label_game_process)).append(if (pid > 0) "pid $pid" else getString(R.string.about_not_running)).append('\n')
        runtime.append(getString(R.string.about_label_runtime_state)).append(bridge.runtimeState()).append('\n')
        runtime.append(getString(R.string.about_label_engine_hooks)).append(bridge.hookStats()).append('\n')
        runtime.append(getString(R.string.about_label_menu_state)).append(bridge.menuState()).append('\n')
        runtime.append(getString(R.string.about_label_game_version)).append(bridge.minecraftVersion())
        binding.runtimeStatus.text = runtime.toString()

        val plugins = StringBuilder()
        appendFiles(plugins, getString(R.string.about_label_dex_plugins), bridge.pluginFiles())
        appendFiles(plugins, getString(R.string.about_label_native_plugins), bridge.nativePluginFiles())
        val infos = bridge.pluginInfos()
        plugins.append(getString(R.string.about_loaded_plugin_count, infos.size)).append('\n')
        if (infos.isEmpty()) {
            plugins.append(getString(R.string.about_none_loaded)).append('\n')
        } else {
            for (info in infos) {
                // name|author|version|description - the shape PluginInfo.info() reports
                val parts = info.split('|')
                val name = parts.getOrElse(0) { "?" }
                val author = parts.getOrElse(1) { "-" }
                val version = parts.getOrElse(2) { "-" }
                val description = parts.getOrElse(3) { "" }
                plugins.append("  · ").append(name)
                    .append(" v").append(version)
                    .append("  ").append(author)
                if (description.isNotEmpty()) plugins.append("  —  ").append(description)
                plugins.append('\n')
            }
        }
        binding.pluginStatus.text = plugins.toString().trim()
    }

    private fun appendFiles(text: StringBuilder, title: String, items: List<String>) {
        text.append(title).append(' ').append(items.size).append('\n')
        if (items.isEmpty()) {
            text.append(getString(R.string.about_none_loaded)).append('\n')
            return
        }
        for (item in items) text.append("  · ").append(item).append('\n')
        text.append('\n')
    }
}
