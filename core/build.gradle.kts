
plugins {
    id("com.android.library")
    
}

android {
    namespace = "com.answer.launcher.core"
    compileSdk = 35
    defaultConfig {
        minSdk = 24
        lint.targetSdk = 35
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    buildToolsVersion = "35.0.1"


}

dependencies {
    implementation(libs.lsp.hiddenpass)
    implementation (libs.pine.core)
    implementation (libs.weishu.reflection)
    
    implementation(project(":api"))
    compileOnly(project(":stub"))
    
    implementation(libs.androidx.annotation)

    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))

    compileOnly(fileTree(mapOf("dir" to "compile_only", "include" to listOf("*.jar"))))
}
