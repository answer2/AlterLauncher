
plugins {
    id("com.android.library")
    
}

android {
    namespace = "com.answer.api"
    compileSdk = 36

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
    release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    buildToolsVersion = "36.1.0"


}

dependencies {
    compileOnly(project(":stub"))
}
