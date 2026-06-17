
plugins {
    id("com.android.application")
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.answer.launcher"
    compileSdk = 35


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
        minSdk = 24
        targetSdk = 35
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
    buildToolsVersion = "35.0.1"
    kotlinOptions {
        jvmTarget = "11"
    }

}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    
    implementation ("com.github.getActivity:XXPermissions:20.0")
    
    implementation(project(":api"))
    implementation(project(":core"))
    implementation(project(":cpp"))
    
    
    implementation(libs.lsp.hiddenpass)
    implementation (libs.pine.core)
    implementation (libs.weishu.reflection)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.navigation.fragment)
    implementation(libs.androidx.navigation.ui)
    implementation(libs.androidx.activity)

    compileOnly(fileTree(mapOf("dir" to "compile_only", "include" to listOf("*.jar"))))
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))
}
