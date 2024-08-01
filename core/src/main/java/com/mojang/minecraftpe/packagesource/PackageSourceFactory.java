package com.mojang.minecraftpe.packagesource;

/**
 * @Author AnswerDev
 * @Date 2024/07/12 21:57
 */
public class PackageSourceFactory {
    static PackageSource createGooglePlayPackageSource(String googlePlayLicenseKey, PackageSourceListener packageSourceListener) {
        return new StubPackageSource(packageSourceListener);
    }
}
