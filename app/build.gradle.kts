plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing comes only from the environment (CI repo secrets, see README.md).
// No keystore or password is ever stored in the repo.
val keystorePath: String? = System.getenv("SIGNING_KEYSTORE_FILE")?.takeIf { it.isNotBlank() }
val hasReleaseKey = keystorePath != null && file(keystorePath).exists() &&
    !System.getenv("SIGNING_STORE_PASSWORD").isNullOrBlank() &&
    !System.getenv("SIGNING_KEY_ALIAS").isNullOrBlank()
val onCi = System.getenv("CI") == "true"

// CI must never publish a throwaway-signed build: updates would not install over each other
if (onCi && !hasReleaseKey) {
    throw GradleException(
        "Release signing is not configured on CI: set the repo secrets SIGNING_KEYSTORE_B64, " +
            "SIGNING_STORE_PASSWORD, SIGNING_KEY_ALIAS (and SIGNING_KEY_PASSWORD) - see README.md"
    )
}

val versionCodeEnv: Int = System.getenv("VERSION_CODE")?.takeIf { it.isNotBlank() }?.toInt() ?: 1

android {
    namespace = "io.github.oceansde012.dotline"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.oceansde012.dotline"
        minSdk = 31
        targetSdk = 35
        versionCode = versionCodeEnv
        versionName = "1.0.$versionCodeEnv"
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(keystorePath!!)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")?.takeIf { it.isNotBlank() }
                    ?: System.getenv("SIGNING_STORE_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // installs next to the release app
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            buildConfigField("boolean", "UPDATES_ENABLED", "false")
        }
        release {
            isMinifyEnabled = false
            vcsInfo.include = false   // no git revision/branch metadata inside the APK
            buildConfigField("boolean", "UPDATES_ENABLED", "true")
            // local builds without the secrets fall back to the machine's debug key (CI fails above instead)
            signingConfig = if (hasReleaseKey) signingConfigs.getByName("release")
                            else signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}
