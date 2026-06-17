//
// Decompiled by Jadx - 559ms
//
package com.mojang.minecraftpe;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

    public class BatteryMonitor extends BroadcastReceiver {
        private final Context mContext;
        public int mBatteryLevel = -1;
        public int mBatteryScale = -1;
        public int mBatteryStatus = -1;
        private int mBatteryTemperature = -1;

        public BatteryMonitor( Context context) {
            mContext = context;
            context.registerReceiver(this, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        }

        @Override
        public void finalize() {
            mContext.unregisterReceiver(this);
        }

        public int getBatteryLevel() {
            return mBatteryLevel;
        }

        public int getBatteryScale() {
            return mBatteryScale;
        }

        public int getBatteryStatus() {
            return mBatteryStatus;
        }

        public int getBatteryTemperature() {
            return mBatteryTemperature;
        }

        public void onReceive(Context context, Intent intent) {
            mBatteryLevel = intent.getIntExtra("level", -1);
            mBatteryScale = intent.getIntExtra("scale", -1);
            mBatteryStatus = intent.getIntExtra("category_status", -1);
            mBatteryTemperature = intent.getIntExtra("temperature", -1);
        }
}
