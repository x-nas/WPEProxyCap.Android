package com.wpe64.wpc.service

import com.wpe64.wpc.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ApiKeyTest {

    @Test
    fun decodeXorsPartsBack() {
        val key = "Test-Key_0123456789abcdefABCDEF!"
        val b = ByteArray(key.length) { i -> (i * 37 + 11).toByte() }
        val a = ByteArray(key.length) { i -> (key[i].code xor b[i].toInt()).toByte() }
        assertEquals(key, ApiKey.decode(a, b))
    }

    @Test
    fun emptyKeyDecodesToEmpty() {
        assertEquals("", ApiKey.decode(ByteArray(0), ByteArray(0)))
    }

    @Test
    fun mismatchedPartsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { ApiKey.decode(ByteArray(2), ByteArray(3)) }
    }

    @Test
    fun buildConfigPartsHaveSameLength() {
        assertEquals(BuildConfig.WPC_KEY_A.size, BuildConfig.WPC_KEY_B.size)
    }
}
