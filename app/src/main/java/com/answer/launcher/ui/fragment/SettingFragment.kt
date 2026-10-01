package com.answer.launcher.ui.fragment


import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.answer.launcher.R
import com.answer.launcher.core.LauncherConstants
import com.answer.launcher.core.bridge.GameBridgeClient
import com.answer.launcher.core.bridge.GameRuntime
import com.answer.launcher.core.manager.IdentityStore
import com.answer.launcher.core.manager.IOManager
import com.answer.launcher.ui.LauncherPrefs
import com.answer.launcher.databinding.FragmentSettingBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

class SettingFragment : Fragment() {

    lateinit var binding : FragmentSettingBinding;

    private val bridgeListener = GameBridgeClient.Listener { refresh() }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        binding = FragmentSettingBinding.inflate(inflater, container, false)

        binding.rescanPlugins.setOnClickListener {
            GameBridgeClient.get().reloadPlugins()
            Toast.makeText(requireContext(), R.string.setting_rescan_notified, Toast.LENGTH_SHORT).show()
        }
        bindRow(binding.navAccount, R.id.nav_account)
        bindRow(binding.navLogin, R.id.nav_login)
        bindRow(binding.navAppearance, R.id.nav_appearance)
        bindRow(binding.navData, R.id.nav_data)
        selectRow(binding.navAccount)
        return binding.root;
    }

    private fun bindRow(row: android.view.View, id: Int) {
        row.setOnClickListener { selectRow(row) }
        row.tag = id
    }

    /** PCL-style single-pane switch: highlight the active row, show only its cards. */
    private fun selectRow(row: android.view.View) {
        sidebarRows().forEach { it.isActivated = (it === row) }
        showGroup(row.tag as Int)
    }

    private fun sidebarRows(): List<android.view.View> =
        listOf(binding.navAccount, binding.navLogin, binding.navAppearance, binding.navData)

    private fun showGroup(id: Int) {
        binding.groupAccount.visibility = if (id == R.id.nav_account) View.VISIBLE else View.GONE
        binding.groupLogin.visibility = if (id == R.id.nav_login) View.VISIBLE else View.GONE
        binding.groupAppearance.visibility = if (id == R.id.nav_appearance) View.VISIBLE else View.GONE
        binding.groupData.visibility = if (id == R.id.nav_data) View.VISIBLE else View.GONE
    }

    override fun onResume() {
        super.onResume()
        GameBridgeClient.get().addListener(bridgeListener)
        refresh()
    }

    override fun onPause() {
        GameBridgeClient.get().removeListener(bridgeListener)
        super.onPause()
    }

    private fun refresh() {
        if (!::binding.isInitialized) {
            return
        }
        val host: Context = requireContext().applicationContext
        bindIdentity(IdentityStore.readAccount(host))
        bindLoginMethod(host)
        bindAppearance(host)
        bindVersionIsolation(host)

        val version = try {
            host.packageManager.getPackageInfo(LauncherConstants.MINECRAFTPACKAGE, 0).versionName
        } catch (ignored: Throwable) {
            null
        }
        binding.versionLine.text = getString(R.string.setting_version_line, version ?: getString(R.string.setting_version_not_installed))
        val active = IdentityStore.activeSlug(host)
        binding.instancePath.text = if (active == null) {
            getString(R.string.setting_data_dir_no_launch)
        } else {
            getString(R.string.setting_data_dir_path, IdentityStore.instanceRoot(host, active))
        }

        val state = GameBridgeClient.get().runtimeState()
        binding.runtimeLine.text = when {
            state.startsWith(GameRuntime.FAILED_PREFIX) -> getString(R.string.setting_runtime_failed, state.substring(GameRuntime.FAILED_PREFIX.length))
            state == GameRuntime.READY -> getString(R.string.setting_runtime_running, GameBridgeClient.get().gamePid())
            state == GameRuntime.ASSEMBLING -> getString(R.string.setting_runtime_assembling)
            else -> getString(R.string.setting_runtime_not_started)
        }
        binding.hookLine.text = GameBridgeClient.get().hookStats()
    }

    private fun bindIdentity(account: IdentityStore.Account) {
        // The listener is detached while the box is being synced from the store:
        // assigning isChecked fires it, and a render must not write the mode back.
        val listener = android.widget.CompoundButton.OnCheckedChangeListener { _, checked ->
            val host = requireContext().applicationContext
            IdentityStore.setMode(host, if (checked) IdentityStore.MODE_SHARED else IdentityStore.MODE_ISOLATED)
            bindIdentity(IdentityStore.readAccount(host))
            Toast.makeText(host, R.string.setting_identity_change_effect, Toast.LENGTH_SHORT).show()
        }
        binding.identityShare.setOnCheckedChangeListener(null)
        binding.identityShare.isChecked = account.shared
        binding.identityShare.setOnCheckedChangeListener(listener)
        binding.identitySummary.setText(
            if (account.shared) R.string.identity_share_summary else R.string.identity_isolated_summary
        )
        binding.accountLine.text = when {
            account.signedIn -> getString(R.string.account_setting_line_signed_in,
                    account.xuid ?: getString(R.string.account_unknown_xuid),
                    account.maskedAccount() ?: getString(R.string.account_unknown_xuid))
            else -> getString(R.string.account_setting_line_not_signed_in)
        }
        binding.identityPath.text = account.root.absolutePath
    }

    private fun bindLoginMethod(host: Context) {
        // Detach while syncing from the preference, exactly like the identity switch: rendering
        // the stored value must not immediately write it back.
        val listener = android.widget.CompoundButton.OnCheckedChangeListener { _, checked ->
            LauncherPrefs.setLoginInWebView(host, checked)
            Toast.makeText(
                host,
                if (checked) R.string.setting_webview_on_toast else R.string.setting_webview_off_toast,
                Toast.LENGTH_SHORT
            ).show()
        }
        binding.loginWebview.setOnCheckedChangeListener(null)
        binding.loginWebview.isChecked = LauncherPrefs.loginInWebView(host)
        binding.loginWebview.setOnCheckedChangeListener(listener)
    }

    private fun bindAppearance(host: Context) {
        binding.autoValidate.setOnCheckedChangeListener(null)
        binding.autoValidate.isChecked = LauncherPrefs.autoValidateLogin(host)
        binding.autoValidate.setOnCheckedChangeListener { _, checked ->
            LauncherPrefs.setAutoValidateLogin(host, checked)
        }

        binding.clearCache.setOnClickListener { confirmClearCache(host) }
    }

    private fun bindVersionIsolation(host: Context) {
        binding.versionIsolation.setOnCheckedChangeListener(null)
        binding.versionIsolation.isChecked = IOManager.versionIsolated(host)
        binding.versionIsolation.setOnCheckedChangeListener { _, checked ->
            IOManager.setVersionIsolated(host, checked)
            Toast.makeText(host, R.string.setting_isolation_effect, Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmClearCache(host: Context) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.setting_clear_cache_title)
            .setMessage(R.string.setting_clear_cache_message)
            .setNegativeButton(R.string.common_cancel, null)
            .setPositiveButton(R.string.setting_clear_cache_confirm) { _, _ ->
                Thread {
                    var freed = 0L
                    for (dir in listOfNotNull(host.cacheDir, host.externalCacheDir)) {
                        dir.listFiles()?.forEach { file ->
                            freed += sizeOf(file)
                            deleteAll(file)
                        }
                    }
                    val mb = freed / 1024.0 / 1024.0
                    activity?.runOnUiThread {
                        Toast.makeText(host, getString(R.string.setting_cache_freed, mb), Toast.LENGTH_SHORT).show()
                    }
                }.apply { name = "clear-cache" }.start()
            }
            .show()
    }

    private fun sizeOf(file: File): Long {
        if (file.isFile) return file.length()
        var total = 0L
        file.listFiles()?.forEach { total += sizeOf(it) }
        return total
    }

    private fun deleteAll(file: File) {
        if (file.isDirectory) file.listFiles()?.forEach { deleteAll(it) }
        file.delete()
    }

    override fun onDestroy() {
        super.onDestroy()

    }
}
