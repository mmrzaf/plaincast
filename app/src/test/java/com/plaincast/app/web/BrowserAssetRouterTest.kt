package com.plaincast.app.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrowserAssetRouterTest {
    @Test
    fun servesOnlyKnownBrowserRoutes() {
        assertEquals("index.html", BrowserAssetRouter.resolve("/")?.file)
        assertEquals("index.html", BrowserAssetRouter.resolve("/join/ABCD")?.file)
        assertEquals("index.html", BrowserAssetRouter.resolve("/join/8k7p/")?.file)
        assertEquals("app.js", BrowserAssetRouter.resolve("/app.js")?.file)
        assertEquals("styles.css", BrowserAssetRouter.resolve("/styles.css")?.file)
        assertEquals("audio-worklet.js", BrowserAssetRouter.resolve("/audio-worklet.js")?.file)
        assertEquals("manifest.webmanifest", BrowserAssetRouter.resolve("/manifest.webmanifest")?.file)
        assertEquals("favicon.png", BrowserAssetRouter.resolve("/favicon.png")?.file)
        assertEquals("icon-192.png", BrowserAssetRouter.resolve("/icon-192.png")?.file)
        assertEquals("icon-512.png", BrowserAssetRouter.resolve("/icon-512.png")?.file)
        assertNull(BrowserAssetRouter.resolve("/join/ABC"))
        assertNull(BrowserAssetRouter.resolve("/join/ABCD/extra"))
        assertNull(BrowserAssetRouter.resolve("/unknown"))
    }
}
