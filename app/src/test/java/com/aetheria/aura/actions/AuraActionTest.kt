package com.aetheria.aura.actions

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AuraActionTest {

    @Test
    fun testParseOpenApp() {
        val json = """{"action":"open_app","args":{"target":"chrome"}}"""
        val action = AuraAction.parse(json)
        assertTrue(action is AuraAction.OpenApp)
        assertEquals("chrome", (action as AuraAction.OpenApp).target)
    }

    @Test
    fun testParseOpenAppEmptyTarget() {
        val json = """{"action":"open_app","args":{}}"""
        val action = AuraAction.parse(json)
        assertTrue(action is AuraAction.OpenApp)
        assertEquals("", (action as AuraAction.OpenApp).target)
    }

    @Test
    fun testParseUnknownAction() {
        val json = """{"action":"unknown_action","args":{}}"""
        val action = AuraAction.parse(json)
        assertTrue(action is AuraAction.Unknown)
    }

    @Test
    fun testParseInvalidJson() {
        val json = """not valid json"""
        val action = AuraAction.parse(json)
        assertTrue(action is AuraAction.Unknown)
    }

    @Test
    fun testParseEmptyJson() {
        val json = "{}"
        val action = AuraAction.parse(json)
        assertTrue(action is AuraAction.Unknown)
    }
}