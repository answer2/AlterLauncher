
plugins {
    id("com.android.library")
    
}

android {
    namespace = "com.answer.launcher.core"
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
    implementation(libs.lsp.hiddenpass)
    implementation (libs.pine.core)
    implementation (libs.weishu.reflection)
    
    implementation(project(":api"))
    compileOnly(project(":stub"))
    
    implementation(libs.androidx.annotation)
   
    compileOnly(fileTree(mapOf("dir" to "compile_only", "include" to listOf("*.jar"))))
}
