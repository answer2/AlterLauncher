
plugins {
    id("com.android.application")
    
}

android {
    namespace = "com.answer.launcher"
    compileSdk = 33
    defaultConfig {
        applicationId = "com.answer.launcher.core"
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
            version = "3.18.1"
        }
    }

    buildFeatures {
        viewBinding = true
    }
    
}

dependencies {
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")
    implementation ("top.canyie.pine:core:0.2.9")
    implementation ("me.weishu:free_reflection:3.0.1")
    compileOnly(project(":app"))
    compileOnly(fileTree(mapOf("dir" to "compile_only", "include" to listOf("*.jar"))))
}
