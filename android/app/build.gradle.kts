import org.gradle.api.tasks.Sync

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.chaquo.python")
}

android {
    namespace = "com.freedify.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.freedify.android"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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
        buildConfig = true
    }
}

val embeddedPythonDir = layout.buildDirectory.dir("generated/embeddedPython")
val syncEmbeddedPython by tasks.registering(Sync::class) {
    from(rootProject.projectDir.parentFile.resolve("app")) {
        into("app")
        exclude("**/__pycache__/**", "**/*.pyc")
    }
    from(rootProject.projectDir.parentFile.resolve("static")) {
        into("static")
    }
    into(embeddedPythonDir)
}

tasks.named("preBuild").configure {
    dependsOn(syncEmbeddedPython)
}

tasks.matching { it.name.endsWith("PythonSources") }.configureEach {
    dependsOn(syncEmbeddedPython)
}

chaquopy {
    defaultConfig {
        version = "3.13"
        pip {
            install("-r", "requirements-android.txt")
        }
    }
    sourceSets {
        getByName("main") {
            srcDir(embeddedPythonDir)
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
}
