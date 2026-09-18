package com.zx.homecamera.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PasswordHasherTest {
    @Test
    fun sha256HexMatchesKnownVector() {
        // SHA-256("") 已知摘要。
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            PasswordHasher.sha256Hex(""),
        )
    }

    @Test
    fun sha256HexIsDeterministic() {
        assertEquals(
            PasswordHasher.sha256Hex("家庭监控密码123"),
            PasswordHasher.sha256Hex("家庭监控密码123"),
        )
    }

    @Test
    fun differentPasswordsProduceDifferentHashes() {
        assertNotEquals(
            PasswordHasher.sha256Hex("password-a"),
            PasswordHasher.sha256Hex("password-b"),
        )
    }
}
