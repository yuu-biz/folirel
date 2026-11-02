package io.github.yuubiz.folirel.ui.settings

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Settings > About > Licenses reads the catalogue the build generates from the runtime classpath into the app's assets. */
@RunWith(AndroidJUnit4::class)
class LicenseCatalogTest {
    private val groups by lazy {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.assets.open(LICENSE_CATALOG_ASSET).use { parseLicenseCatalog(it.readBytes().decodeToString()) }
    }

    private fun groupOf(module: String) = groups.single { g -> g.libraries.any { it.module == module } }

    @Test
    fun folirelsOwnMitLicenceComesFirst() {
        val first = groups.first()
        assertEquals(listOf("Folirel"), first.libraries.map { it.name })
        assertTrue(first.text.body.contains("MIT License") && first.text.body.contains("Permission is hereby granted"))
    }

    @Test
    fun theLibrariesTheAppDeclaresAreListedWithTheirLicenceText() {
        val apache = listOf(
            "androidx.room:room-runtime-android", "androidx.work:work-runtime", "androidx.datastore:datastore-preferences-android",
            "androidx.browser:browser", "com.squareup.okhttp3:okhttp-android", "com.squareup.okio:okio-jvm", "io.coil-kt.coil3:coil-compose-android",
            "org.jetbrains.kotlinx:kotlinx-coroutines-android", "org.jetbrains.kotlinx:kotlinx-serialization-json-jvm", "org.jetbrains.kotlin:kotlin-stdlib",
        )
        apache.forEach { module ->
            val group = groupOf(module)
            assertEquals(module, "Apache License 2.0", group.text.title)
            assertTrue(module, group.text.body.contains("TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION"))
            assertTrue(module, group.libraries.single { it.module == module }.version.isNotEmpty())
        }
        // MIT and BSD texts carry the library's own copyright line, so each comes from the library itself.
        groupOf("org.jsoup:jsoup").let { assertEquals("MIT License", it.text.title); assertTrue(it.text.body.contains("Jonathan Hedley")) }
        groupOf("androidx.datastore:datastore-preferences-external-protobuf").let {
            assertEquals("BSD 3-Clause License", it.text.title)
            assertTrue(it.text.body.contains("Google"))
        }
        // The copied Material Icons drawables are not a library on the classpath but are listed too.
        assertEquals("Apache License 2.0", groupOf("github.com/google/material-design-icons").text.title)
    }

    @Test
    fun eachLicenceTextIsListedOnceAndEveryGroupHasLibrariesAndAText() {
        assertEquals(groups.size, groups.map { it.text.body }.distinct().size)
        groups.forEach { assertTrue(it.text.title, it.libraries.isNotEmpty() && it.text.body.isNotBlank()) }
    }

    @Test
    fun aLibraryUnderTwoLicencesIsInBothGroupsAndItsNoticesStayWithIt() {
        val json = """
            {"texts": {"A": {"title": "Apache License 2.0", "body": "apache"}, "m/LICENSE": {"title": "MIT License", "body": "mit"},
                       "m/META-INF/NOTICE": {"title": "NOTICE", "body": "notice"}},
             "libraries": [{"name": "One", "module": "g:one", "version": "1", "licenses": ["A"], "notices": []},
                           {"name": "Two", "module": "g:two", "version": "2", "licenses": ["m/LICENSE", "A"], "notices": ["m/META-INF/NOTICE"]}]}
        """.trimIndent()
        val parsed = parseLicenseCatalog(json)
        assertEquals(listOf("Apache License 2.0", "MIT License"), parsed.map { it.text.title })
        assertEquals(listOf("One", "Two"), parsed[0].libraries.map { it.name })
        assertEquals(listOf(LicenseText("NOTICE", "notice")), parsed[1].libraries.single().notices)
    }
}
