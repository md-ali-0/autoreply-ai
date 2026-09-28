plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

import java.util.Properties

android {
    namespace = "com.claw.autoreplyai"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.claw.autoreplyai"
        minSdk = 28
        targetSdk = 35
        versionCode = 57
        versionName = "1.52.6"
    }

    /**
     * Release signing reads from keystore.properties, which is deliberately NOT in
     * version control (see .gitignore) — a keystore and its passwords must never be
     * committed. When the file is absent the release build falls back to the debug
     * key so `assembleRelease` still runs on a fresh clone; the build prints a loud
     * warning in that case, because a debug-signed release is not distributable and
     * is NOT debuggable-safe.
     */
    val keystorePropsFile = rootProject.file("keystore.properties")
    val hasReleaseKeystore = keystorePropsFile.exists()

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                val props = Properties().apply {
                    keystorePropsFile.inputStream().use { load(it) }
                }
                storeFile = file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // Debugging stays exactly as it was: debuggable, so the live state can be
            // inspected with `adb shell run-as` while tuning on a real device.
            isMinifyEnabled = false
            isDebuggable = true
        }

        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // Never debuggable, and never signed with the shared debug key. The debug
            // key is public — anyone can sign a "release" with it, so shipping that way
            // means the app can be impersonated and its data read over adb.
            isDebuggable = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                logger.warn(
                    "⚠️  keystore.properties পাওয়া যায়নি — release বিল্ড debug key দিয়ে " +
                            "সাইন হচ্ছে। এটা বিতরণের জন্য নয়।"
                )
                signingConfig = signingConfigs.getByName("debug")
            }
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
        viewBinding = true
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
