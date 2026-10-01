package com.answer.launcher.ui.fragment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.answer.launcher.R;
import com.answer.launcher.core.manager.GameDataStore;
import com.answer.launcher.databinding.FragmentContentBinding;

/**
 * 内容 (task #18/#19/#20): the entry point for worlds, resource packs, behaviour packs
 * and cross-version global packs. Uses a compact PCL-style left sidebar (custom rows,
 * not a NavigationRail — that is stuck at an 80dp minimum) to swap the right-hand page.
 * Everything here is scoped to the currently active instance because that owns
 * {@code games/com.mojang}; with no active instance the sidebar is replaced by a hint
 * pointing at 实例.
 */
public class ContentFragment extends Fragment {

    private FragmentContentBinding binding;
    private View selectedItem;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        binding = FragmentContentBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        bindRow(binding.navWorlds, new WorldsFragment());
        bindRow(binding.navResource, PacksFragment.newInstance(PacksFragment.KIND_RESOURCE));
        bindRow(binding.navBehavior, PacksFragment.newInstance(PacksFragment.KIND_BEHAVIOR));
        bindRow(binding.navGlobal, new GlobalPacksFragment());
        select(binding.navWorlds);
    }

    private void bindRow(View row, Fragment page) {
        row.setOnClickListener(v -> select(row));
        row.setTag(page);
    }

    private void select(View row) {
        if (selectedItem != null) selectedItem.setActivated(false);
        selectedItem = row;
        row.setActivated(true);
        Fragment page = (Fragment) row.getTag();
        getChildFragmentManager().beginTransaction()
                .replace(R.id.content_frame, page)
                .commit();
    }

    @Override
    public void onResume() {
        super.onResume();
        updateAvailability();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
        selectedItem = null;
    }

    /** The sidebar is only meaningful once an instance owns a data root. */
    private void updateAvailability() {
        if (binding == null) return;
        boolean available = GameDataStore.activeRoot(requireContext()) != null;
        binding.sidebar.setVisibility(available ? View.VISIBLE : View.GONE);
        binding.contentFrame.setVisibility(available ? View.VISIBLE : View.GONE);
        binding.textNoInstance.setVisibility(available ? View.GONE : View.VISIBLE);
    }
}
