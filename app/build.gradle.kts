import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

android {
    namespace = "io.github.yuubiz.folirel"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.yuubiz.folirel"
        minSdk = 28
        targetSdk = 37
        versionCode = 29
        versionName = "0.14.0"
        // Shown in Settings > About so a build can be traced to its source ("-dirty" = uncommitted changes).
        val gitCommit = try {
            providers.exec { commandLine("git", "describe", "--always", "--dirty", "--abbrev=7", "--match=no-such-tag") }.standardOutput.asText.get().trim()
        } catch (e: Exception) {
            "unknown"
        }
        buildConfigField("String", "GIT_COMMIT", "\"$gitCommit\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // The release key stays outside the repository; the git-ignored keystore.properties says where it is. Without the file the
    // release variant still configures, so tests, lint and debug builds work anywhere, but packaging a release APK fails (below).
    val releaseKeystore = rootProject.file("keystore.properties")
    signingConfigs {
        if (releaseKeystore.isFile) {
            val props = Properties().apply { releaseKeystore.inputStream().use(::load) }
            val missing = listOf("storeFile", "storePassword", "keyAlias", "keyPassword").filter { props.getProperty(it).isNullOrBlank() }
            if (missing.isNotEmpty()) throw GradleException("keystore.properties lacks ${missing.joinToString()}; see docs/releasing.md.")
            create("release") {
                storeFile = rootProject.file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
                // minSdk 28 verifies v2 and v3, so a v1 (JAR) signature would only add size; v4 would add an .idsig file nobody uses.
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = false
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { test ->
                test.maxHeapSize = "2g"
                // *Probe classes measure or read local data instead of checking behaviour; run them with -Pfolirel.probe.
                if (!project.hasProperty("folirel.probe")) test.exclude("**/*Probe.class")
                project.findProperty("folirel.realBackup")?.let { test.systemProperty("folirel.realBackup", it.toString()) }
                // Robolectric 4.17 on JDK 17+ needs access to FileDescriptor internals.
                test.jvmArgs(
                    "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
                    "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
                    "--add-opens=java.base/java.io=ALL-UNNAMED",
                    "--add-opens=java.base/java.lang=ALL-UNNAMED",
                )
            }
        }
    }

    sourceSets {
        // Synthetic fixtures are read from the repository root; never copied or re-encoded.
        getByName("test").resources.directories.add(rootProject.file("fixtures").path)
        getByName("androidTest").assets.directories.add(rootProject.file("fixtures").path)
        // Exported Room schemas for MigrationTestHelper.
        getByName("androidTest").assets.directories.add("$projectDir/schemas")
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }

    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/LICENSE*", "/META-INF/NOTICE*")
        }
    }
}

// An unsigned or debug-signed release APK must never come out of this build: stop before packaging when the key is not set up.
tasks.matching { it.name == "packageRelease" }.configureEach {
    val keystore = rootProject.file("keystore.properties")
    doFirst {
        if (!keystore.isFile) throw GradleException("Release signing is not set up: create keystore.properties in the repository root (see docs/releasing.md).")
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.work.runtime.ktx)
    implementation(libs.datastore.preferences)
    implementation(libs.androidx.browser)
    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.jsoup)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver3)
    testImplementation(libs.okhttp.tls)
    testImplementation(libs.room.testing)
    testImplementation(libs.work.testing)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.uiautomator)
    // Compose UI test pulls espresso-core 3.5.0, which fails on API 37 (InputManager.getInstance removed).
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    // The home flow test serves feeds from the device itself (the test source set only; production never allows loopback).
    androidTestImplementation(libs.okhttp.mockwebserver3)
}
