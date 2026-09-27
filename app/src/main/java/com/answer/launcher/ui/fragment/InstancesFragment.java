package com.answer.launcher.ui.fragment;

import android.app.AlertDialog;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import com.answer.launcher.core.manager.Instance;
import com.answer.launcher.core.manager.InstanceImporter;
import com.answer.launcher.core.manager.InstanceStore;
import com.answer.launcher.databinding.FragmentInstancesBinding;
import com.answer.launcher.databinding.ItemInstanceBinding;

import java.util.ArrayList;
import java.util.List;

/**
 * 实例管理 (levi-compat-design.md §3): lists imported Minecraft APKs, lets the
 * user import one via SAF, set the active instance the guest boots into, and
 * rename/delete. Backs {@link InstanceStore} (Tier-1) and is read by the
 * launch flow (Tier-3) via the {@code .active-instance} signal.
 */
public class InstancesFragment extends Fragment {

    private FragmentInstancesBinding binding;
    private InstanceAdapter adapter;
    private final List<Instance> instances = new ArrayList<>();
    private String activeId;
    private ActivityResultLauncher<String[]> importLauncher;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Registered before STARTED, as required by the result API.
        importLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                uri -> { if (uri != null) startImport(uri); });
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentInstancesBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        adapter = new InstanceAdapter();
        binding.list.setAdapter(adapter);
        binding.list.setOnItemClickListener((parent, v, position, id) ->
                setActive(instances.get(position)));
        binding.list.setOnItemLongClickListener((parent, v, position, id) -> {
            showOptions(instances.get(position));
            return true;
        });
        binding.btnImport.setOnClickListener(v ->
                importLauncher.launch(new String[]{"application/vnd.android.package-archive"}));
        binding.btnSystem.setOnClickListener(v -> {
            InstanceStore.clearActive(requireContext());
            toast("已切换为系统安装的 Minecraft");
            refresh();
        });
        refresh();
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    private void refresh() {
        if (binding == null) return;
        Context ctx = requireContext();
        instances.clear();
        instances.addAll(InstanceStore.list(ctx));
        activeId = InstanceStore.getActive(ctx);
        adapter.notifyDataSetChanged();
        boolean empty = instances.isEmpty();
        binding.list.setVisibility(empty ? View.GONE : View.VISIBLE);
        binding.textEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
    }

    private void startImport(Uri uri) {
        Context ctx = requireContext();
        toast("正在导入…");
        new Thread(() -> {
            InstanceImporter.Result r = InstanceImporter.import_(ctx, uri, null);
            if (getActivity() == null) return;
            getActivity().runOnUiThread(() -> {
                if (binding == null) return;
                if (r.success()) {
                    toast("导入成功：" + r.instance.versionName
                            + (r.aligned16kb ? "" : "（部分 .so 非 16KB 对齐）"));
                } else {
                    new AlertDialog.Builder(ctx)
                            .setTitle("导入失败")
                            .setMessage(r.error == null ? "未知错误" : r.error)
                            .setPositiveButton("知道了", null)
                            .show();
                }
                refresh();
            });
        }).start();
    }

    private void setActive(Instance in) {
        InstanceStore.setActive(requireContext(), in.id);
        toast("已设为当前实例：" + in.label);
        refresh();
    }

    private void showOptions(Instance in) {
        new AlertDialog.Builder(requireContext())
                .setTitle(in.label)
                .setItems(new String[]{"设为当前实例", "重命名", "删除"}, (d, which) -> {
                    if (which == 0) setActive(in);
                    else if (which == 1) showRename(in);
                    else if (which == 2) showDelete(in);
                })
                .show();
    }

    private void showRename(Instance in) {
        final EditText et = new EditText(requireContext());
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setText(in.label);
        et.setSelection(in.label.length());
        new AlertDialog.Builder(requireContext())
                .setTitle("重命名")
                .setView(et)
                .setPositiveButton("确定", (d, w) -> {
                    String nl = et.getText().toString().trim();
                    if (nl.isEmpty()) { toast("名称不能为空"); return; }
                    InstanceStore.rename(requireContext(), in.id, nl);
                    refresh();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showDelete(Instance in) {
        new AlertDialog.Builder(requireContext())
                .setTitle("删除实例")
                .setMessage("删除「" + in.label + "」？此操作不可撤销。")
                .setPositiveButton("删除", (d, w) -> {
                    InstanceStore.delete(requireContext(), in.id);
                    toast("已删除");
                    refresh();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void toast(String s) {
        Context ctx = getContext();
        if (ctx != null) Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show();
    }

    private class InstanceAdapter extends BaseAdapter {
        private final LayoutInflater inflater = LayoutInflater.from(requireContext());

        @Override
        public int getCount() { return instances.size(); }

        @Override
        public Object getItem(int position) { return instances.get(position); }

        @Override
        public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ItemInstanceBinding b;
            if (convertView == null) {
                b = ItemInstanceBinding.inflate(inflater, parent, false);
                convertView = b.getRoot();
                convertView.setTag(b);
            } else {
                b = (ItemInstanceBinding) convertView.getTag();
            }
            Instance in = instances.get(position);
            b.tvLabel.setText(in.label);
            String sub = "版本 " + in.versionName + " · "
                    + (in.abi == null || in.abi.isEmpty() ? "无 native 库" : in.abi)
                    + " · #" + in.versionCode;
            b.tvSubtitle.setText(sub);
            b.tvId.setText(in.id);
            boolean active = in.id.equals(activeId);
            b.chipActive.setVisibility(active ? View.VISIBLE : View.GONE);
            boolean aligned = in.aligned16kb;
            b.tvAlign.setVisibility(View.VISIBLE);
            b.tvAlign.setText(aligned ? "16KB" : "非16KB");
            b.tvAlign.setTextColor(aligned ? 0xFF2E7D32 : 0xFFC62828);
            final Instance current = in;
            b.btnMore.setOnClickListener(v -> showOptions(current));
            return convertView;
        }
    }
}
