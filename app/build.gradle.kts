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
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            // Celulares, tablets e a maioria das TVs Android atuais.
            // Adicione "armeabi-v7a" se quiser rodar em TV box / celular antigo de 32 bits.
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
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
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
