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
import com.answer.launcher.databinding.FragmentDownloadBinding
import com.answer.launcher.databinding.ItemVersionBinding
import com.answer.launcher.ui.VersionApi
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 版本下载: lists the Minecraft Bedrock builds the MC 版本库 API reports and hands the player to
 * the right网盘 link for their ABI. The links are cloud-drive share pages, not direct files, so
 * "download" opens them in a browser — the player then imports the APK through 实例, which is the
 * launcher's existing path. Kept read-only and network-only: nothing here writes game data.
 */
class DownloadFragment : Fragment() {

    private var binding: FragmentDownloadBinding? = null
    private val versions = ArrayList<VersionApi.Version>()
    private var adapter: VersionAdapter? = null
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var loading = false

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
            showProviders(versions[position])
        }
        b.refresh.setOnClickListener { load() }
        load()
        return b.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
        adapter = null
    }

    private fun load() {
        if (loading) {
            return
        }
        loading = true
        binding?.status?.text = "正在加载版本列表…"
        Thread {
            try {
                val list = VersionApi.lastVersions()
                main.post {
                    loading = false
                    val bind = binding ?: return@post
                    versions.clear()
                    versions.addAll(list)
                    adapter?.notifyDataSetChanged()
                    bind.status.text =
                        if (list.isEmpty()) "没有可用版本" else "共 ${list.size} 个版本 · 点击选择下载网盘"
                }
            } catch (e: Exception) {
                main.post {
                    loading = false
                    binding?.status?.text = "加载失败：${e.message}"
                }
            }
        }.apply { name = "version-fetch" }.start()
    }

    private fun showProviders(v: VersionApi.Version) {
        if (v.links.isEmpty()) {
            toast("该版本没有下载链接")
            return
        }
        val providers = v.links.keys.toList()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("${v.versionAll} · 选择网盘")
            .setItems(providers.toTypedArray()) { _, which -> pickAbi(v, providers[which]) }
            .show()
    }

    private fun pickAbi(v: VersionApi.Version, provider: String) {
        val urls = v.links[provider] ?: return
        val armv7 = urls.getOrNull(0).orEmpty()
        val armv8 = urls.getOrNull(1).orEmpty()
        val options = ArrayList<Pair<String, String>>()
        if (armv8.isNotEmpty()) options.add("ARMv8（64 位）" to armv8)
        if (armv7.isNotEmpty()) options.add("ARMv7（32 位）" to armv7)
        if (options.isEmpty()) {
            toast("无可用链接")
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
            toast("没有可打开该链接的应用")
        }
    }

    private fun toast(s: String) {
        val ctx = context ?: return
        Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show()
    }

    private inner class VersionAdapter : BaseAdapter() {
        override fun getCount(): Int = versions.size
        override fun getItem(position: Int): Any = versions[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val b = if (convertView == null) {
                ItemVersionBinding.inflate(LayoutInflater.from(context), parent, false)
                    .also { it.root.tag = it }
            } else {
                convertView.tag as ItemVersionBinding
            }
            val v = versions[position]
            b.tvVersion.text = v.versionAll
            b.tvBeta.visibility = if (v.beta) View.VISIBLE else View.GONE
            val date = v.updateTime.substringBefore('T')
            b.tvMeta.text = listOf(date, v.fileSize).filter { it.isNotEmpty() }
                .joinToString("  ·  ")
            return b.root
        }
    }
}
