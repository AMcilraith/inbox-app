package com.amcilraith.inboxhub

import com.amcilraith.inboxhub.data.accountLabel
import com.amcilraith.inboxhub.data.htmlToText
import com.amcilraith.inboxhub.data.previewText
import com.amcilraith.inboxhub.ui.initials
import com.amcilraith.inboxhub.ui.shortUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TextTest {
    @Test fun dropsInvisiblePreheaderPadding() {
        val s = previewText("Sale͏​­͏ ‌﻿starts now")
        assertEquals("Sale starts now", s)
    }

    @Test fun decodesEntitiesInPlainText() {
        assertEquals("Fish & chips — 5 > 3\nnext line", htmlToText("Fish &amp; chips &mdash; 5 &gt; 3\nnext line"))
    }

    @Test fun stripsStyleAndRules() {
        val t = htmlToText("<html><head><style>.a{color:red}</style></head><body><p>Hello</p><p>━━━━━━━━</p><p>World&nbsp;!</p></body></html>")
        assertFalse(t.contains("color"))
        assertFalse(t.contains("━"))
        assertEquals("Hello", t.lines().first())
        assertEquals("World !", t.lines().last())
    }

    @Test fun keepsEmojiSequences() {
        val family = "👨‍👩‍👧"
        assertEquals("Hi $family", previewText("Hi $family"))
    }

    @Test fun shortensTrackingLinks() {
        assertEquals("shopnow.example/bf…", shortUrl("https://shopnow.example/bf?utm_campaign=bf26&utm_source=newsletter"))
        assertEquals("example.com", shortUrl("https://www.example.com"))
    }

    @Test fun labelsAndInitials() {
        assertEquals("Northwind", accountLabel("alex@northwind.studio"))
        assertEquals("alex.r", accountLabel("alex.r@gmail.com"))
        assertEquals("PS", initials("Priya Shah"))
        assertEquals("C", initials("CEO"))
    }
}
