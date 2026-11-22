import groovy.json.JsonSlurper

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

/*
 * One version for the whole project: "version" in ../../buildspec.json (the OBS plugin's
 * build spec). versionCode is derived from it: MAJOR * 10000 + MINOR * 100 + PATCH, so
 * 1.4.2 -> 10402. A pre-release suffix (0.2.0-beta1) keeps the same code as its release.
 */
val buildspec = JsonSlurper().parse(rootProject.file("../buildspec.json")) as Map<*, *>
val appVersion = buildspec["version"] as String
val versionParts = appVersion.substringBefore('-').split('.').map { it.toInt() }
val appVersionCode = versionParts[0] * 10000 + versionParts[1] * 100 + versionParts[2]

/*
 * Release signing: the app's one permanent key, from CI secrets. Updates install over the old
 * version only when both are signed with the same key, so a release is never signed with a
 * throwaway debug key: without the key the release APK is left unsigned, and CI refuses to
 * publish it (see .github/workflows/android.yaml).
 */
val keystorePath: String? = System.getenv("ANDROID_KEYSTORE_PATH")
val hasReleaseKey = !keystorePath.isNullOrBlank() && file(keystorePath).exists()

android {
    namespace = "dev.sharptalons.obsdirector"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.sharptalons.obsdirector"
        minSdk = 29
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersion
        buildConfigField("String", "GITHUB_REPO", "\"SharpTalons/OBS-Director\"")
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(keystorePath!!)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseKey) signingConfigs.getByName("release") else null
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
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
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}
