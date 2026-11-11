package com.aetheria.aura.shizuku

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScreenControlParseTest {

    @Test
    fun `escape input text spaces as percents`() {
        assertEquals("'hello%sworld'", ScreenControl.escapeForInputText("hello world"))
    }

    @Test
    fun `escape input text percent doubled first`() {
        assertEquals("'100%%%sdone'", ScreenControl.escapeForInputText("100% done"))
    }

    @Test
    fun `escape input text single quotes for shell`() {
        assertEquals("'it'\\''s%sok'", ScreenControl.escapeForInputText("it's ok"))
    }

    @Test
    fun `parse node line into ui node with center coords`() {
        val node = ScreenControl.parseNodeLine("Play\tcom.acme:id/play\ttrue\t[100,200][300,400]")
        assertTrue(node != null, "node should parse")
        assertEquals("Play", node!!.text)
        assertEquals("com.acme:id/play", node.resourceId)
        assertTrue(node.clickable)
        assertEquals(200, node.bounds.centerX)
        assertEquals(300, node.bounds.centerY)
    }

    @Test
    fun `node line with fewer fields returns null`() {
        assertNull(ScreenControl.parseNodeLine("garbage-with-no-tabs"))
    }

    @Test
    fun `node line with bad bounds returns null`() {
        assertNull(ScreenControl.parseNodeLine("Label\tid\ttrue\tnot-bounds"))
    }

    @Test
    fun `non clickable node parses clickable false`() {
        val node = ScreenControl.parseNodeLine("Text\t\tfalse\t[0,0][10,10]")
        assertTrue(node != null)
        assertEquals(false, node!!.clickable)
    }
}
