package com.mojang.minecraftpe.store;

import com.mojang.minecraftpe.store.googleplay.GooglePlayStore;
import com.mojang.minecraftpe.store.amazonappstore.AmazonAppStore;
import com.mojang.minecraftpe.MainActivity;

/**
 * @Author AnswerDev
 * @Date 2024/07/12 21:50
 */
public class StoreFactory {
    
    static Store createGooglePlayStore(String googlePlayLicenseKey, StoreListener storeListener) {
        return new GooglePlayStore(MainActivity.mInstance, googlePlayLicenseKey, storeListener);
    }

    static Store createAmazonAppStore(StoreListener storeListener, boolean forFireTV) {
        return new AmazonAppStore(MainActivity.mInstance, storeListener, forFireTV);
    }
}
