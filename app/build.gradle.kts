
plugins {
    id("com.android.application")
    
}

android {
    namespace = "com.answer.launcher"
    compileSdk = 33
    ndkVersion = "24.0.8215888"
    defaultConfig {
        applicationId = "com.answer.launcher"
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

    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.9.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    
    implementation ("com.github.getActivity:XXPermissions:20.0")
    
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")
    implementation ("top.canyie.pine:core:0.2.9")
    implementation ("me.weishu:free_reflection:3.0.1")
    
    compileOnly(fileTree(mapOf("dir" to "compile_only", "include" to listOf("*.jar"))))
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))
}
