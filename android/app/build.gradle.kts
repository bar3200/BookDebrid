import org.gradle.api.tasks.Sync

val signingStoreFile = providers.environmentVariable("FREEDIFY_SIGNING_STORE_FILE").orNull
val signingKeyAlias = providers.environmentVariable("FREEDIFY_SIGNING_KEY_ALIAS").orNull
val signingStorePassword = providers.environmentVariable("FREEDIFY_SIGNING_STORE_PASSWORD").orNull
val signingKeyPassword = providers.environmentVariable("FREEDIFY_SIGNING_KEY_PASSWORD").orNull
val releaseSigningConfigured = listOf(
    signingStoreFile,
    signingKeyAlias,
    signingStorePassword,
    signingKeyPassword,
).all { !it.isNullOrBlank() }

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
        versionCode = 3
        versionName = "1.1.0"

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("persistentRelease") {
                storeFile = file(signingStoreFile!!)
                keyAlias = signingKeyAlias
                storePassword = signingStorePassword
                keyPassword = signingKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("persistentRelease")
            }
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

val verifyReleaseSigning by tasks.registering {
    doLast {
        check(releaseSigningConfigured) {
            "Release signing is not configured. Set all FREEDIFY_SIGNING_* environment variables."
        }
        check(file(signingStoreFile!!).isFile) {
            "Release signing keystore does not exist: $signingStoreFile"
        }
    }
}

tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    dependsOn(verifyReleaseSigning)
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
    implementation("androidx.media:media:1.7.0")
}
