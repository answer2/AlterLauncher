
plugins {
    id("com.android.library")
}

android {
    namespace = "com.answer.launcher.stub"
    compileSdk = 35
    defaultConfig {
        minSdk = 24
        lint.targetSdk  = 35
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildToolsVersion = "35.0.1"

}

dependencies {

}
