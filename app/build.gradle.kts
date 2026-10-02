plugins {
    alias(libs.plugins.android.application)
}

val releaseStoreFile = providers.environmentVariable("AHUT_RELEASE_STORE_FILE").orNull?.takeIf { it.isNotBlank() }
val releaseKeyAlias = providers.environmentVariable("AHUT_RELEASE_KEY_ALIAS").orNull?.takeIf { it.isNotBlank() }
val releaseStorePassword = providers.environmentVariable("AHUT_RELEASE_STORE_PASSWORD").orNull?.takeIf { it.isNotBlank() }
val releaseKeyPassword = providers.environmentVariable("AHUT_RELEASE_KEY_PASSWORD").orNull?.takeIf { it.isNotBlank() }
val releaseSigningConfigured = listOf(
    releaseStoreFile, releaseKeyAlias, releaseStorePassword, releaseKeyPassword
).all { it != null }

val verifyReleaseSigning = tasks.register("verifyReleaseSigning") {
    group = "verification"
    description = "Checks that all release signing values are configured before packaging."
    doLast {
        check(releaseSigningConfigured) {
            "Release packaging requires AHUT_RELEASE_STORE_FILE, AHUT_RELEASE_KEY_ALIAS, " +
                "AHUT_RELEASE_STORE_PASSWORD, and AHUT_RELEASE_KEY_PASSWORD."
        }
    }
}

tasks.configureEach {
    if (name == "packageRelease" || name == "bundleRelease") {
        dependsOn(verifyReleaseSigning)
    }
}

android {
    namespace = "ahut.wifiauth.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "ahut.wifiauth.android"
        minSdk = 24
        targetSdk = 36
        versionCode = 7
        versionName = "1.3.2"
        resValue("string", "app_version", "v$versionName")
    }

    signingConfigs {
        create("release") {
            releaseStoreFile?.let { storeFile = file(it) }
            storePassword = releaseStorePassword.orEmpty()
            keyAlias = releaseKeyAlias.orEmpty()
            keyPassword = releaseKeyPassword.orEmpty()
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
        resValues = true
        compose = false
        aidl = false
        buildConfig = false
        shaders = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

androidComponents {
    onVariants { variant ->
        val variantName = variant.name.replaceFirstChar { it.uppercase() }
        val buildTypeName = variant.buildType ?: variant.name
        val outputDir = rootProject.layout.projectDirectory.dir("apk_output")

        val copyApk = tasks.register("copy${variantName}Apk", Copy::class) {
            from(variant.artifacts.get(com.android.build.api.artifact.SingleArtifact.APK))
            include("*.apk")
            into(outputDir)
            val verName = android.defaultConfig.versionName ?: "1.0.0"
            rename { "ahut-auth-v${verName}-${buildTypeName}.apk" }
        }
        afterEvaluate {
            tasks.named("assemble${variantName}") {
                finalizedBy(copyApk)
            }
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.json)
}

