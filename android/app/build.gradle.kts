import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Стабільний ключ підпису Track Catcher.
// CI: змінні середовища TC_* (із секретів GitHub).
// Телефон: ~/.android/track-catcher-signing.properties (створює меню, пункт 7).
// Без ключа збирається лише непідписаний APK — його не публікуємо.
val phoneSigningFile = File(System.getProperty("user.home"), ".android/track-catcher-signing.properties")
val phoneSigning = Properties().apply {
    if (phoneSigningFile.isFile) phoneSigningFile.inputStream().use { load(it) }
}

fun signingValue(env: String, prop: String): String? =
    providers.environmentVariable(env).orNull?.takeIf { it.isNotBlank() }
        ?: phoneSigning.getProperty(prop)?.takeIf { it.isNotBlank() }

val tcKeystoreFile = signingValue("TC_KEYSTORE_PATH", "storeFile")?.let { file(it) }
val tcStorePassword = signingValue("TC_STORE_PASSWORD", "storePassword")
val tcKeyAlias = signingValue("TC_KEY_ALIAS", "keyAlias")
val tcKeyPassword = signingValue("TC_KEY_PASSWORD", "keyPassword")
val tcSigningAvailable = tcKeystoreFile?.isFile == true &&
    !tcStorePassword.isNullOrBlank() && !tcKeyAlias.isNullOrBlank() && !tcKeyPassword.isNullOrBlank()

android {
    namespace = "ua.faric.trackcatcher"
    compileSdk = 36

    defaultConfig {
        applicationId = "ua.faric.trackcatcher"
        // Запис звуку інших застосунків (AudioPlaybackCapture) є лише з Android 10.
        minSdk = 29
        targetSdk = 36
        versionCode = 22
        versionName = "0.1.21"
    }

    signingConfigs {
        create("stable") {
            if (tcSigningAvailable) {
                storeFile = tcKeystoreFile
                storePassword = tcStorePassword
                keyAlias = tcKeyAlias
                keyPassword = tcKeyPassword
            }
        }
    }

    buildTypes {
        // Робоча збірка для телефона — release без мініфікації:
        // не debuggable, тож MP3 кодується швидко.
        release {
            isMinifyEnabled = false
            if (tcSigningAvailable) {
                signingConfig = signingConfigs.getByName("stable")
            }
        }
        debug {
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    // Інструкція користувача: сторінка з assets через https-адресу (картинки з file:// не вантажились).
    implementation("androidx.webkit:webkit:1.12.1")
    // MP3: Java-порт LAME 3.98.4 (LGPL-2.1+), без NDK — збирається і в CI, і на телефоні.
    implementation("de.sciss:jump3r:1.0.5")

    testImplementation("junit:junit:4.13.2")
}
