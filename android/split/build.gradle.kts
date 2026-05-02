plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.prince.split"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")

        // OAuth Web client ID — used to request access tokens for the Sheets/Drive scope.
        // Set in android/local.properties as: SPLIT_OAUTH_WEB_CLIENT_ID=xxxxx.apps.googleusercontent.com
        val oauthClientId = providers
            .gradleProperty("SPLIT_OAUTH_WEB_CLIENT_ID")
            .orElse(
                providers.fileContents(rootProject.layout.projectDirectory.file("local.properties"))
                    .asText
                    .map { txt ->
                        txt.lineSequence()
                            .map { it.trim() }
                            .firstOrNull { it.startsWith("SPLIT_OAUTH_WEB_CLIENT_ID=") }
                            ?.substringAfter("=")
                            .orEmpty()
                    }
            )
            .getOrElse("")
        buildConfigField("String", "OAUTH_WEB_CLIENT_ID", "\"" + oauthClientId + "\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.play.services.auth)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
