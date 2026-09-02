plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.rektide.batsignal"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.rektide.batsignal"
        // minSdk 26: BluetoothLeAdvertiser.startAdvertisingSet (the extended
        // advertising API the beacon will use) was added in API 26; there is no
        // reason to run on anything older.
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    // Use the system-installed build-tools rather than letting AGP fetch its
    // default; /usr/lib/android-sdk only ships 36.0.0.
    buildToolsVersion = "36.0.0"

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
}
