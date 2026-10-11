plugins {
    id("com.android.application")
}

val officialBuild = project.findProperty("fuelsmoothing.official")?.toString() == "true" ||
    System.getenv("FUELSMOOTHING_OFFICIAL") == "1"
val applicationUser = System.getProperty("user.name").orEmpty()
    .lowercase()
    .replace(Regex("[^a-z0-9]"), "_")
    .trim('_')
    .ifBlank { "local" }
val fuelsmoothingApplicationId = if (officialBuild) {
    "com.davidlang.torque.fuelsmoothing"
} else {
    "com.$applicationUser.torque.fuelsmoothing"
}

val releaseStoreFile = System.getenv("FUELSMOOTHING_STORE_FILE")
val releaseStorePassword = System.getenv("FUELSMOOTHING_STORE_PASSWORD")
val releaseKeyAlias = System.getenv("FUELSMOOTHING_KEY_ALIAS")
val releaseKeyPassword = System.getenv("FUELSMOOTHING_KEY_PASSWORD")
val releaseSigningReady = !releaseStoreFile.isNullOrBlank() &&
    !releaseStorePassword.isNullOrBlank() &&
    !releaseKeyAlias.isNullOrBlank() &&
    !releaseKeyPassword.isNullOrBlank()

android {
    namespace = "com.davidlang.torque.fuelsmoothing"
    compileSdk = 36
    defaultConfig {
        applicationId = fuelsmoothingApplicationId
        minSdk = 21
        targetSdk = 36
        versionCode = file("versionCode.txt").readText().trim().toInt()
        val rawVersion = providers.exec {
            workingDir = project.rootDir
            commandLine(
                "bash",
                "-c",
                "BRANCH_NAME=$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo 'HEAD') && (git describe --tags --match \"${'$'}{BRANCH_NAME}-start\" 2>/dev/null || git describe --always 2>/dev/null || echo 'dev')",
            )
        }.standardOutput.asText.orNull?.trim() ?: "dev"
        versionName = rawVersion
        if (!officialBuild) {
            resValue("string", "app_name", "Fuel Smoothing for Torque (local)")
        }
    }
    val androidUserHome = System.getenv("ANDROID_USER_HOME")
        ?: error("ANDROID_USER_HOME unset; run ./build_app (sets worktree .android-shared)")
    signingConfigs {
        getByName("debug") {
            storeFile = file("$androidUserHome/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (releaseSigningReady) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }
    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseSigningReady) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        buildConfig = true
        aidl = true
        resValues = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("com.android.billingclient:billing-ktx:8.0.0")
    testImplementation("junit:junit:4.13.2")
}
