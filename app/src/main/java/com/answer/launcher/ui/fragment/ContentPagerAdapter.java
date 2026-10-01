package com.answer.launcher.ui.fragment;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;

/**
 * The four content tabs (task #18/#19/#20). Fragments are created lazily by position,
 * so a rotation/restore rebuilds them through the same factory rather than through a
 * pre-built list — the state adapter owns the instance lifecycle.
 */
public class ContentPagerAdapter extends FragmentStateAdapter {

    public static final int PAGE_COUNT = 4;
    public static final int PAGE_WORLDS = 0;
    public static final int PAGE_RESOURCE = 1;
    public static final int PAGE_BEHAVIOR = 2;
    public static final int PAGE_GLOBAL = 3;

    public ContentPagerAdapter(@NonNull FragmentActivity activity) {
        super(activity);
    }

    @NonNull
    @Override
    public Fragment createFragment(int position) {
        switch (position) {
            case PAGE_RESOURCE:
                return PacksFragment.newInstance(PacksFragment.KIND_RESOURCE);
            case PAGE_BEHAVIOR:
                return PacksFragment.newInstance(PacksFragment.KIND_BEHAVIOR);
            case PAGE_GLOBAL:
                return new GlobalPacksFragment();
            case PAGE_WORLDS:
            default:
                return new WorldsFragment();
        }
    }

    @Override
    public int getItemCount() {
        return PAGE_COUNT;
    }
}
