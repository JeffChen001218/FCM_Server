package org.example

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.io.path.createTempDirectory

class SenderConfigTest {
    @Test
    fun `validated strips topic prefix and resolves relative service account path`() {
        val configDirectory = createTempDirectory().toFile()
        val config = SenderConfig(
            googleServiceAccount = GoogleServiceAccountConfig("google-service-account.json"),
            pollIntervalSeconds = 30,
            topic = "/topics/news",
            notification = NotificationConfig(" Title ", " Body "),
        ).validated(configDirectory = configDirectory)

        assertEquals("news", config.topic)
        assertEquals("Title", config.notification?.title)
        assertEquals("Body", config.notification?.body)
        assertEquals(configDirectory.resolve("google-service-account.json").path, config.googleServiceAccount?.path)
    }

    @Test
    fun `validated rejects config without notification or data`() {
        val error = assertFailsWith<IllegalArgumentException> {
            SenderConfig(
                googleServiceAccount = GoogleServiceAccountConfig("google-service-account.json"),
                pollIntervalSeconds = 30,
                topic = "news",
            ).validated(configDirectory = createTempDirectory().toFile())
        }

        assertEquals("At least one of notification or data must be provided.", error.message)
    }
}
