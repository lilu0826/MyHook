plugins { id("com.android.application") }
android {
    namespace = "dev.josh.myhook"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.josh.myhook"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "0.2.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes { release { isMinifyEnabled = false } }
}
dependencies { compileOnly("io.github.libxposed:api:102.0.0") }
