package com.answer.launcher.ui.fragment


import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.answer.launcher.R
import com.answer.launcher.auth.AccountManager
import com.answer.launcher.auth.AccountStore
import com.answer.launcher.auth.SessionValidator
import com.answer.launcher.core.LauncherConstants
import com.answer.launcher.core.bridge.GameBridgeClient
import com.answer.launcher.core.bridge.GameRuntime
import com.answer.launcher.core.manager.IdentityStore
import com.answer.launcher.core.manager.IOManager
import com.answer.launcher.ui.AvatarLoader
import com.answer.launcher.ui.LauncherPrefs
import com.answer.launcher.ui.SignInSheet
import com.answer.launcher.databinding.FragmentSettingBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

class SettingFragment : Fragment() {

    lateinit var binding : FragmentSettingBinding;

    private val bridgeListener = GameBridgeClient.Listener { refresh() }

    /**
     * The last 会话验证 answer, kept so switching sidebar groups repaints the card without
     * spending another network round trip. Null means "nothing asked yet" — the card then shows the
     * on-disk record and, when the player opted in to online checks, asks once for the real answer
     * (which is also the only way to learn the gamertag and the avatar).
     */
    private var validity: SessionValidator.Status? = null
    @Volatile private var validating = false

    /**
     * True once a check has come back for this visit, whether or not it produced an answer. Without
     * it a check that threw would leave [validity] null, and the repaint it triggers would ask
     * again — a self-sustaining loop of token rotations.
     */
    private var validationTried = false

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
        binding.btnAccountLogin.setOnClickListener { startSignIn() }
        binding.btnAccountSignout.setOnClickListener { confirmSignOut() }
        binding.btnAccountSwitch.setOnClickListener { showAccountSwitcher() }
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
        // One online answer per visit: the card repaints from the disk record immediately and the
        // check (which also carries the gamertag and the avatar) fills in when it lands.
        forgetValidity()
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
        val account = IdentityStore.readAccount(host)
        bindIdentity(account)
        bindAccountCard(host, account)
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
            val refreshed = IdentityStore.readAccount(host)
            bindIdentity(refreshed)
            bindAccountCard(host, refreshed)
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

    /**
     * The account header: face, name, XUID and what the last validation said about them.
     *
     * The two sources are deliberately different strengths. The on-disk record proves a sign-in
     * exists and names its XUID without a network, which is what the card shows first; the
     * gamertag and the avatar only exist on the Xbox side, so they arrive with the check — and with
     * the check disabled by the 自动验证 preference, the card stays honest about holding a local
     * record rather than pretending it is still accepted.
     */
    private fun bindAccountCard(host: Context, account: IdentityStore.Account) {
        val answer = validity
        binding.btnAccountLogin.setText(
            if (account.signedIn) R.string.account_relogin_button else R.string.account_login_button
        )
        binding.btnAccountSignout.isEnabled = account.signedIn
        if (!account.signedIn) {
            binding.accountGamertag.setText(R.string.account_not_signed_in)
            binding.accountDetail.setText(R.string.account_not_signed_in_hint)
            AvatarLoader.load(binding.accountAvatar, null, null)
            return
        }
        val gamertag = answer?.gamertag
        binding.accountGamertag.text = gamertag ?: getString(R.string.account_signed_in_fallback)
        val state = when {
            answer != null && answer.valid -> getString(R.string.setting_account_state_valid)
            answer != null -> getString(R.string.account_expired_label)
            // Either the player opted out of online checks or the one attempt we made came back
            // with nothing: in both cases what is on screen is the local record, unverified.
            !LauncherPrefs.autoValidateLogin(host) || validationTried ->
                getString(R.string.setting_account_state_local)
            else -> getString(R.string.account_checking_status)
        }
        val xuid = answer?.xuid ?: account.xuid
        binding.accountDetail.text = getString(
            R.string.setting_account_xuid_line,
            xuid ?: getString(R.string.account_unknown_xuid),
            state
        )
        // The letter avatar only starts from a real name, so an unverified card shows the generic
        // account icon rather than the first character of the word "Signed in".
        AvatarLoader.load(binding.accountAvatar, answer?.avatarUrl, gamertag)
        validate(host, account)
    }

    /**
     * Asks Xbox whether the stored chain still authenticates, and takes the avatar with the answer.
     * Exactly once per visit, and only when the player opted in to online validation: this call
     * rotates refresh tokens, so it must not be spent on every sidebar repaint or bridge callback.
     * {@code validity} is cleared on resume and after a sign-in or sign-out — the three moments the
     * previous answer actually stops describing reality.
     */
    private fun validate(host: Context, account: IdentityStore.Account) {
        if (validating || validationTried || !account.signedIn
            || !LauncherPrefs.autoValidateLogin(host)
        ) {
            return
        }
        validating = true
        val root = IdentityStore.accountRoot(host)
        val version = SignInSheet.gameVersion(host)
        val agent = SignInSheet.userAgent(version)
        Thread {
            val result = try {
                SessionValidator.check(root, version, agent)
            } catch (failure: Throwable) {
                null
            }
            activity?.runOnUiThread {
                validating = false
                validationTried = true
                if (!isAdded || !::binding.isInitialized) {
                    return@runOnUiThread
                }
                if (result != null) {
                    validity = result
                }
                bindAccountCard(host, account)
            }
        }.apply { name = "account-validate" }.start()
    }

    /** Drops the cached answer so the next repaint asks again — used by every state change. */
    private fun forgetValidity() {
        validity = null
        validationTried = false
    }

    private fun startSignIn() {
        SignInSheet.show(requireActivity()) { success, message ->
            val ctx = context ?: return@show
            Toast.makeText(
                ctx,
                if (success) getString(R.string.sign_in_complete_toast)
                else getString(R.string.sign_in_failed_toast, message),
                Toast.LENGTH_LONG
            ).show()
            // A new chain under the same files: the old answer, and with it the old avatar, is void.
            forgetValidity()
            refresh()
        }
    }

    /**
     * Mirrors the home card's sign-out: destructive, so it is refused while {@code :game} is up
     * (XAL holds the store open and would rewrite it underneath us), confirmed first, and run off
     * the main thread.
     */
    private fun confirmSignOut() {
        val state = GameBridgeClient.get().runtimeState()
        if (state == GameRuntime.READY || state == GameRuntime.ASSEMBLING) {
            Toast.makeText(requireContext(), R.string.account_game_running_block, Toast.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.account_signout_title)
            .setMessage(R.string.account_signout_confirm_message)
            .setNegativeButton(R.string.common_cancel, null)
            .setPositiveButton(R.string.account_signout_confirm_button) { _, _ ->
                val host = requireContext().applicationContext
                Thread {
                    val ok = IdentityStore.signOut(host)
                    activity?.runOnUiThread {
                        if (!isAdded || !::binding.isInitialized) {
                            return@runOnUiThread
                        }
                        Toast.makeText(host,
                            if (ok) R.string.account_signout_success else R.string.account_signout_partial,
                            Toast.LENGTH_SHORT).show()
                        forgetValidity()
                        refresh()
                    }
                }.apply { name = "signout" }.start()
            }
            .show()
    }

    /**
     * Multi-account switcher: every stored account is listed (the active one marked); tapping one
     * re-exports its XAL so the next launch boots as them. "添加账号" runs the normal sign-in,
     * which appends to the list; "删除当前账号" drops the active record from the launcher's list
     * without touching the game's on-disk store. All the network work runs off the main thread.
     */
    private fun showAccountSwitcher() {
        val host = requireContext().applicationContext
        val root = IdentityStore.accountRoot(host)
        val accounts = AccountManager.list(root)
        val active = AccountManager.active(root)
        if (accounts.isEmpty()) {
            startSignIn()
            return
        }
        val labels = ArrayList<String>()
        for (a in accounts) {
            val name = a.gamertag ?: getString(R.string.account_signed_in_fallback)
            val marker = if (active != null && active.xuid == a.xuid) "  ✓" else ""
            labels.add(name + marker)
        }
        labels.add(getString(R.string.account_add_button))
        labels.add(getString(R.string.account_delete_current))
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.account_switch_title)
            .setItems(labels.toTypedArray()) { _, which ->
                when {
                    which == labels.size - 2 -> startSignIn()
                    which == labels.size - 1 -> confirmDeleteAccount(active)
                    else -> switchTo(root, accounts[which].xuid)
                }
            }
            .show()
    }

    private fun switchTo(root: java.io.File, xuid: String?) {
        if (xuid == null) return
        val host = requireContext().applicationContext
        if (activeXuid(host) == xuid) return
        val state = GameBridgeClient.get().runtimeState()
        if (state == GameRuntime.READY || state == GameRuntime.ASSEMBLING) {
            Toast.makeText(requireContext(), R.string.account_game_running_block, Toast.LENGTH_SHORT).show()
            return
        }
        val version = SignInSheet.gameVersion(host)
        val agent = SignInSheet.userAgent(version)
        Toast.makeText(host, R.string.account_switching, Toast.LENGTH_SHORT).show()
        Thread {
            val ok = try {
                AccountManager.activate(root, xuid, version, agent) != null
            } catch (failure: Throwable) {
                false
            }
            activity?.runOnUiThread {
                if (!isAdded || !::binding.isInitialized) return@runOnUiThread
                Toast.makeText(host,
                    if (ok) R.string.account_switched else R.string.account_switch_failed,
                    Toast.LENGTH_SHORT).show()
                forgetValidity()
                refresh()
            }
        }.apply { name = "account-switch" }.start()
    }

    private fun activeXuid(host: Context): String? =
        AccountManager.active(IdentityStore.accountRoot(host))?.xuid

    private fun confirmDeleteAccount(active: AccountStore.Account?) {
        if (active == null) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.account_delete_current)
            .setMessage(getString(R.string.account_delete_confirm, active.gamertag))
            .setNegativeButton(R.string.common_cancel, null)
            .setPositiveButton(R.string.common_delete) { _, _ ->
                val host = requireContext().applicationContext
                val root = IdentityStore.accountRoot(host)
                Thread {
                    try {
                        AccountManager.remove(root, active.xuid)
                    } catch (ignored: Throwable) {
                    }
                    activity?.runOnUiThread {
                        if (!isAdded || !::binding.isInitialized) return@runOnUiThread
                        forgetValidity()
                        refresh()
                    }
                }.apply { name = "account-delete" }.start()
            }
            .show()
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
