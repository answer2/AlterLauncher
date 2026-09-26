
plugins {
    id("com.android.library")
}

android {
    namespace = "com.answer.launcher.auth"
    compileSdk = 36
    defaultConfig {
        minSdk = 26
        lint.targetSdk = 36
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildToolsVersion = "36.1.0"

}

dependencies {
    // LGPL-3.0, shipped as its own module so the licence stays separable; see NOTICE.
    // It drags gson + net.lenni0451.commons along with it.
    api(libs.minecraft.auth)
}
