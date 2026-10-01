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
 * and cross-version global packs. Uses a PCL-style left NavigationRail to swap between
 * sub-pages instead of top tabs. Everything here is scoped to the instance that is
 * currently active, because that is what owns {@code games/com.mojang} — so with no
 * active instance the rail is replaced by a hint pointing at 实例.
 */
public class ContentFragment extends Fragment {

    private FragmentContentBinding binding;
    private int selectedId = R.id.nav_worlds;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        binding = FragmentContentBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        binding.navRail.setOnItemSelectedListener(item -> {
            if (item.getItemId() == selectedId) return true;
            selectedId = item.getItemId();
            showPage();
            return true;
        });
        showPage();
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
    }

    private void showPage() {
        if (binding == null) return;
        Fragment child;
        if (selectedId == R.id.nav_resource) {
            child = PacksFragment.newInstance(PacksFragment.KIND_RESOURCE);
        } else if (selectedId == R.id.nav_behavior) {
            child = PacksFragment.newInstance(PacksFragment.KIND_BEHAVIOR);
        } else if (selectedId == R.id.nav_global) {
            child = new GlobalPacksFragment();
        } else {
            child = new WorldsFragment();
        }
        getChildFragmentManager().beginTransaction()
                .replace(R.id.content_frame, child)
                .commit();
    }

    /** The rail is only meaningful once an instance owns a data root. */
    private void updateAvailability() {
        if (binding == null) return;
        boolean available = GameDataStore.activeRoot(requireContext()) != null;
        binding.navRail.setVisibility(available ? View.VISIBLE : View.GONE);
        binding.contentFrame.setVisibility(available ? View.VISIBLE : View.GONE);
        binding.textNoInstance.setVisibility(available ? View.GONE : View.VISIBLE);
    }
}
