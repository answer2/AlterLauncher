
plugins {
    id("com.android.library")
}

android {
    namespace = "com.answer.launcher.constants"
    compileSdk = 33
    defaultConfig {
        minSdk = 24
        targetSdk = 33
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildToolsVersion = "35.0.1"

}

dependencies {

}
