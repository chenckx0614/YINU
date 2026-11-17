package com.yinu.app

import com.yinu.app.sync.CloudHttpError
import com.yinu.app.sync.CloudSetupError
import com.yinu.app.sync.authFailureMessage
import com.yinu.app.sync.cloudConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudLoginTest {
    @Test
    fun pastedDataApiAddressUsesTheProjectOriginForAuth() {
        val origin = "https://example.supabase.co"
        val key = "sb_publishable_example_public_key"
        assertEquals(origin, cloudConfig("  $origin/rest/v1/\n", key).project)
        assertEquals(origin, cloudConfig(origin, key).project)
        assertThrows(CloudSetupError::class.java) { cloudConfig("$origin/rest/v1/records", key) }
        assertThrows(CloudSetupError::class.java) { cloudConfig("$origin/rest/v1/?key=test", key) }
    }

    @Test
    fun distinguishesAnUnverifiedEmailFromMissingCredentials() {
        assertTrue(authFailureMessage(CloudHttpError(400, "email_not_confirmed"), false).contains("邮箱还没验证"))
        assertTrue(authFailureMessage(CloudHttpError(400, "invalid_credentials"), false).contains("注册账号"))
    }
}
