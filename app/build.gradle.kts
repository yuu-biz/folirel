import groovy.json.JsonOutput
import java.util.Properties
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.gradle.api.artifacts.component.ComponentArtifactIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedArtifactResult

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
        versionCode = 30
        versionName = "1.0.0-rc.1"
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
            // Settings > About > Licenses shows every licence and notice text of the runtime classpath (LicenseCatalogTask below), so
            // the copies inside the libraries stay out of the APK.
            excludes += setOf("/META-INF/LICENSE*", "/META-INF/NOTICE*", "/META-INF/**/LICENSE*", "/META-INF/**/NOTICE*")
        }
    }
}

// An unsigned or debug-signed release APK must never come out of this build: stop before packaging when the key is not set up.
tasks.named { it == "packageRelease" }.configureEach {
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
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    // Compose UI test pulls an espresso-core that fails under Robolectric's newer SDKs (InputManager.getInstance removed).
    testImplementation(libs.androidx.test.espresso.core)

    // createComposeRule() starts the empty ComponentActivity this manifest declares; debug only, so a release build never has it.
    debugImplementation(libs.compose.ui.test.manifest)

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

// Settings > About > Licenses shows what this task writes. It reads the variant's runtime classpath, so the list cannot drift from
// what the APK contains.
androidComponents {
    onVariants { variant ->
        val resolved = variant.runtimeConfiguration.incoming.artifactView {
            componentFilter { it is ModuleComponentIdentifier }
        }.artifacts.resolvedArtifacts
        // Each module's own POM (not its dependencies) is where its licence is declared.
        val pomConfiguration = configurations.create("${variant.name}LicensePoms") {
            isCanBeConsumed = false
            isTransitive = false
        }
        pomConfiguration.dependencies.addAllLater(
            resolved.map { artifacts ->
                artifacts.map { (it.id.componentIdentifier as ModuleComponentIdentifier).displayName }.distinct()
                    .map { project.dependencies.create("$it@pom") }
            },
        )
        val catalog = tasks.register<LicenseCatalogTask>("${variant.name}LicenseCatalog") {
            artifactIds.set(resolved.map { set -> set.map { it.id } })
            artifactFiles.set(resolved.map { set -> set.map { it.file } })
            poms.set(pomConfiguration.incoming.artifacts.resolvedArtifacts)
            inheritedLicenses.put("com.google.guava:listenablefuture", "Apache-2.0") // declared in its parent, guava-parent
            // DataStore's repackaged Protocol Buffers carry the BSD terms without the upstream copyright line, which the terms require.
            copyrights.put("androidx.datastore:datastore-preferences-external-protobuf", "Protocol Buffers\nCopyright 2008 Google Inc.  All rights reserved.")
            appLicense.set(rootProject.layout.projectDirectory.file("LICENSE"))
            sharedTexts.set(layout.projectDirectory.dir("licenses"))
            // Vector drawables copied from Material Icons (see the header of each file in res/drawable); not a classpath module.
            bundled.set(listOf("Material Icons (vector drawables)|github.com/google/material-design-icons|Apache-2.0"))
        }
        variant.sources.assets?.addGeneratedSourceDirectory(catalog, LicenseCatalogTask::outputDir)
    }
}

/**
 * Writes `licenses.json`: Folirel's own licence, then every module of the runtime classpath with the licences its POM declares, the
 * full texts, and the LICENSE and NOTICE files its artifact carries. The APK drops those files (`packaging.resources.excludes`), so
 * this screen is where Apache-2.0 section 4(d) notices are shown. A licence the task cannot map fails the build: a new dependency
 * cannot ship without its text.
 */
abstract class LicenseCatalogTask : DefaultTask() {
    @get:Input abstract val artifactIds: ListProperty<ComponentArtifactIdentifier>
    @get:InputFiles @get:PathSensitive(PathSensitivity.NAME_ONLY) abstract val artifactFiles: ListProperty<File>

    // Resolved only when the task runs: resolving it while Gradle plans the build would resolve the runtime classpath that early too.
    // Not an input: a published POM never changes for the module versions that artifactIds already tracks.
    @get:Internal abstract val poms: SetProperty<ResolvedArtifactResult>

    /** Modules whose POM leaves the licence to a parent POM: "group:module" to licence id. */
    @get:Input abstract val inheritedLicenses: MapProperty<String, String>

    /** Copyright lines a library's bundled LICENSE file leaves out, put in front of it: "group:module" to the line. */
    @get:Input abstract val copyrights: MapProperty<String, String>

    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val appLicense: RegularFileProperty
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) abstract val sharedTexts: DirectoryProperty

    /** Third-party material that is not a classpath module: "name|id|licence id". */
    @get:Input abstract val bundled: ListProperty<String>

    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun write() {
        val pomByModule = poms.get().associate { (it.id.componentIdentifier as ModuleComponentIdentifier).moduleIdentifier.toString() to it.file }
        val inherited = inheritedLicenses.get()
        val shared = sharedTexts.get().asFile.listFiles { f -> f.extension == "txt" }!!.associate { it.nameWithoutExtension to it.readText() }
        val texts = linkedMapOf<String, Map<String, String>>()
        fun sharedText(id: String): String {
            val body = shared[id] ?: throw GradleException("No text for licence $id in app/licenses/.")
            texts.getOrPut(id) { mapOf("title" to LICENSE_TITLES.getValue(id), "body" to body) }
            return id
        }
        val libraries = mutableListOf<Map<String, Any>>()
        texts["Folirel"] = mapOf("title" to LICENSE_TITLES.getValue("MIT"), "body" to appLicense.get().asFile.readText())
        libraries += library("Folirel", "io.github.yuubiz.folirel", "", listOf("Folirel"), emptyList())

        val problems = mutableListOf<String>()
        artifactIds.get().zip(artifactFiles.get())
            .groupBy { (id, _) -> id.componentIdentifier as ModuleComponentIdentifier }
            .toSortedMap(compareBy { it.displayName })
            .forEach { (component, artifacts) ->
                val module = component.moduleIdentifier.toString()
                val pom = pomByModule[module]?.let(::readPom) ?: throw GradleException("No POM for $module.")
                val ids = pom.licenses.mapNotNull { (name, url) -> spdx(name, url) ?: null.also { problems += "$module: unknown licence \"$name\" ($url)" } }
                    .ifEmpty { listOfNotNull(inherited[module]) }
                if (pom.licenses.isEmpty() && ids.isEmpty()) problems += "$module: the POM declares no licence"
                val files = artifacts.flatMap { (_, file) -> licenseFiles(file) }.distinctBy { it.second.normalized() }
                val ownFile = files.firstOrNull { (path, _) -> !path.contains("NOTICE", ignoreCase = true) }
                val licenseKeys = ids.mapNotNull { id ->
                    if (id in OWN_TEXT) {
                        ownFile?.let { (_, body) ->
                            val text = listOfNotNull(copyrights.get()[module], body).joinToString("\n\n")
                            "$module/LICENSE".also { texts[it] = mapOf("title" to LICENSE_TITLES.getValue(id), "body" to text) }
                        } ?: null.also { problems += "$module: $id licence without a bundled LICENSE file" }
                    } else {
                        sharedText(id)
                    }
                }
                val known = shared.values.map { it.normalized() }
                // AndroidX ships the Apache text without its appendix ("How to apply..."); that is the same licence, not a notice.
                val notices = files.filter { (path, body) ->
                    val b = body.normalized()
                    (ownFile?.first != path || ids.none { it in OWN_TEXT }) && known.none { it == b || (it.startsWith(b) && "END OF TERMS AND CONDITIONS" in b) }
                }
                    .map { (path, body) -> "$module/$path".also { texts[it] = mapOf("title" to path.substringAfterLast('/'), "body" to body) } }
                libraries += library(pom.name, module, component.version, licenseKeys, notices)
            }
        bundled.get().forEach { line ->
            val (name, module, id) = line.split('|')
            libraries += library(name, module, "", listOf(sharedText(id)), emptyList())
        }
        if (problems.isNotEmpty()) {
            throw GradleException("Licences screen: ${problems.joinToString("; ")}. Add the text under app/licenses/ and map it in LicenseCatalogTask.")
        }
        val out = outputDir.get().asFile.apply { deleteRecursively(); mkdirs() }
        File(out, "licenses.json").writeText(JsonOutput.prettyPrint(JsonOutput.toJson(mapOf("texts" to texts, "libraries" to libraries))))
    }

    private fun library(name: String, module: String, version: String, licenses: List<String>, notices: List<String>) =
        mapOf("name" to name, "module" to module, "version" to version, "licenses" to licenses, "notices" to notices)

    private data class Pom(val name: String, val licenses: List<Pair<String, String>>)

    private fun readPom(file: File): Pom {
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            isExpandEntityReferences = false
        }
        val project = factory.newDocumentBuilder().parse(file).documentElement
        fun org.w3c.dom.Element.children(tag: String) = (0 until childNodes.length).map { childNodes.item(it) }
            .filterIsInstance<org.w3c.dom.Element>().filter { it.tagName == tag }
        fun org.w3c.dom.Element.text(tag: String) = children(tag).firstOrNull()?.textContent.orEmpty().trim()
        val licenses = project.children("licenses").flatMap { it.children("license") }.map { it.text("name") to it.text("url") }
        return Pom(project.text("name").ifEmpty { project.text("artifactId") }, licenses)
    }

    /** LICENSE, NOTICE and COPYING files inside a jar, or inside an aar and the jars it contains. */
    private fun licenseFiles(file: File): List<Pair<String, String>> {
        val found = mutableListOf<Pair<String, String>>()
        fun scan(zip: ZipInputStream) {
            while (true) {
                val entry = zip.nextEntry ?: break
                when {
                    entry.isDirectory -> Unit
                    entry.name.endsWith(".jar") -> scan(ZipInputStream(zip))
                    LICENSE_FILE.matches(entry.name.substringAfterLast('/')) -> found += entry.name to zip.readBytes().decodeToString()
                }
            }
        }
        ZipInputStream(file.inputStream().buffered()).use(::scan)
        return found
    }

    private fun spdx(name: String, url: String): String? {
        val n = name.lowercase()
        val u = url.lowercase()
        return when {
            "apache.org/licenses/license-2.0" in u || ("apache" in n && "2.0" in n) -> "Apache-2.0"
            "opensource.org/licenses/mit" in u || n in setOf("mit", "mit license", "the mit license") -> "MIT"
            "opensource.org/licenses/bsd-3-clause" in u || n == "bsd-3-clause" -> "BSD-3-Clause"
            else -> null
        }
    }

    private fun String.normalized() = trim().split(Regex("\\s+")).joinToString(" ")

    private companion object {
        val LICENSE_FILE = Regex("(?i)(LICENSE|LICENCE|NOTICE|COPYING)([._-][^/]*)?")
        val LICENSE_TITLES = mapOf("Apache-2.0" to "Apache License 2.0", "MIT" to "MIT License", "BSD-3-Clause" to "BSD 3-Clause License")

        // Licences whose text carries the library's own copyright line, so the text must come from the library's LICENSE file.
        val OWN_TEXT = setOf("MIT", "BSD-3-Clause")
    }
}
