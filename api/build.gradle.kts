
plugins {
    id("com.android.application")
    
}

android {
    namespace = "com.answer.launcher"
    compileSdk = 33
    defaultConfig {
        applicationId = "com.answer.launcher.api"
        minSdk = 24
        targetSdk = 33
        versionCode = 1
        versionName = "1.0"
        
        vectorDrawables { 
            useSupportLibrary = true
        }
    }
    
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

    buildFeatures {
        viewBinding = true
    }
    
}

dependencies {
    compileOnly(project(":core"))
}
