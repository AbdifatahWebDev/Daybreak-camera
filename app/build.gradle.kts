plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// CI passes the release version (-PdaybreakVersionName=0.1.0 -PdaybreakVersionCode=100).
// Local builds use the VERSION file at the repository root.
val daybreakVersionName: String = providers.gradleProperty("daybreakVersionName")
    .orElse(
        providers.fileContents(rootProject.layout.projectDirectory.file("VERSION"))
            .asText.map { it.trim() + "-dev" },
    )
    .getOrElse("0.0.0-dev")
val daybreakVersionCode = providers.gradleProperty("daybreakVersionCode").getOrElse("1").toInt()

// The GitHub build updates itself from this repository's releases.
val daybreakUpdateRepo: String = providers.gradleProperty("daybreakUpdateRepo")
    .getOrElse("AbdifatahWebDev/Daybreak-camera")

// Release signing comes from environment variables set by CI from repository secrets.
val keystorePath: String? = System.getenv("DAYBREAK_KEYSTORE")
val hasReleaseKey = keystorePath != null && file(keystorePath).exists()

android {
    namespace = "com.brekwol.daybreak"
    compileSdk = 37
    // Must match :core:engine. The app module strips debug symbols from the
    // native libraries it packages, and needs this NDK to do it.
    ndkVersion = "30.0.16248370"

    defaultConfig {
        applicationId = "com.brekwol.daybreak"
        minSdk = 29
        targetSdk = 37
        versionCode = daybreakVersionCode
        versionName = daybreakVersionName
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    // github: the APK on GitHub Releases, which updates itself.
    // store:  for Google Play and F-Droid, which update apps themselves.
    flavorDimensions += "distribution"
    productFlavors {
        create("github") {
            dimension = "distribution"
            buildConfigField("boolean", "UPDATER", "true")
            buildConfigField("String", "UPDATE_REPO", "\"$daybreakUpdateRepo\"")
        }
        create("store") {
            dimension = "distribution"
            buildConfigField("boolean", "UPDATER", "false")
            buildConfigField("String", "UPDATE_REPO", "\"\"")
        }
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(keystorePath!!)
                storePassword = System.getenv("DAYBREAK_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("DAYBREAK_KEY_ALIAS")
                keyPassword = System.getenv("DAYBREAK_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = if (hasReleaseKey) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
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

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":core:designsystem"))
    implementation(project(":core:engine"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}
