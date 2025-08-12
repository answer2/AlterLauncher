package com.answer.launcher.utils;


import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import com.answer.launcher.ui.activity.CrashActivity;

import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class CrashHandler {
    public static final Thread.UncaughtExceptionHandler DEFAULT_HANDLER =
            Thread.getDefaultUncaughtExceptionHandler();

    private static CrashHandler instance;
    private PartCrashHandler partHandler;

    private GlobalExceptionHandler handler;

    public static synchronized CrashHandler getInstance() {
        if (instance == null) {
            instance = new CrashHandler();
        }
        return instance;
    }

    public void registerGlobal(Context context) {
        registerGlobal(context, null);
    }

    public void registerGlobal(Context context, String crashDir) {
        handler =  new GlobalExceptionHandler(context, crashDir);
        Thread.setDefaultUncaughtExceptionHandler(handler);
    }

    public void registerPart(Context context) {
        unregisterPart();
        partHandler = new PartCrashHandler(context);
        new Handler(Looper.getMainLooper()).postAtFrontOfQueue(partHandler);
    }

    public void unregisterPart() {
        if (partHandler != null) {
            partHandler.isRunning.set(false);
            partHandler = null;
        }
    }

    private class PartCrashHandler implements Runnable {
        final Context context;
        final AtomicBoolean isRunning = new AtomicBoolean(true);

        PartCrashHandler(Context context) {
            this.context = context;
        }

        @Override
        public void run() {
            while (isRunning.get()) {
                try {
                    Looper.loop();
                } catch (Throwable e) {
                    if (isRunning.get()) {
                        new Handler(Looper.getMainLooper()).post(() ->{
                            Toast.makeText(context, e.toString(), Toast.LENGTH_LONG).show();
                            e.printStackTrace();
                                    handler.saveCrashLog(handler.buildCrashLog(e));
                        }
                        );
                    } else {
                        throw new RuntimeException(e);
                    }
                }
            }
        }
    }

    private static class GlobalExceptionHandler implements Thread.UncaughtExceptionHandler {
        private static final DateFormat DATE_FORMAT =
                new SimpleDateFormat("yyyy_MM_dd-HH_mm_ss");

        private final Context context;
        private final File crashDir;

        GlobalExceptionHandler(Context context, String dirPath) {
            this.context = context.getApplicationContext();
            this.crashDir = TextUtils.isEmpty(dirPath)
                    ? new File(context.getExternalCacheDir(), "crash")
                    : new File(dirPath);

            Log.d("GlobalExceptionHandler", crashDir.getAbsolutePath());
        }

        @Override
        public void uncaughtException(Thread thread, Throwable ex) {
            try {
                String log = buildCrashLog(ex);
                saveCrashLog(log);

                Intent intent = new Intent(context, CrashActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                intent.putExtra("CRASH_LOG", log);
                context.startActivity(intent);

                android.os.Process.killProcess(android.os.Process.myPid());
                System.exit(0);
            } catch (Throwable e) {
                DEFAULT_HANDLER.uncaughtException(thread, ex);
            }
        }

        public String buildCrashLog(Throwable ex) {
            LinkedHashMap<String, String> deviceInfo = new LinkedHashMap<>();
            deviceInfo.put("Time", DATE_FORMAT.format(new Date()));
            deviceInfo.put("Device", Build.MANUFACTURER + ", " + Build.MODEL);
            deviceInfo.put("Android", Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")");
            deviceInfo.put("ABIs", Build.SUPPORTED_ABIS != null
                    ? Arrays.toString(Build.SUPPORTED_ABIS) : "N/A");
            deviceInfo.put("Fingerprint", Build.FINGERPRINT);
            deviceInfo.put("ClassLoader", " ( " + context.getClassLoader() +" ) ");

            StringBuilder log = new StringBuilder();
            for (String key : deviceInfo.keySet()) {
                log.append(key).append(": ").append(deviceInfo.get(key)).append("\n");
            }
            return log.append("\n").append(Log.getStackTraceString(ex)).toString();
        }

        public void saveCrashLog(String log) {
            String fileName = "crash_" + DATE_FORMAT.format(new Date()) + ".txt";
            File file = new File(crashDir, fileName);
            try {
                write(file, log.getBytes("UTF-8"));
            } catch (Throwable e) {
                e.printStackTrace();
            }
        }

        public static void write(File file, byte[] data) throws IOException {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();

            ByteArrayInputStream input = new ByteArrayInputStream(data);
            FileOutputStream output = new FileOutputStream(file);
            try {
                write(input, output);
            } finally {
                closeIO(input, output);
            }
        }

        public static void write(InputStream input, OutputStream output) throws IOException {
            byte[] buf = new byte[1024 * 8];
            int len;
            while ((len = input.read(buf)) != -1) {
                output.write(buf, 0, len);
            }
        }
    }

    public static void closeIO(Closeable... closeables) {
        for (Closeable closeable : closeables) {
            try {
                if (closeable != null) closeable.close();
            } catch (IOException ignored) {}
        }
    }

    public static void crash(){
        int[] numbers = {1, 2, 3}; // 越界访问，触发崩溃
         int outOfBoundsNumber = numbers[5];

    }
}