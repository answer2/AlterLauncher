package com.answer.launcher.core.tool;

import android.content.ContentProviderClient;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Build.VERSION;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.SystemClock;

public class ContentProviderCompat {

    //调用 ContextProviders
    
    /**
     * Call a provider-defined method.  This can be used to implement
     * read or write interfaces which are cheaper than using a Cursor and/or
     * do not fit into the traditional table model.
     *
     * @param method provider-defined method name to call.  Opaque to
     *   framework, but must be non-null.
     * @param arg provider-defined String argument.  May be null.
     * @param extras provider-defined Bundle argument.  May be null.
     * @return a result Bundle, possibly null.  Will be null if the ContentProvider
     *   does not implement call.
     * @throws NullPointerException if uri or method is null
     * @throws IllegalArgumentException if uri is not known
     */
    public static Bundle call(Context context, Uri uri, String method, String arg, Bundle extras, int retryCount) throws IllegalAccessException {
        if (VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR1) {
            //似乎有验证 authority
            // by Android 14
            return context.getContentResolver().call(uri, method, arg, extras);
        }
        
        ContentProviderClient client = acquireContentProviderClientRetry(context, uri, retryCount);
        try {
            if (client == null) {
                throw new IllegalAccessException();
            }
            return client.call(method, arg, extras);
        } catch (RemoteException e) {
            // Arbitrary and not worth documenting, as Activity
            // Manager will kill this process shortly anyway.
            throw new IllegalAccessException(e.getMessage());
        } finally {
            releaseQuietly(client);
        }
    }

    private static ContentProviderClient acquireContentProviderClient(Context context, Uri uri) {
        try {
            if (VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                return context.getContentResolver().acquireUnstableContentProviderClient(uri);
            }
            return context.getContentResolver().acquireContentProviderClient(uri);
        } catch (SecurityException e) {
            e.printStackTrace();
        }
        return null;
    }

    public static ContentProviderClient acquireContentProviderClientRetry(Context context, Uri uri, int retryCount) {
        ContentProviderClient client = acquireContentProviderClient(context, uri);
        if (client == null) {
            int retry = 0;
            while (retry < retryCount && client == null) {
                SystemClock.sleep(100);
                retry++;
                client = acquireContentProviderClient(context, uri);
            }
        }
        return client;
    }

    public static ContentProviderClient acquireContentProviderClientRetry(Context context, String name, int retryCount) {
        ContentProviderClient client = acquireContentProviderClient(context, name);
        if (client == null) {
            int retry = 0;
            while (retry < retryCount && client == null) {
                SystemClock.sleep(100);
                retry++;
                client = acquireContentProviderClient(context, name);
            }
        }
        return client;
    }

    private static ContentProviderClient acquireContentProviderClient(Context context, String name) {
        if (VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            return context.getContentResolver().acquireUnstableContentProviderClient(name);
        }
        return context.getContentResolver().acquireContentProviderClient(name);
    }

    //close
    private static void releaseQuietly(ContentProviderClient client) {
        if (client != null) {
            try {
                if (VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    client.close();
                } else {
                    client.release();
                }
            } catch (Exception ignored) {
            }
        }
    }
}