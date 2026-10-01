package com.answer.launcher.ui.fragment

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.answer.launcher.R
import com.answer.launcher.databinding.FragmentDownloadBinding
import com.answer.launcher.databinding.ItemVersionBinding
import com.answer.launcher.ui.VersionApi
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 版本下载: lists the Minecraft Bedrock builds the MC 版本库 API reports and hands the player to
 * the right网盘 link for their ABI. The links are cloud-drive share pages, not direct files, so
 * "download" opens them in a browser — the player then imports the APK through 实例, which is the
 * launcher's existing path. Kept read-only and network-only: nothing here writes game data.
 *
 * A compact PCL-style left sidebar (the same pattern as the content and settings pages) splits the
 * single flat list into 正式版 / 测试版 tabs that filter the one cached fetch, plus four reserved
 * category rows (资源包 / 行为包 / 世界 / 插件) that only show a "敬请期待" placeholder for now.
 */
class DownloadFragment : Fragment() {

    private var binding: FragmentDownloadBinding? = null

    /** The whole catalog, fetched once and cached. */
    private val allVersions = ArrayList<VersionApi.Version>()

    /** The filtered subset currently shown in the list. */
    private val shown = ArrayList<VersionApi.Version>()

    private var adapter: VersionAdapter? = null
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var loading = false

    private var selectedItem: View? = null
    private var currentTab = TAB_RELEASE

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val b = FragmentDownloadBinding.inflate(inflater, container, false)
        binding = b
        val a = VersionAdapter()
        adapter = a
        b.versionList.adapter = a
        b.versionList.setOnItemClickListener { _, _, position, _ ->
            showProviders(shown[position])
        }
        b.btnRefresh.setOnClickListener { load() }

        bindRow(b.navRelease, TAB_RELEASE)
        bindRow(b.navBeta, TAB_BETA)
        bindRow(b.navDlResource, TAB_RESERVED)
        bindRow(b.navDlBehavior, TAB_RESERVED)
        bindRow(b.navDlWorld, TAB_RESERVED)
        bindRow(b.navDlPlugin, TAB_RESERVED)
        select(b.navRelease)

        // Fetch the full list once; 正式版/测试版 re-filter it locally, refresh re-fetches.
        load()
        return b.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
        adapter = null
        selectedItem = null
    }

    private fun bindRow(row: View, tab: Int) {
        row.setOnClickListener { select(row) }
        row.tag = tab
    }

    /** PCL-style single-pane switch: highlight the active row, swap the visible group. */
    private fun select(row: View) {
        selectedItem?.setActivated(false)
        selectedItem = row
        row.setActivated(true)
        val tab = row.tag as Int
        currentTab = tab
        val b = binding ?: return
        if (tab == TAB_RESERVED) {
            b.groupVersions.visibility = View.GONE
            b.groupReserved.visibility = View.VISIBLE
        } else {
            b.groupVersions.visibility = View.VISIBLE
            b.groupReserved.visibility = View.GONE
            b.groupTitle.setText(if (tab == TAB_BETA) R.string.download_nav_beta else R.string.download_nav_release)
            applyFilter()
        }
    }

    /** Re-renders the list from the cached catalog for the selected release/beta tab. */
    private fun applyFilter() {
        val b = binding ?: return
        shown.clear()
        val wantBeta = currentTab == TAB_BETA
        for (v in allVersions) {
            if (v.beta == wantBeta) shown.add(v)
        }
        adapter?.notifyDataSetChanged()
        b.status.text = when {
            loading -> getString(R.string.download_loading_versions)
            allVersions.isEmpty() -> getString(R.string.download_no_versions)
            shown.isEmpty() -> getString(R.string.download_no_versions)
            else -> getString(R.string.download_version_count, shown.size)
        }
    }

    private fun load() {
        if (loading) {
            return
        }
        loading = true
        binding?.status?.text = getString(R.string.download_loading_versions)
        Thread {
            try {
                val list = VersionApi.lastVersions()
                main.post {
                    loading = false
                    if (binding == null) return@post
                    allVersions.clear()
                    allVersions.addAll(list)
                    applyFilter()
                }
            } catch (e: Exception) {
                main.post {
                    loading = false
                    binding?.status?.text = getString(R.string.download_load_failed, e.message)
                }
            }
        }.apply { name = "version-fetch" }.start()
    }

    private fun showProviders(v: VersionApi.Version) {
        if (v.links.isEmpty()) {
            toast(getString(R.string.download_no_links))
            return
        }
        val providers = v.links.keys.toList()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.download_provider_dialog_title, v.versionAll))
            .setItems(providers.toTypedArray()) { _, which -> pickAbi(v, providers[which]) }
            .show()
    }

    private fun pickAbi(v: VersionApi.Version, provider: String) {
        val urls = v.links[provider] ?: return
        val armv7 = urls.getOrNull(0).orEmpty()
        val armv8 = urls.getOrNull(1).orEmpty()
        val options = ArrayList<Pair<String, String>>()
        if (armv8.isNotEmpty()) options.add(getString(R.string.download_armv8_label) to armv8)
        if (armv7.isNotEmpty()) options.add(getString(R.string.download_armv7_label) to armv7)
        if (options.isEmpty()) {
            toast(getString(R.string.download_no_abi_links))
            return
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(provider)
            .setItems(options.map { it.first }.toTypedArray()) { _, which ->
                openInBrowser(options[which].second)
            }
            .show()
    }

    private fun openInBrowser(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            toast(getString(R.string.download_no_app_for_link))
        }
    }

    private fun toast(s: String) {
        val ctx = context ?: return
        Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show()
    }

    private inner class VersionAdapter : BaseAdapter() {
        override fun getCount(): Int = shown.size
        override fun getItem(position: Int): Any = shown[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val b = if (convertView == null) {
                ItemVersionBinding.inflate(LayoutInflater.from(context), parent, false)
                    .also { it.root.tag = it }
            } else {
                convertView.tag as ItemVersionBinding
            }
            val v = shown[position]
            b.tvVersion.text = v.versionAll
            b.tvBeta.visibility = if (v.beta) View.VISIBLE else View.GONE
            val date = v.updateTime.substringBefore('T')
            b.tvMeta.text = listOf(date, v.fileSize).filter { it.isNotEmpty() }
                .joinToString("  ·  ")
            return b.root
        }
    }

    companion object {
        private const val TAB_RELEASE = 0
        private const val TAB_BETA = 1
        private const val TAB_RESERVED = 2
    }
}
