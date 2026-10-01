package com.answer.launcher.ui.fragment;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import com.answer.launcher.core.manager.Instance;
import com.answer.launcher.core.manager.InstanceImporter;
import com.answer.launcher.core.manager.InstanceStore;
import com.answer.launcher.R;
import com.answer.launcher.databinding.FragmentInstancesBinding;
import com.answer.launcher.databinding.ItemInstanceBinding;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

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
        binding.toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_import) {
                importLauncher.launch(new String[]{"application/vnd.android.package-archive"});
                return true;
            }
            if (item.getItemId() == R.id.action_system) {
                InstanceStore.clearActive(requireContext());
                toast(getString(R.string.instance_switched_to_system));
                refresh();
                return true;
            }
            return false;
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
        toast(getString(R.string.instance_importing));
        new Thread(() -> {
            InstanceImporter.Result r = InstanceImporter.import_(ctx, uri, null);
            if (getActivity() == null) return;
            getActivity().runOnUiThread(() -> {
                if (binding == null) return;
                if (r.success()) {
                    toast(getString(R.string.instance_import_success, r.instance.versionName)
                            + (r.aligned16kb ? "" : getString(R.string.instance_import_align_warning)));
                } else {
                    new MaterialAlertDialogBuilder(ctx)
                            .setTitle(R.string.instance_import_failed_title)
                            .setMessage(r.error == null ? getString(R.string.instance_unknown_error) : r.error)
                            .setPositiveButton(R.string.common_got_it, null)
                            .show();
                }
                refresh();
            });
        }).start();
    }

    private void setActive(Instance in) {
        InstanceStore.setActive(requireContext(), in.id);
        toast(getString(R.string.instance_set_active, in.label));
        refresh();
    }

    private void showOptions(Instance in) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(in.label)
                .setItems(new String[]{getString(R.string.instance_option_set_active), getString(R.string.instance_option_rename), getString(R.string.instance_option_delete)}, (d, which) -> {
                    if (which == 0) setActive(in);
                    else if (which == 1) showRename(in);
                    else if (which == 2) showDelete(in);
                })
                .show();
    }

    private void showRename(Instance in) {
        Context ctx = requireContext();
        TextInputLayout field = new TextInputLayout(ctx);
        field.setPadding(dp(24), dp(8), dp(24), 0);
        TextInputEditText et = new TextInputEditText(ctx);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setText(in.label);
        et.setSelection(in.label.length());
        field.addView(et);
        new MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.instance_rename_title)
                .setView(field)
                .setPositiveButton(R.string.common_confirm, (d, w) -> {
                    String nl = et.getText() == null ? "" : et.getText().toString().trim();
                    if (nl.isEmpty()) { toast(getString(R.string.instance_rename_empty_error)); return; }
                    InstanceStore.rename(requireContext(), in.id, nl);
                    refresh();
                })
                .setNegativeButton(R.string.common_cancel, null)
                .show();
    }

    private void showDelete(Instance in) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.instance_delete_title)
                .setMessage(getString(R.string.instance_delete_confirm_message, in.label))
                .setPositiveButton(R.string.common_delete, (d, w) -> {
                    InstanceStore.delete(requireContext(), in.id);
                    toast(getString(R.string.instance_deleted));
                    refresh();
                })
                .setNegativeButton(R.string.common_cancel, null)
                .show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
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
            String sub = getString(R.string.instance_subtitle_format, in.versionName,
                    in.abi == null || in.abi.isEmpty() ? getString(R.string.instance_no_native_libs) : in.abi,
                    in.versionCode);
            b.tvSubtitle.setText(sub);
            b.tvId.setText(in.id);
            boolean active = in.id.equals(activeId);
            b.chipActive.setVisibility(active ? View.VISIBLE : View.GONE);
            boolean aligned = in.aligned16kb;
            b.tvAlign.setVisibility(View.VISIBLE);
            b.tvAlign.setText(aligned ? "16KB" : getString(R.string.instance_not_16kb));
            b.tvAlign.setTextColor(aligned ? 0xFF2E7D32 : 0xFFC62828);
            final Instance current = in;
            b.btnMore.setOnClickListener(v -> showOptions(current));
            return convertView;
        }
    }
}
