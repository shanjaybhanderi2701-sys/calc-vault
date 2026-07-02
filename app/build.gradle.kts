plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ktlint)
}

android {
    namespace = "com.appblish.calculatorvault"
    compileSdk = libs.versions.compileSdk
        .get()
        .toInt()

    defaultConfig {
        applicationId = "com.appblish.calculatorvault"
        minSdk = libs.versions.minSdk
            .get()
            .toInt()
        targetSdk = libs.versions.targetSdk
            .get()
            .toInt()
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        // The Compose runtime check mis-fires when produceState assigns `value` from the
        // result of a suspend call (e.g. `value = repo.load()`), reporting it as unassigned.
        // The value IS assigned inside the producer at every call site; this is a known
        // false positive, so we opt out of the check rather than obscure the code.
        disable += "ProduceStateDoesNotAssignValue"
    }

    sourceSets["main"].java.srcDir("src/main/kotlin")
    sourceSets["test"].java.srcDir("src/test/kotlin")
}

ktlint {
    // Pin the ktlint engine: the plugin's default (1.0.1) has a rule-loading bug
    // (string-template-indent depends on an unloaded multiline-expression-wrapping rule)
    // that crashes ktlintCheck. 1.4.1 loads the standard ruleset cleanly.
    version.set("1.4.1")
    android.set(true)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)

    // EncryptedSharedPreferences / MasterKey — backing for the vault storage interface.
    implementation(libs.androidx.security.crypto)

    // AppLock (Phase 3): CameraX for the Intruder Selfie, Biometric for the lock method.
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.biometric)

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    // Instrumented-test runner + JUnit4 hooks for the on-device survive-uninstall /
    // PIN-recovery test that proves the public .CalcVault/ storage gate (APP-169) and the
    // un-hide / restore-to-gallery write-back (APP-170).
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation(libs.truth)
}
