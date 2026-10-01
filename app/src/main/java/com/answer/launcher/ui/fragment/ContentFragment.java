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
import com.google.android.material.tabs.TabLayoutMediator;

/**
 * 内容 (task #18/#19): the entry point for worlds, resource packs and behaviour packs.
 * Everything here is scoped to the instance that is currently active, because that is
 * what owns {@code games/com.mojang} — so with no active instance the pager is replaced
 * by a hint pointing at 实例 instead of an empty list.
 */
public class ContentFragment extends Fragment {

    private FragmentContentBinding binding;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        binding = FragmentContentBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        binding.pager.setAdapter(new ContentPagerAdapter(requireActivity()));
        new TabLayoutMediator(binding.tabLayout, binding.pager, (tab, position) ->
                tab.setText(tabTitle(position))).attach();
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

    private String tabTitle(int position) {
        switch (position) {
            case ContentPagerAdapter.PAGE_RESOURCE:
                return getString(R.string.content_tab_resource);
            case ContentPagerAdapter.PAGE_BEHAVIOR:
                return getString(R.string.content_tab_behavior);
            case ContentPagerAdapter.PAGE_WORLDS:
            default:
                return getString(R.string.content_tab_worlds);
        }
    }

    /** The pager is only meaningful once an instance owns a data root. */
    private void updateAvailability() {
        if (binding == null) return;
        boolean available = GameDataStore.activeRoot(requireContext()) != null;
        binding.pager.setVisibility(available ? View.VISIBLE : View.GONE);
        binding.tabLayout.setVisibility(available ? View.VISIBLE : View.GONE);
        binding.textNoInstance.setVisibility(available ? View.GONE : View.VISIBLE);
    }
}
