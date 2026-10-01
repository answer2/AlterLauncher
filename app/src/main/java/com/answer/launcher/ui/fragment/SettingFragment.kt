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
            Toast.makeText(requireContext(), "已通知游戏进程重新扫描", Toast.LENGTH_SHORT).show()
        }
        return binding.root;
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

        val version = try {
            host.packageManager.getPackageInfo(LauncherConstants.MINECRAFTPACKAGE, 0).versionName
        } catch (ignored: Throwable) {
            null
        }
        binding.versionLine.text = "版本：" + (version ?: "未安装")
        val active = IdentityStore.activeSlug(host)
        binding.instancePath.text = if (active == null) {
            "本次运行的数据目录：尚未启动过任何版本"
        } else {
            "本次运行的数据目录：" + IdentityStore.instanceRoot(host, active)
        }

        val state = GameBridgeClient.get().runtimeState()
        binding.runtimeLine.text = when {
            state.startsWith(GameRuntime.FAILED_PREFIX) -> "运行时装配失败：" + state.substring(GameRuntime.FAILED_PREFIX.length)
            state == GameRuntime.READY -> "运行中 · 进程 " + GameBridgeClient.get().gamePid()
            state == GameRuntime.ASSEMBLING -> "正在装配运行时…"
            else -> "运行时未启动"
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
            Toast.makeText(host, "下次启动游戏生效", Toast.LENGTH_SHORT).show()
        }
        binding.identityShare.setOnCheckedChangeListener(null)
        binding.identityShare.isChecked = account.shared
        binding.identityShare.setOnCheckedChangeListener(listener)
        binding.identitySummary.setText(
            if (account.shared) R.string.identity_share_summary else R.string.identity_isolated_summary
        )
        binding.accountLine.text = when {
            account.signedIn -> "已登录 · XUID " + (account.xuid ?: "未知") +
                    " · 账号 " + (account.maskedAccount() ?: "未知")
            else -> "未登录"
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
                if (checked) "下次登录将尝试内置 WebView" else "下次登录将使用系统浏览器",
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

        binding.dynamicColor.setOnCheckedChangeListener(null)
        binding.dynamicColor.isChecked = LauncherPrefs.dynamicColor(host)
        binding.dynamicColor.setOnCheckedChangeListener { _, checked ->
            LauncherPrefs.setDynamicColor(host, checked)
            Toast.makeText(host, "重启应用后生效", Toast.LENGTH_SHORT).show()
        }

        binding.clearCache.setOnClickListener { confirmClearCache(host) }
    }

    private fun confirmClearCache(host: Context) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("清除缓存")
            .setMessage("只清除启动器自身的缓存目录，不影响游戏数据、账号或已导入的实例。确定清除？")
            .setNegativeButton("取消", null)
            .setPositiveButton("清除") { _, _ ->
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
                        Toast.makeText(host, "已释放 %.1f MB".format(mb), Toast.LENGTH_SHORT).show()
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
