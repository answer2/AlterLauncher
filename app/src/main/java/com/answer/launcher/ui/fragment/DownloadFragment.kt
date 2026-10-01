package com.answer.launcher.ui.fragment

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
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
 *
 * The two tabs alone would be a two-version launcher, because {@code last_version} only ever
 * reports the newest stable and the newest beta. The search box above the list is what reaches the
 * rest of the catalog: it asks {@code search_version} for one {@code X.Y} family — accepting the
 * long forms players actually type, like {@code 1.26.52.3} — and swaps the tab list for the result
 * until it is cleared, which an empty search, the 显示最新 button under the box, or another tab all
 * do.
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
    @Volatile private var searching = false

    /** True while [shown] holds a search_version result instead of the cached family list. */
    private var searchActive = false

    private var selectedItem: View? = null
    private var currentTab = TAB_RELEASE
    private var currentFamily = VersionApi.FAMILIES[VersionApi.FAMILIES.size - 1]

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
        b.btnSearch.setOnClickListener { search() }
        b.btnShowLatest.setOnClickListener {
            b.searchInput.setText("")
            applyFilter()
        }
        b.searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                search()
                true
            } else {
                false
            }
        }

        // Version-family dropdown, newest last and preselected — the same selector the site uses.
        val families = VersionApi.FAMILIES.toList()
        b.familySpinner.adapter = android.widget.ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_dropdown_item, families)
        b.familySpinner.setSelection(families.size - 1)
        b.familySpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                val family = families[position]
                if (family != currentFamily) {
                    currentFamily = family
                    load()
                }
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        bindRow(b.navRelease, TAB_RELEASE)
        bindRow(b.navBeta, TAB_BETA)
        bindRow(b.navDlResource, TAB_RESERVED)
        bindRow(b.navDlBehavior, TAB_RESERVED)
        bindRow(b.navDlWorld, TAB_RESERVED)
        bindRow(b.navDlPlugin, TAB_RESERVED)
        select(b.navRelease)

        load()
        return b.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
        adapter = null
        selectedItem = null
        searching = false
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
            // The tab's own list is not the result of whatever is still in the box, so the box
            // goes with the result.
            b.searchInput.setText("")
            applyFilter()
        }
    }

    /**
     * Re-renders the list from the cached catalog for the selected release/beta tab. This is also
     * the way out of a search — an empty search, 显示最新 and switching tab all land here — so a
     * stale search result can never be mistaken for the tab's own list: the cached catalog is the
     * default state, and 显示最新 only exists while a result is on screen.
     */
    private fun applyFilter() {
        val b = binding ?: return
        searchActive = false
        b.btnShowLatest.visibility = View.GONE
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

    /**
     * Fills the cached catalog for the selected family: the whole {@code get_version} list for it
     * (channel 2 = release + preview + beta, filtered locally into the two tabs) merged with the
     * {@code last_version} builds, which carry the current 1.26.x line the family list does not.
     * Deduped by version_all so the newest stable never appears twice.
     */
    private fun load() {
        if (loading) {
            return
        }
        loading = true
        binding?.status?.text = getString(R.string.download_loading_versions)
        val family = currentFamily
        Thread {
            val merged = LinkedHashMap<String, VersionApi.Version>()
            var failure: Exception? = null
            try {
                for (v in VersionApi.getVersionList(family, 2)) merged[v.versionAll] = v
            } catch (e: Exception) {
                failure = e
            }
            try {
                for (v in VersionApi.lastVersions()) if (!merged.containsKey(v.versionAll)) merged[v.versionAll] = v
            } catch (ignored: Exception) {
                // last_version is a bonus on top of the family list; its failure is not fatal.
            }
            val list = ArrayList(merged.values)
            main.post {
                loading = false
                if (binding == null) return@post
                if (list.isEmpty() && failure != null) {
                    binding?.status?.text = getString(R.string.download_load_failed, failure.message)
                    return@post
                }
                allVersions.clear()
                allVersions.addAll(list)
                if (!searchActive) {
                    applyFilter()
                }
            }
        }.apply { name = "version-fetch" }.start()
    }

    /**
     * Asks the API for one version family and shows what it returns in place of the tab list.
     * An empty box is the documented way back to the cached 最新 list, so it never hits the network.
     */
    private fun search() {
        val b = binding ?: return
        val typed = b.searchInput.text?.toString().orEmpty().trim()
        if (typed.isEmpty()) {
            applyFilter()
            return
        }
        if (searching) {
            return
        }
        val query = normalizeVersion(typed)
        if (query == null) {
            toast(getString(R.string.download_search_format_error))
            b.status.text = getString(R.string.download_search_format_error)
            return
        }
        searching = true
        b.status.text = getString(R.string.download_search_loading, query)
        Thread {
            try {
                val found = VersionApi.searchVersion(query)
                main.post {
                    searching = false
                    val box = binding ?: return@post
                    // The player may have switched tab (which empties the box) or edited the query
                    // while this was in flight; a stale answer must not paint over what they moved on to.
                    if (box.searchInput.text?.toString().orEmpty().trim() != typed) return@post
                    shown.clear()
                    shown.addAll(found)
                    searchActive = true
                    box.btnShowLatest.visibility = View.VISIBLE
                    adapter?.notifyDataSetChanged()
                    box.status.text = if (found.isEmpty()) {
                        getString(R.string.download_search_not_found, query)
                    } else {
                        getString(R.string.download_search_count, query, found.size)
                    }
                    if (found.isEmpty()) {
                        toast(getString(R.string.download_search_not_found, query))
                    }
                }
            } catch (e: Exception) {
                main.post {
                    searching = false
                    val box = binding ?: return@post
                    box.status.text = getString(R.string.download_load_failed, e.message)
                    toast(getString(R.string.download_load_failed, e.message ?: ""))
                }
            }
        }.apply { name = "version-search" }.start()
    }

    /**
     * Turns what the player typed into the short {@code X.Y} family key the search endpoint wants:
     * drop a leading {@code 1.} and keep the first two segments, so {@code 1.26.52.3},
     * {@code 1.26.52} and {@code 26.52} all mean the same thing. Anything that is not two numbers
     * is refused rather than sent, because a malformed {@code s=} reads as "no such version" and
     * would report a typo as an unavailable build.
     */
    private fun normalizeVersion(raw: String): String? {
        var text = raw.trim()
        if (text.startsWith("1.")) {
            text = text.substring(2)
        }
        val parts = text.split(".")
        if (parts.size < 2) {
            return null
        }
        val major = parts[0]
        val minor = parts[1]
        if (major.isEmpty() || minor.isEmpty() ||
            major.any { !it.isDigit() } || minor.any { !it.isDigit() }
        ) {
            return null
        }
        return "$major.$minor"
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
