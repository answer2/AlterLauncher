
plugins {
    id("com.android.library")
    
}

android {
    namespace = "com.answer.api"
    compileSdk = 35

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildTypes {
    release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    buildToolsVersion = "35.0.1"


}

dependencies {
    compileOnly(project(":stub"))
}
