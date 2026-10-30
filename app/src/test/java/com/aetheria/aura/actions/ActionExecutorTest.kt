package com.aetheria.aura.actions

import kotlin.test.Test
import kotlin.test.assertEquals

class ActionExecutorTest {

    @Test
    fun `cleanPhoneNumberForSms strips spaces dashes parentheses`() {
        assertEquals("3525550100", ActionExecutor.cleanPhoneNumberForSms("(352) 555-0100"))
        assertEquals("+13525550100", ActionExecutor.cleanPhoneNumberForSms("+1 352-555-0100"))
        assertEquals("3525550100", ActionExecutor.cleanPhoneNumberForSms("3525550100"))
    }

    @Test
    fun `cleanPhoneNumberForSms preserves plus and country code`() {
        assertEquals("+447911123456", ActionExecutor.cleanPhoneNumberForSms("+44 7911 123456"))
        assertEquals("+15551234567", ActionExecutor.cleanPhoneNumberForSms("+1 (555) 123-4567"))
    }

    @Test
    fun `cleanPhoneNumberForSms handles already clean numbers`() {
        assertEquals("5551234", ActionExecutor.cleanPhoneNumberForSms("5551234"))
        assertEquals("+15551234567", ActionExecutor.cleanPhoneNumberForSms("+15551234567"))
    }

    @Test
    fun `cleanPhoneNumberForSms handles empty string`() {
        assertEquals("", ActionExecutor.cleanPhoneNumberForSms(""))
    }
}