
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

    // Two ABI flavours (issue #45): an Android app runs all its processes at a single bitness,
    // chosen at install from the ABIs the APK ships. So a 64-bit launcher cannot dlopen a 32-bit
    // libminecraftpe.so. arm64 ships arm64-v8a (the everyday build); arm32 ships armeabi-v7a only,
    // installs alongside as com.answer.launcher.x32, and runs 32-bit-only / universal Bedrock
    // builds in its own 32-bit :game process.
    flavorDimensions += "abi"
    productFlavors {
        create("arm64") {
            dimension = "abi"
            ndk { abiFilters.add("arm64-v8a") }
        }
        create("arm32") {
            dimension = "abi"
            applicationIdSuffix = ".x32"
            versionNameSuffix = "-32bit"
            ndk { abiFilters.add("armeabi-v7a") }
            resValue("string", "app_name", "Alter启动器 32位")
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
    // Matched by substring so it still fires once the "abi" flavours prefix the task names
    // (checkArm64DebugAarMetadata, checkArm32ReleaseAarMetadata, …).
    tasks.matching { it.name.contains("AarMetadata") }
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

// The compile-only dumps in app/compile_only are dex2jar output of the guest APK, and AGP
// puts compileOnly jars ahead of the real AAR jars on the javac classpath. Their androidx.*
// copies carry no usable InnerClasses metadata, so a nested lookup such as
// `RecyclerView.Adapter` resolves against the dump and fails to compile. Push the dumps to
// the back: real artifacts then win every shared lookup, and the guest classes the launcher
// reflects on exist nowhere else, so nothing is lost.
tasks.withType<JavaCompile>().configureEach {
    doFirst {
        val dumps = setOf("mc.jar", "mc2.jar", "jetified-mc.jar", "jetified-mc2.jar")
        val entries = classpath.files.toList()
        val moved = entries.filter { dumps.contains(it.name) }
        if (moved.isNotEmpty()) {
            setClasspath(files(entries.filterNot { moved.contains(it) } + moved))
        }
    }
}
