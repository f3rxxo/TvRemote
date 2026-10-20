plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.protobuf")
}

android {
    namespace = "com.kiko.tvremote"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.kiko.tvremote"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            // Debug-signed so GitHub Actions can produce an installable APK
            // without needing a signing key checked into the repo.
            signingConfig = signingConfigs.getByName("debug")
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
    }
    composeOptions {
        // Matches Kotlin 1.9.24.
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    // The three BouncyCastle jars each ship the same META-INF files, which
    // makes mergeDebugJavaResource fail with "N files found with path".
    packaging {
        resources {
            excludes += setOf(
                "META-INF/versions/**",
                "META-INF/MANIFEST.MF",
                "META-INF/*.SF",
                "META-INF/*.DSA",
                "META-INF/*.RSA",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1"
            )
        }
    }
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.25.3"
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                create("java") {
                    option("lite")
                }
            }
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Jetpack Compose + Material 3
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")

    // Wire format only, no android.util/android.os deps - keeps codegen simple.
    implementation("com.google.protobuf:protobuf-javalite:3.25.3")

    // X.509 self-signed certificate generation. The Android/JDK standard
    // library has no public API for building a certificate from scratch;
    // this is the standard, well-supported way to do it.
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")
}
