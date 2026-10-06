package io.github.yuubiz.folirel.app

import android.content.Intent
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** P3B-T25..T27 (the intent half), T32, T33: an Intent becomes a share request only when it is a text share, and only once. */
@RunWith(AndroidJUnit4::class)
class ShareIntentTest {
    private fun send(text: CharSequence?, type: String? = "text/plain") =
        Intent(Intent.ACTION_SEND).apply {
            this.type = type
            if (text != null) putExtra(Intent.EXTRA_TEXT, text)
        }

    @Test
    fun aTextShareGivesItsText() {
        assertEquals("面白い\nhttps://example.com/a", ShareIntent.consume(send("面白い\nhttps://example.com/a"))?.text)
    }

    @Test
    fun theTextIsTakenOutOfTheIntentSoItCannotBeHandledTwice() {
        val intent = send("https://example.com/a")
        assertNotNull(ShareIntent.consume(intent))
        assertFalse(intent.hasExtra(Intent.EXTRA_TEXT))
        // A copy of the intent after the first handling carries no text: it is still a share, with nothing to read.
        assertNull(ShareIntent.consume(intent)?.text)
    }

    @Test
    fun aLauncherStartAndOtherIntentsAreNotShares() {
        assertNull(ShareIntent.consume(null))
        assertNull(ShareIntent.consume(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)))
        assertNull(ShareIntent.consume(Intent(Intent.ACTION_VIEW)))
        assertNull(ShareIntent.consume(Intent(Intent.ACTION_SEND_MULTIPLE).apply { type = "text/plain" }))
        assertNull(ShareIntent.consume(Intent()))
    }

    @Test
    fun onlyPlainTextIsTaken() {
        assertNull(ShareIntent.consume(send("https://example.com/a", type = "image/png")))
        assertNull(ShareIntent.consume(send("https://example.com/a", type = null)))
        assertNotNull(ShareIntent.consume(send("https://example.com/a", type = "text/plain; charset=utf-8")))
        assertNotNull(ShareIntent.consume(send("https://example.com/a", type = "TEXT/PLAIN")))
    }

    @Test
    fun aShareWithoutTextIsAShareWithNoText() {
        assertNull(ShareIntent.consume(send(null))!!.text)
    }

    @Test
    fun anExtraThatIsNotTextDoesNotCrash() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, 42)
        }
        assertNull(ShareIntent.consume(intent)!!.text)
        val bundleAsText = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, Bundle())
        }
        assertNull(ShareIntent.consume(bundleAsText)!!.text)
    }

    @Test
    fun anIntentReopenedFromRecentsIsNotAShareAgain() {
        val intent = send("https://example.com/a").addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
        assertNull(ShareIntent.consume(intent))
    }

    @Test
    fun theManifestOffersTextSharingToTheRunningInstanceAndAddsNoPermission() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertNotNull(Regex("android.intent.action.SEND").find(manifest))
        assertNotNull(Regex("<data android:mimeType=\"text/plain\"\\s*/>").find(manifest))
        // A later share must reach the running instance (onNewIntent), not start a second activity.
        assertNotNull(Regex("android:launchMode=\"singleTask\"").find(manifest))
        // The launcher entry is kept.
        assertNotNull(Regex("android.intent.category.LAUNCHER").find(manifest))
        // No new permission: the same two as before (FOREGROUND_SERVICE is listed only to be removed from the merged manifest).
        val permissions = Regex("<uses-permission\\s+android:name=\"([^\"]+)\"").findAll(manifest).map { it.groupValues[1] }.toSet()
        assertEquals(
            setOf("android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE", "android.permission.FOREGROUND_SERVICE"),
            permissions,
        )
        assertNotNull(Regex("FOREGROUND_SERVICE\"\\s+tools:node=\"remove\"").find(manifest))
    }
}
