plugins {
    id("com.android.application") version "8.7.3"
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "mx.alan.intercom.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "mx.alan.intercom"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-lab"
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

    kotlinOptions { jvmTarget = "17" }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
    }
}

dependencies {
    implementation(project(":core"))
}
