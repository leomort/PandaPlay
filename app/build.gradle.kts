plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.pandaplay.emu"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.pandaplay.emu"
        minSdk = 24
        targetSdk = 34
        // Cada compilação do GitHub Actions ganha um número maior (atualização normal no Android)
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = "2.0." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")

        ndk {
            // Celulares, tablets e a maioria das TVs Android atuais.
            // Adicione "armeabi-v7a" se quiser rodar em TV box / celular antigo de 32 bits.
            abiFilters += listOf("arm64-v8a")
        }
    }

    // Chave de assinatura FIXA: sem ela cada compilação sai com uma chave diferente
    // e o Android se recusa a atualizar ("conflito com pacote existente").
    // O GitHub Actions cria o arquivo a partir do secret DEBUG_KEYSTORE_BASE64.
    val keystorePath = System.getenv("PANDAPLAY_KEYSTORE")
    signingConfigs {
        if (keystorePath != null && file(keystorePath).exists()) {
            create("pandaplay") {
                storeFile = file(keystorePath)
                storeType = "pkcs12"
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        debug {
            signingConfigs.findByName("pandaplay")?.let { signingConfig = it }
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        jniLibs {
            // Extrai as .so para nativeLibraryDir, assim carregamos o core pelo caminho completo.
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation("com.github.swordfish90:libretrodroid:0.14.0")
    implementation("com.github.swordfish90:radialgamepad:08d1dd95")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
