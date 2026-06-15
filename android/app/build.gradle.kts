import com.prince.turtlekeyboard.buildtools.dafsa.BuildDawgTask
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.google.services)
}

// Read secrets from local.properties (gitignored) so API keys never live in
// source. Returns empty when the property is missing — the build still
// succeeds, but network calls using the key will fail with a clear error
// from the upstream service rather than at compile time.
val localProperties = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val geminiApiKey: String = localProperties.getProperty("GEMINI_API_KEY") ?: run {
    logger.warn("GEMINI_API_KEY not set in local.properties — Gemini calls will fail at runtime")
    ""
}
val revenuecatSdkKey: String = localProperties.getProperty("REVENUECAT_SDK_KEY") ?: run {
    logger.warn("REVENUECAT_SDK_KEY not set in local.properties — RevenueCat init will fail at runtime")
    ""
}

// Mock AI knobs. When MOCK_AI=true every GeminiService call returns the
// bundled gg.png fixture after the per-method delay below — no network, no
// Gemini quota. Flip in local.properties for fast UI iteration; rebuild to
// switch back to live Gemini. SCENARIO is reserved for phases 3+4
// (error/fragmented modes); phase 1 only honors SUCCESS.
val mockAi: Boolean =
    (localProperties.getProperty("MOCK_AI") ?: "false").toBoolean()
val mockScenario: String =
    localProperties.getProperty("MOCK_SCENARIO") ?: "SUCCESS"
val mockDelayImageMs: Int =
    (localProperties.getProperty("MOCK_DELAY_IMAGE_MS") ?: "1500").toInt()
val mockDelayImageEditMs: Int =
    (localProperties.getProperty("MOCK_DELAY_IMAGE_EDIT_MS") ?: "2500").toInt()
val mockDelayTextMs: Int =
    (localProperties.getProperty("MOCK_DELAY_TEXT_MS") ?: "800").toInt()

android {
    namespace = "com.prince.turtlekeyboard"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.prince.turtlekeyboard"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "GEMINI_API_KEY", "\"$geminiApiKey\"")
        buildConfigField("String", "REVENUECAT_SDK_KEY", "\"$revenuecatSdkKey\"")
        buildConfigField("boolean", "MOCK_AI", mockAi.toString())
        buildConfigField("String",  "MOCK_SCENARIO", "\"$mockScenario\"")
        buildConfigField("int",     "MOCK_DELAY_IMAGE_MS", mockDelayImageMs.toString())
        buildConfigField("int",     "MOCK_DELAY_IMAGE_EDIT_MS", mockDelayImageEditMs.toString())
        buildConfigField("int",     "MOCK_DELAY_TEXT_MS", mockDelayTextMs.toString())
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
//    externalNativeBuild {
//        cmake {
//            path = file("src/main/cpp/CMakeLists.txt")
//            version = "3.22.1"
//        }
//    }
    buildFeatures {
        viewBinding = true
        dataBinding = true
        buildConfig = true
    }

    testOptions {
        // No isIncludeAndroidResources — none of the unit tests reference app
        // resources; enabling it pulls AAPT into the test classpath path and
        // has caused link failures with our resource set.
        unitTests.isReturnDefaultValues = true
    }

    // Keep the DAFSA asset uncompressed so the IME can mmap it straight from
    // the APK — compressed entries would need to be inflated to a temp file
    // first, losing the zero-parse cold-start win.
    androidResources {
        noCompress.add("dawg")
    }
}

// Copy the repo-shared system prompts (../../commands/prompts/) into the app's
// assets so both platforms read from the same source of truth and stay in
// parity. iOS does an analogous copy via an Xcode Run Script phase.
val copySharedPrompts = tasks.register<Copy>("copySharedPrompts") {
    from(rootProject.file("../commands/prompts")) {
        include("*.txt")
    }
    into(layout.buildDirectory.dir("generated/sharedPrompts/prompts"))
}
android.sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/sharedPrompts"))
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }
    .configureEach { dependsOn(copySharedPrompts) }

// Copy the built WebView games (../../games/dist/<name>/index.html) into the
// APK's merged assets so WebGameSheetView can load them at
// file:///android_asset/games/<name>/index.html. The games/ workspace must be
// built first (`cd games && pnpm build`) — Gradle does NOT invoke pnpm itself
// to keep the Android build hermetic. If games/dist is empty or missing the
// Copy task is a no-op and the APK builds without games.
val copyGamesHtml = tasks.register<Copy>("copyGamesHtml") {
    from(rootProject.file("../games/dist")) {
        include("*/index.html")
    }
    into(layout.buildDirectory.dir("generated/gamesAssets/games"))
}
android.sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/gamesAssets"))
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }
    .configureEach { dependsOn(copyGamesHtml) }

// Compile the bundled unigrams.txt into a minimized DAFSA the IME mmaps from
// the APK. The builder lives in buildSrc — see BuildDawgTask for the binary
// layout. Up-to-date checking is per Gradle's standard @InputFile / @OutputFile,
// so the ~1 MB blob is only rebuilt when the source dict changes.
val buildDawg = tasks.register<BuildDawgTask>("buildDawg") {
    input.set(file("src/main/assets/dict/en_unigrams.txt"))
    output.set(layout.buildDirectory.file("generated/dawgAssets/dict/en.dawg"))
}
android.sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/dawgAssets"))
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }
    .configureEach { dependsOn(buildDawg) }

configurations.all {
    resolutionStrategy {
        force("org.jetbrains.kotlin:kotlin-stdlib:1.8.10")
        force("org.jetbrains.kotlin:kotlin-stdlib-jdk7:1.8.10")
        force("org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.8.10")
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":split"))
    implementation(project(":notion"))
    implementation(project(":slack"))
    implementation(project(":web"))
    implementation(project(":ai"))
    implementation(libs.appcompat)
    implementation(libs.core.splashscreen)
    implementation(libs.emoji2)
    implementation(libs.emoji2.views.helper)
    implementation(libs.transition)
    implementation(libs.viewpager2)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.auth)
    implementation(libs.firebase.database)
    implementation(libs.revenuecat.purchases)
    implementation(libs.ai.edge.aicore)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.test.core)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.test.runner)
    androidTestImplementation(libs.test.rules)
}