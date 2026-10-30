import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    // one APK per processor type: the on-phone voice engine is large, and a phone only needs its own
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }
    // keep native libraries compressed inside the APK (they are unpacked once on install)
    packaging { jniLibs { useLegacyPackaging = true } }
    namespace = "com.werd.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.werd.app"
        // Library & family server. Empty = those features show "coming soon" (can also be set in Settings).
        buildConfigField("String", "SERVER_URL", "\"${(project.findProperty("werdServerUrl") as String?) ?: ""}\"")
        minSdk = 26
        targetSdk = 36
        versionCode = 18
        versionName = "1.15"
        // Phones are ARM (64- and 32-bit); keeps the OCR native libraries small
        // one APK per processor type (see splits below)
    }

    // Release signing: the key and its passwords never live in the repo. They come from environment variables
    // (CI: GitHub secrets) or from ~/.gradle/gradle.properties on a developer's machine:
    //   WERD_KEYSTORE_FILE / werdKeystoreFile      path to werd-release.jks (default: app/werd-release.jks, git-ignored)
    //   WERD_KEYSTORE_PASSWORD / werdKeystorePassword
    //   WERD_KEY_ALIAS / werdKeyAlias              (default "werd")
    //   WERD_KEY_PASSWORD / werdKeyPassword
    // Without them a release build is left unsigned. Updates only install over the existing app when signed with the
    // same key (SHA-256 13:f3:d0:dc…), so never make a new one.
    fun secret(env: String, prop: String): String? =
        System.getenv(env)?.takeIf { it.isNotBlank() } ?: (project.findProperty(prop) as String?)?.takeIf { it.isNotBlank() }
    val keystore = file(secret("WERD_KEYSTORE_FILE", "werdKeystoreFile") ?: "werd-release.jks")
    val storePass = secret("WERD_KEYSTORE_PASSWORD", "werdKeystorePassword")
    val canSign = keystore.exists() && storePass != null
    signingConfigs {
        if (canSign) create("release") {
            storeFile = keystore
            storePassword = storePass
            keyAlias = secret("WERD_KEY_ALIAS", "werdKeyAlias") ?: "werd"
            keyPassword = secret("WERD_KEY_PASSWORD", "werdKeyPassword") ?: storePass
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (canSign) signingConfig = signingConfigs.getByName("release")
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

    androidResources {
        // the OCR model is opened by file descriptor to check its size
        noCompress += "traineddata"
    }

    sourceSets {
        // database schema history, for migration tests
        getByName("debug").assets.srcDir("$projectDir/schemas")   // debug builds only
    }

    lint {
        // Format args are %s on purpose so numbers always render with Western digits (matching the counters).
        disable += "StringFormatMatches"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.systemProperty("roborazzi.test.record", "true")
            // -PwerdTestServer=http://127.0.0.1:8080 runs RealServerTest against a running werd-server
            providers.gradleProperty("werdTestServer").orNull?.let { url -> it.systemProperty("werd.server", url) }
            it.maxHeapSize = "3g"
            // Optional: -Probolectric.dir=/path/to/jars to run screenshot tests offline
            providers.gradleProperty("robolectric.dir").orNull?.let { dir ->
                it.systemProperty("robolectric.offline", "true")
                it.systemProperty("robolectric.dependency.dir", dir)
            }
        }
    }
}

ksp {
    // Room writes each database version's schema here; used for safe auto-migrations
    arg("room.schemaLocation", "$projectDir/schemas")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.navigation:navigation-compose:2.9.6")

    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")

    implementation("androidx.glance:glance-appwidget:1.1.1")

    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    implementation("cz.adaptech.tesseract4android:tesseract4android:4.9.0")
    implementation("androidx.exifinterface:exifinterface:1.4.1")
    implementation("androidx.work:work-runtime-ktx:2.11.0")
    implementation("androidx.media:media:1.7.1")
    // on-phone natural voice (Piper models run by sherpa-onnx; the voice itself is downloaded in the app)
    implementation(files("libs/sherpa-onnx-1.13.8.aar"))
    implementation("org.apache.commons:commons-compress:1.27.1")
    // Gemini Nano (on-device, only on phones with Android AICore) for reading pages
    implementation("com.google.mlkit:genai-prompt:1.0.0-beta4")

    testImplementation("junit:junit:4.13.2")
    // Headless screenshot tests of the real screens (test-only, not shipped)
    testImplementation(composeBom)
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.50.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.50.0")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.test:core-ktx:1.6.1")
    testImplementation("androidx.room:room-testing:2.8.4")
    testImplementation("androidx.work:work-testing:2.11.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
