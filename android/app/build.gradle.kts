import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
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
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}