
plugins {
    id("com.android.library")
    
}

android {
    namespace = "com.answer.launcher.cpp"
    compileSdk = 35
    ndkVersion = "24.0.8215888"
    defaultConfig {
        minSdk = 24
        lint.targetSdk  = 35
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildTypes {
        all {
            externalNativeBuild {
                cmake {
                    abiFilters("arm64-v8a", "armeabi-v7a")
                    cppFlags += "-std=c++17"
                }
            }
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    buildToolsVersion = "35.0.1"
}

dependencies {

}
