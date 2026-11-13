plugins {
    id("com.android.application")
}

android {
    namespace = "com.opencode.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.opencode.android"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
        resourceConfigurations += listOf("en", "uk")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += setOf("**/libchrootng.so")
        }
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    implementation("org.apache.commons:commons-compress:1.26.2")
    implementation("org.tukaani:xz:1.9")
}
