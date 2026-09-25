
plugins {
    id("com.android.library")
    
}

android {
    namespace = "com.answer.launcher.core"
    compileSdk = 36
    defaultConfig {
        minSdk = 26
        lint.targetSdk = 35
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    buildToolsVersion = "36.1.0"

    // The :game <-> UI bridge (IGameBridge.aidl / IGameBridgeCallback.aidl).
    buildFeatures {
        aidl = true
    }

}

dependencies {
    implementation(libs.lsp.hiddenpass)
    implementation (libs.weishu.reflection)

    // AlterCore is the process' only hook engine; Pine and Pina (a Pine
    // wrapper) were removed in the engine convergence.
    implementation(libs.altercore)

    implementation(project(":api"))
    compileOnly(project(":stub"))
    
    implementation(libs.androidx.annotation)

    implementation(libs.conscrypt.android)
    implementation(libs.androidx.games.activity)

    compileOnly(fileTree(mapOf("dir" to "compile_only", "include" to listOf("*.jar"))))
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))

}
