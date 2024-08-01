
plugins {
    id("com.android.application")
}

android {
    namespace = "com.answer.launcher"
    compileSdk = 33
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

    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.9.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    
    implementation ("com.github.getActivity:XXPermissions:20.0")
    
    implementation(project(":core"))
    implementation(project(":cpp"))
    implementation(project(":api"))
    
    implementation(libs.lsp.hiddenpass)
    implementation (libs.pine.core)
    implementation (libs.weishu.reflection)
    
    compileOnly(fileTree(mapOf("dir" to "compile_only", "include" to listOf("*.jar"))))
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))
}
