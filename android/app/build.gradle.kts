plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// ---- Firma release desde variables de entorno / GitHub Secrets ----
//
// Convención: el keystore se entrega como base64 en `ANDROID_KEYSTORE_BASE64`
// (env var local o secret de CI). En CI, el workflow lo decodifica a
// `~/.gradle/agent-native.keystore` antes de compilar. En local, puedes
// apuntar `KEYSTORE_FILE` al keystore real directamente.
//
// Sin estas variables, el build release NO firma (Gradle avisa en el log)
// pero el debug sigue funcionando igual.
val keystoreFile: File? = listOfNotNull(
    System.getenv("KEYSTORE_FILE"),
    File(System.getProperty("user.home"), ".gradle/agent-native.keystore").absolutePath,
).map(::File).firstOrNull { it.exists() }

val keystorePwd: String? = System.getenv("KEYSTORE_PASSWORD")
val keyAlias: String? = System.getenv("KEY_ALIAS") ?: "agent-native"
val keyPwd: String? = System.getenv("KEY_PASSWORD")
val hasSigning = keystoreFile != null && keystorePwd != null && keyPwd != null

android {
    namespace = "com.example.agentnative"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.agentnative"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // Debe coincidir con los ABIs que genera scripts/build_rust_android.sh.
            // En CI los .so se compilan antes de esta tarea y Gradle los empaqueta
            // desde android/app/src/main/jniLibs/.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    // Splits por ABI: genera un APK por arquitectura (mucho más pequeño que el
    // universal, que incluye los 3 .so). Cada APK contiene solo su librería.
    // El APK universal sigue generándose para `assembleDebug` por si se necesita.
    //
    // Nota para publicar en Play Console con varios APKs: hay que asignar un
    // versionCode único por split. Convenio sugerido:
    //   universal=1, arm64-v8a=2, armeabi-v7a=3, x86_64=4
    // (o `base*1000 + multiplier`) vía `androidComponents.onVariants` cuando
    // se suba a release; no es necesario para el artifact debug de CI.
    splits {
        abi {
            isEnable = true
            reset()
            // Mismo set que abiFilters y que build_rust_android.sh.
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    signingConfigs {
        if (hasSigning && keystoreFile != null) {
            create("release") {
                storeFile = keystoreFile
                storePassword = keystorePwd
                keyAlias = keyAlias
                keyPassword = keyPwd
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasSigning) {
                signingConfig = signingConfigs.getByName("release")
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
        compose = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // --- Tests de UI Compose (instrumentados, corren en emulador/dispositivo) ---
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
