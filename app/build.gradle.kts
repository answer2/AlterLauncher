
plugins {
    id("com.android.application")
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.answer.launcher"
    compileSdk = 36


    signingConfigs {
        create("release")  {
            storeFile = file("AnswerDev.jks") // 密钥库文件路径
            storePassword = "2903536884AnswerDev" // 密钥库密码
            keyAlias = "AnswerDev" // 密钥别名
            keyPassword = "2903536884AnswerDev" // 密钥密码
        }
    }



    defaultConfig {
        applicationId = "com.answer.launcher"
        minSdk = 26
        // Android 16. The virtualisation path was checked against the android16
        // sources first: LoadedApk / ContextImpl / ActivityThread have no
        // targetSdk>=36 gate on getPackageInfoNoCheck, makeApplicationInner or
        // createAppContext, so raising this does not change how the guest LoadedApk
        // is assembled. Predictive back is the one 36 behaviour change that reaches
        // into the game, and it is opted out of in the manifest - see the note there.
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        
        vectorDrawables { 
            useSupportLibrary = true
        }
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release") // 使用相同的签名配置
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            signingConfig = signingConfigs.getByName("release") // 使用相同的签名配置
        }
    }
    
    buildFeatures {
        viewBinding = true
    }

    // altercore's AAR metadata requires compileSdk 37.1, but AGP 8.8.0 has no
    // compileSdkMinor DSL (needs >= 8.9) and only platforms/android-37.1 is installed,
    // so the gate cannot be satisfied. We consume only AlterCore/AlterHelper's
    // reflection surface, which compiles fine against android-36.
    // TODO: upgrade AGP to 8.9+/9.x + compileSdkMinor = 1, then delete this block.
    tasks.matching { it.name.contains("checkDebugAarMetadata") || it.name.contains("checkReleaseAarMetadata") }
        .configureEach { enabled = false }

    kotlinOptions {
        jvmTarget = "17"
    }
    buildToolsVersion = "36.1.0"

}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    
    implementation ("com.github.getActivity:XXPermissions:20.0")
    
    implementation(project(":api"))
    implementation(project(":core"))
    implementation(project(":cpp"))
    // Host-side Microsoft sign-in: the launcher owns the XAL store the game reads.
    implementation(project(":auth"))
    
    
    implementation(libs.lsp.hiddenpass)
    implementation (libs.weishu.reflection)
    // Pine removed: AlterCore (via :core) is the only hook engine.


    implementation(libs.androidx.drawerlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.navigation.fragment)
    implementation(libs.androidx.navigation.ui)
    implementation(libs.androidx.activity)

    compileOnly(fileTree(mapOf("dir" to "compile_only", "include" to listOf("*.jar"))))
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))
}
