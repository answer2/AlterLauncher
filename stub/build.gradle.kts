
plugins {
    id("com.android.library")
}

android {
    namespace = "com.answer.launcher.stub"
    compileSdk = 36
    defaultConfig {
        minSdk = 26
        lint.targetSdk  = 36
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildToolsVersion = "36.1.0"

}

dependencies {

}
