
plugins {
    id("com.android.library")
    
}

android {
    namespace = "com.answer.api"
    compileSdk = 33
    defaultConfig {
        minSdk = 24
        targetSdk = 33
        
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

    
}

dependencies {
    compileOnly(project(":stub"))
}
