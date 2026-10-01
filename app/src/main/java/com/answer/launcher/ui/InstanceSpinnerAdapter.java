package com.answer.launcher.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import com.answer.launcher.R;
import com.google.android.material.imageview.ShapeableImageView;

import java.util.ArrayList;
import java.util.List;

/**
 * The rows of the instance dropdowns (启动页 version picker, 内容页 instance picker). Both screens
 * pick an installation the same way — an icon plus a one-line name — so they share this adapter
 * instead of each carrying its own. A dropdown, not a dialog: the selection is a list the player
 * scrolls, and the chosen entry stays visible in the collapsed widget.
 */
public class InstanceSpinnerAdapter extends BaseAdapter {

    /** One selectable row. {@code id} is what the caller maps back to (instance id, or null for the
     *  synthetic "system install" the launcher offers on the launch page). */
    public static final class Item {
        public final String id;
        public final String label;
        public final int iconRes;

        public Item(String id, String label, int iconRes) {
            this.id = id;
            this.label = label;
            this.iconRes = iconRes;
        }
    }

    private final Context context;
    private final List<Item> items = new ArrayList<>();

    public InstanceSpinnerAdapter(Context context) {
        this.context = context;
    }

    public void setItems(List<Item> next) {
        items.clear();
        items.addAll(next);
        notifyDataSetChanged();
    }

    /** Index of the row whose id matches {@code id}, or 0 when nothing matches. */
    public int indexOfId(String id) {
        if (id != null) {
            for (int i = 0; i < items.size(); i++) {
                if (id.equals(items.get(i).id)) return i;
            }
        }
        return 0;
    }

    public Item getItemAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position) : null;
    }

    @Override
    public int getCount() {
        return items.size();
    }

    @Override
    public Item getItem(int position) {
        return items.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        return bind(position, convertView, parent);
    }

    @Override
    public View getDropDownView(int position, View convertView, ViewGroup parent) {
        return bind(position, convertView, parent);
    }

    private View bind(int position, View convertView, ViewGroup parent) {
        View v = convertView != null
                ? convertView
                : LayoutInflater.from(context).inflate(R.layout.item_spinner_instance, parent, false);
        Item item = items.get(position);
        ShapeableImageView icon = v.findViewById(R.id.spinner_icon);
        icon.setImageResource(item.iconRes);
        ((TextView) v.findViewById(R.id.spinner_label)).setText(item.label);
        return v;
    }
}
