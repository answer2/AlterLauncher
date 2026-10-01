package com.answer.launcher.ui.fragment;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import com.answer.launcher.R;
import com.answer.launcher.core.manager.GameDataStore;
import com.answer.launcher.core.manager.IdentityStore;
import com.answer.launcher.core.manager.Instance;
import com.answer.launcher.core.manager.InstanceStore;
import com.answer.launcher.databinding.FragmentContentBinding;
import com.answer.launcher.ui.InstanceSpinnerAdapter;

import java.util.ArrayList;
import java.util.List;

/**
 * 内容 (task #18/#19/#20/#33): the entry point for worlds, resource packs, behaviour packs
 * and cross-version global packs. Uses a compact PCL-style left sidebar (custom rows,
 * not a NavigationRail — that is stuck at an 80dp minimum) to swap the right-hand page.
 *
 * <p>The header above the page picks <em>which installation</em> the sidebar manages
 * (task #33): every child screen is handed the chosen slug as a {@code "slug"} argument and
 * resolves its paths through the per-instance {@code GameDataStore} helpers, so a version
 * that is not currently booted can still be prepared. No slug means "the active instance",
 * which is also what the header shows on entry.
 */
public class ContentFragment extends Fragment {

    /** Restored across a configuration change so the picker does not jump back to the active one. */
    private static final String STATE_SLUG = "slug";

    private static final int PAGE_WORLDS = 0;
    private static final int PAGE_RESOURCE = 1;
    private static final int PAGE_BEHAVIOR = 2;
    private static final int PAGE_GLOBAL = 3;
    private static final int PAGE_PLUGIN = 4;

    private FragmentContentBinding binding;
    private View selectedItem;

    /** The header dropdown that picks which installation the sidebar manages (task #33/#44). */
    private InstanceSpinnerAdapter instanceAdapter;
    /** True while the adapter is reprogrammed, so a rebuild does not look like a user choice. */
    private boolean spinnerSuppress;

    /** The instance being managed; null means the active one, which is the default choice. */
    private String selectedSlug;
    private final List<Instance> instances = new ArrayList<>();
    private String activeSlug;
    private volatile boolean loading;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            selectedSlug = savedInstanceState.getString(STATE_SLUG);
        }
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        binding = FragmentContentBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        bindRow(binding.navWorlds, PAGE_WORLDS);
        bindRow(binding.navResource, PAGE_RESOURCE);
        bindRow(binding.navBehavior, PAGE_BEHAVIOR);
        bindRow(binding.navGlobal, PAGE_GLOBAL);
        bindRow(binding.navPlugin, PAGE_PLUGIN);
        instanceAdapter = new InstanceSpinnerAdapter(requireContext());
        binding.instanceSpinner.setAdapter(instanceAdapter);
        binding.instanceSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (spinnerSuppress) return;
                InstanceSpinnerAdapter.Item item = instanceAdapter.getItemAt(position);
                if (item != null) chooseInstance(item.id);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        updateHeader();
        select(binding.navWorlds);
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_SLUG, selectedSlug);
    }

    private void bindRow(View row, int page) {
        row.setOnClickListener(v -> select(row));
        row.setTag(page);
    }

    private void select(View row) {
        if (selectedItem != null) selectedItem.setActivated(false);
        selectedItem = row;
        row.setActivated(true);
        openPage();
    }

    /** Rebuilds the page for the current slug; the old fragment is dropped along with its paths. */
    private void openPage() {
        if (binding == null || selectedItem == null) return;
        getChildFragmentManager().beginTransaction()
                .replace(R.id.content_frame, createPage((Integer) selectedItem.getTag()))
                .commit();
    }

    private Fragment createPage(int page) {
        switch (page) {
            case PAGE_RESOURCE:
                return PacksFragment.newInstance(PacksFragment.KIND_RESOURCE, selectedSlug);
            case PAGE_BEHAVIOR:
                return PacksFragment.newInstance(PacksFragment.KIND_BEHAVIOR, selectedSlug);
            case PAGE_GLOBAL:
                // Shared across versions by design: it takes the slug only so the header and
                // the page never disagree about what is being shown.
                return GlobalPacksFragment.newInstance(selectedSlug);
            case PAGE_PLUGIN:
                // Plugins are isolated per installation (task #35): the page manages the selected
                // instance's own plugin dir, the same one :game loads when that instance boots.
                return PluginsFragment.newInstance(currentSlug());
            case PAGE_WORLDS:
            default:
                return WorldsFragment.newInstance(selectedSlug);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        updateAvailability();
        loadInstances();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
        selectedItem = null;
        loading = false;
    }

    // ------------------------------------------------------------- instance picker

    /** Reads the store off the main thread; the header only needs the labels and the active id. */
    private void loadInstances() {
        if (binding == null || loading) return;
        final Context ctx = requireContext().getApplicationContext();
        loading = true;
        new Thread(() -> {
            final List<Instance> found = InstanceStore.list(ctx);
            final String active = IdentityStore.activeSlug(ctx);
            post(() -> {
                loading = false;
                if (binding == null || !isAdded()) return;
                instances.clear();
                instances.addAll(found);
                activeSlug = active;
                // An instance deleted from the 实例 page must not stay selected: the page on
                // screen would keep writing into a tree that no longer describes anything.
                if (selectedSlug != null && !hasInstance(selectedSlug)) {
                    selectedSlug = null;
                    // This hop can land after the state was written, and a transaction then
                    // throws; the next resume rebuilds the page from the corrected slug.
                    if (!isStateSaved()) openPage();
                }
                updateHeader();
                updateAvailability();
            });
        }, "content-instances").start();
    }

    private boolean hasInstance(String slug) {
        for (Instance in : instances) {
            if (slug.equals(in.id)) return true;
        }
        return false;
    }

    /** The slug the pages are working against, resolved to the active instance when unset. */
    private String currentSlug() {
        if (selectedSlug != null && !selectedSlug.isEmpty()) return selectedSlug;
        return activeSlug;
    }

    private void chooseInstance(String slug) {
        if (slug == null || slug.equals(currentSlug())) return;
        selectedSlug = slug;
        updateHeader();
        // The child screens cache their root when they are built, so swapping the instance
        // means rebuilding the page that is on screen.
        openPage();
        updateAvailability();
    }

    /** Rebuilds the header dropdown from the loaded instances and selects the one being managed. */
    private void updateHeader() {
        if (binding == null || instanceAdapter == null) return;
        spinnerSuppress = true;
        List<InstanceSpinnerAdapter.Item> items = new ArrayList<>();
        for (Instance in : instances) {
            items.add(new InstanceSpinnerAdapter.Item(in.id, labelOf(in),
                    in.isContentOnly() ? R.drawable.ic_box_content : R.drawable.mincraft));
        }
        instanceAdapter.setItems(items);
        binding.instanceSpinner.setSelection(instanceAdapter.indexOfId(currentSlug()));
        spinnerSuppress = false;
    }

    /** The header's short name for one instance: its label, plus the version it holds. */
    private String labelOf(Instance in) {
        String name = in.label == null || in.label.trim().isEmpty() ? in.id : in.label.trim();
        String version = in.versionName;
        return version == null || version.isEmpty() || version.equals(name)
                ? name : name + " · " + version;
    }

    /**
     * The pages are only meaningful once an installation owns a data root — either the active
     * one, or any imported instance the picker can point at. With neither, the hint at 实例
     * replaces the whole content area.
     */
    private void updateAvailability() {
        if (binding == null) return;
        boolean available = !instances.isEmpty()
                || GameDataStore.activeRoot(requireContext()) != null;
        binding.sidebar.setVisibility(available ? View.VISIBLE : View.GONE);
        binding.instancePicker.setVisibility(available ? View.VISIBLE : View.GONE);
        binding.contentFrame.setVisibility(available ? View.VISIBLE : View.GONE);
        binding.textNoInstance.setVisibility(available ? View.GONE : View.VISIBLE);
    }

    // ---------------------------------------------------------------- plumbing

    private void post(Runnable ui) {
        FragmentActivity act = getActivity();
        if (act != null) act.runOnUiThread(ui);
    }

    private void toast(String message) {
        Context ctx = getContext();
        if (ctx != null && message != null) {
            Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show();
        }
    }
}
