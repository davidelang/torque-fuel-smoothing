import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.testing.Test
import org.gradle.process.ExecOperations

plugins {
    id("com.android.application")
}

val officialApplicationId = "com.davidlang.torque.fuelsmoothing"
val applicationIdPattern = Regex("""^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$""")
val officialProp = project.findProperty("fuelsmoothing.official")?.toString()
if (officialProp != null && officialProp != "true") {
    error("-Pfuelsmoothing.official must be true or unset")
}
val officialEnv = System.getenv("FUELSMOOTHING_OFFICIAL")
if (officialEnv != null && officialEnv != "1") {
    error("FUELSMOOTHING_OFFICIAL must be 1 or unset")
}
val officialChosen = officialProp == "true" || officialEnv == "1"
val devProp = project.findProperty("fuelsmoothing.dev.package")?.toString()
val devChosen = devProp != null
if (officialChosen && devChosen) {
    error(
        "Set only one of -Pfuelsmoothing.official=true (or FUELSMOOTHING_OFFICIAL=1) " +
            "and -Pfuelsmoothing.dev.package=<applicationId>.",
    )
}
if (!officialChosen && !devChosen) {
    error(
        "Set -Pfuelsmoothing.official=true (or FUELSMOOTHING_OFFICIAL=1) " +
            "for com.davidlang.torque.fuelsmoothing, or -Pfuelsmoothing.dev.package=<applicationId> " +
            "for a dev package.",
    )
}
val fuelsmoothingApplicationId = if (officialChosen) {
    officialApplicationId
} else {
    val devPackage = devProp ?: error("-Pfuelsmoothing.dev.package is empty")
    if (devPackage.isEmpty()) {
        error("-Pfuelsmoothing.dev.package is empty")
    }
    if (devPackage == officialApplicationId) {
        error(
            "com.davidlang.torque.fuelsmoothing requires -Pfuelsmoothing.official=true " +
                "or FUELSMOOTHING_OFFICIAL=1",
        )
    }
    if (!applicationIdPattern.matches(devPackage)) {
        error("-Pfuelsmoothing.dev.package='$devPackage' is not an applicationId")
    }
    devPackage
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
    ndkVersion = "28.2.13676358"
    defaultConfig {
        applicationId = fuelsmoothingApplicationId
        minSdk = 21
        targetSdk = 36
        val versionCodeText = try {
            file("versionCode.txt").readText()
        } catch (_: Exception) {
            error("versionCode: app/versionCode.txt is missing or unreadable")
        }
        versionCode = versionCodeText.trim().toIntOrNull()
            ?: error("versionCode: app/versionCode.txt is '${versionCodeText.trim()}', not an integer")
        val described = providers.exec {
            workingDir = project.rootDir
            isIgnoreExitValue = true
            commandLine(
                "bash",
                "-c",
                "set -euo pipefail\n" +
                    "BRANCH_NAME=\$(git rev-parse --abbrev-ref HEAD)\n" +
                    "if git rev-parse --verify \"refs/tags/\${BRANCH_NAME}-start\" >/dev/null; then\n" +
                    "  git describe --tags --match \"\${BRANCH_NAME}-start\"\n" +
                    "else\n" +
                    "  git describe --always --exclude '*+*'\n" +
                    "fi\n",
            )
        }
        val describeResult = described.result.get()
        if (describeResult.exitValue != 0) {
            val describeErr = described.standardError.asText.get().trim()
            error(
                "versionName: git describe failed" +
                    if (describeErr.isEmpty()) "" else "\n$describeErr",
            )
        }
        val rawVersion = described.standardOutput.asText.get().trim()
        if (rawVersion.isEmpty()) {
            error("versionName: git describe printed nothing")
        }
        versionName = rawVersion
        if (!officialChosen) {
            resValue("string", "app_name", "Fuel Smoothing for Torque (local)")
        }
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
            }
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
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
            isMinifyEnabled = false
            isShrinkResources = false
            isDebuggable = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
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
    androidResources {
        localeFilters += listOf(
            "en",
            "af", "am", "as", "az", "b+ceb", "b+en+001", "b+es+419", "b+sr+Latn",
            "be", "bg", "bn", "bs", "ca", "cs", "cy", "da", "de", "el",
            "en-rAU", "en-rCA", "en-rGB", "en-rIN",
            "es", "es-rUS", "et", "eu", "fi", "fo", "fr", "fr-rCA", "fy",
            "ga", "gd", "gl", "gu", "ha", "hi", "hr", "hu", "hy", "ig", "in",
            "is", "it", "ja", "jv", "ka", "kk", "km", "kn", "ko", "ku", "ky",
            "lb", "lo", "lt", "lv", "mg", "mi", "mk", "ml", "mn", "mr", "ms",
            "mt", "my", "nb", "ne", "nl", "nn", "or", "pa", "pl", "pt", "pt-rPT",
            "qu", "ro", "rm", "ru", "rw", "si", "sk", "sl", "so", "sq", "sr",
            "su", "sv", "sw", "ta", "te", "tg", "th", "tk", "tl", "tr", "tt",
            "uk", "uz", "vi", "xh", "yo", "zh-rCN", "zh-rHK", "zh-rTW", "zu",
        )
        generateLocaleConfig = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Changing billing-ktx requires the same version in the open-source notices sentence.
    implementation("com.android.billingclient:billing-ktx:8.0.0")
    testImplementation("junit:junit:4.13.2")
    // Real org.json for unit tests (android.jar ships stubs only); profile.json is edited with JSONObject.
    testImplementation("org.json:json:20240303")
}

tasks.withType<Test>().configureEach {
    val evalDir = System.getProperty("fuelsmoothing.evalDir")
    if (evalDir != null) systemProperty("fuelsmoothing.evalDir", evalDir)
}

// Catalog -> build-output strings and the S() resource map. Does not translate.
// AGP 9 takes generated directories from the variant, not sourceSets.srcDir(Provider).
abstract class GenerateI18nStrings : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val watched: ConfigurableFileCollection

    @get:Input
    abstract val emitAppName: Property<Boolean>

    @get:OutputDirectory
    abstract val resDir: DirectoryProperty

    @get:OutputDirectory
    abstract val kotlinDir: DirectoryProperty

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun generate() {
        val ktFile = kotlinDir.get().asFile.resolve(
            "com/davidlang/torque/fuelsmoothing/I18nSentences.kt",
        )
        execOperations.exec {
            workingDir(project.rootProject.projectDir)
            commandLine("python3", "scripts/i18n_generate_resources.py")
            environment("PYTHONDONTWRITEBYTECODE", "1")
            environment("I18N_RES_DIR", resDir.get().asFile.absolutePath)
            environment("I18N_KT_FILE", ktFile.absolutePath)
            environment("I18N_EMIT_APP_NAME", if (emitAppName.get()) "1" else "0")
        }
    }
}

val generateI18nStrings = tasks.register<GenerateI18nStrings>("generateI18nStrings") {
    watched.from(layout.projectDirectory.dir("src/main/i18n"))
    watched.from(layout.projectDirectory.dir("src/main/kotlin"))
    watched.from(rootProject.layout.projectDirectory.file("scripts/i18n_generate_resources.py"))
    watched.from(rootProject.layout.projectDirectory.file("scripts/i18n_catalog.py"))
    emitAppName.set(officialChosen)
    resDir.set(layout.buildDirectory.dir("generated/i18n/res"))
    kotlinDir.set(layout.buildDirectory.dir("generated/i18n/kotlin"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.res?.addGeneratedSourceDirectory(
            generateI18nStrings,
            GenerateI18nStrings::resDir,
        )
        variant.sources.kotlin?.addGeneratedSourceDirectory(
            generateI18nStrings,
            GenerateI18nStrings::kotlinDir,
        )
    }
}
