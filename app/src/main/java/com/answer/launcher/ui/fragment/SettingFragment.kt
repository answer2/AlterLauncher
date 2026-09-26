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
import com.answer.launcher.databinding.FragmentSettingBinding

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

    override fun onDestroy() {
        super.onDestroy()

    }
}
