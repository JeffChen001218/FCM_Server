package org.example

import com.google.firebase.messaging.AndroidConfig
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SenderConfigTest {
    @Test
    fun `loadSenderConfig reads list document and resolves relative paths`() {
        val configDirectory = createTempDirectory().toFile()
        val configFile = configDirectory.resolve("fcm-sender.json")
        configFile.writeText(
            """
            {
              "configs": [
                {
                  "id": "news",
                  "name": " News sender ",
                  "enabled": true,
                  "googleServiceAccount": { "path": "google-service-account.json" },
                  "pollIntervalSeconds": 30,
                  "topic": "/topics/news",
                  "notification": { "title": " Title ", "body": " Body " },
                  "data": { "type": "custom" }
                }
              ]
            }
            """.trimIndent(),
        )

        val document = SenderConfigStore(configFile).read()
        val config = document.configs.orEmpty().single()

        assertEquals(9999, document.serverPort)
        assertEquals("news", config.id)
        assertEquals("News sender", config.name)
        assertEquals(true, config.enabled)
        assertEquals("news", config.topic)
        assertEquals(" Title ", config.notification?.title)
        assertEquals(" Body ", config.notification?.body)
        assertEquals(configDirectory.resolve("google-service-account.json").path, config.googleServiceAccount?.path)
    }

    @Test
    fun `validated rejects duplicate config ids`() {
        val error = assertFailsWith<IllegalArgumentException> {
            SenderConfigDocument(
                configs = listOf(
                    minimalConfig("duplicate"),
                    minimalConfig("duplicate"),
                ),
            ).validated(configDirectory = createTempDirectory().toFile())
        }

        assertTrue(error.message?.contains("Duplicate ids: duplicate") == true)
    }

    @Test
    fun `validated rejects config without notification or data`() {
        val error = assertFailsWith<IllegalArgumentException> {
            SenderConfig(
                id = "news",
                googleServiceAccount = GoogleServiceAccountConfig("google-service-account.json"),
                pollIntervalSeconds = 30,
                topic = "news",
            ).validated(configDirectory = createTempDirectory().toFile())
        }

        assertEquals("At least one of notification or data must be provided for 'news'.", error.message)
    }

    @Test
    fun `validated accepts special blank notification text without trimming`() {
        val specialBlank = "  "
        val config = minimalConfig("news").copy(
            notification = NotificationConfig(specialBlank, specialBlank),
        ).validated(configDirectory = createTempDirectory().toFile())

        assertEquals(specialBlank, config.notification?.title)
        assertEquals(specialBlank, config.notification?.body)
    }

    @Test
    fun `validated rejects truly empty notification text`() {
        val error = assertFailsWith<IllegalArgumentException> {
            minimalConfig("news").copy(
                notification = NotificationConfig("", "body"),
            ).validated(configDirectory = createTempDirectory().toFile())
        }

        assertEquals("notification.title must not be empty.", error.message)
    }

    @Test
    fun `write persists service account paths relative to config file`() {
        val configDirectory = createTempDirectory().toFile()
        val configFile = configDirectory.resolve("fcm-sender.json")
        val serviceAccount = configDirectory.resolve("google-service-account.json")

        val saved = SenderConfigStore(configFile).write(
            SenderConfigDocument(
                configs = listOf(
                    minimalConfig("news").copy(
                        googleServiceAccount = GoogleServiceAccountConfig(serviceAccount.absolutePath),
                    ),
                ),
            ),
        )

        assertEquals("google-service-account.json", saved.configs.orEmpty().single().googleServiceAccount?.path)
        assertTrue(configFile.readText().contains("\"path\": \"google-service-account.json\""))
    }

    @Test
    fun `validated defaults service account path to item name directory`() {
        val configDirectory = createTempDirectory().toFile()
        val config = minimalConfig("trash").copy(
            name = "Trash Shield",
            googleServiceAccount = null,
        ).validated(configDirectory)

        assertEquals(
            configDirectory.resolve("Trash Shield").resolve("google-service-account.json").path,
            config.googleServiceAccount?.path,
        )
    }

    @Test
    fun `display response keeps loaded config path and original relative paths`() {
        val configDirectory = createTempDirectory().toFile()
        val configFile = configDirectory.resolve("nested").resolve("fcm-sender.json")
        val serviceAccount = configFile.parentFile.resolve("google-service-account.json")
        val store = SenderConfigStore(configFile)

        val saved = store.write(
            SenderConfigDocument(
                configs = listOf(
                    minimalConfig("news").copy(
                        googleServiceAccount = GoogleServiceAccountConfig(serviceAccount.absolutePath),
                    ),
                ),
            ),
        )
        val response = store.toDisplayResponse(saved)

        assertEquals(configFile.toPath().toAbsolutePath().normalize().toString(), response.configPath)
        assertEquals("google-service-account.json", response.configs.orEmpty().single().googleServiceAccount?.path)
    }

    @Test
    fun `write persists review status check metadata`() {
        val configDirectory = createTempDirectory().toFile()
        val configFile = configDirectory.resolve("fcm-sender.json")
        val store = SenderConfigStore(configFile)

        val saved = store.write(
            SenderConfigDocument(
                configs = listOf(
                    minimalConfig("news").copy(
                        reviewStatusCheck = ReviewStatusCheckConfig(
                            packageName = " com.example.news ",
                            track = " production ",
                            lastVersion = " 20260601 ",
                            lastCheckedAt = " 2026-06-02T00:00:00Z ",
                            lastStatus = ReviewStatusCheckStatus.ONLINE,
                            lastResult = " VersionCode 20260601 is online. ",
                        ),
                    ),
                ),
            ),
        )
        val reviewStatusCheck = saved.configs.orEmpty().single().reviewStatusCheck

        assertEquals("com.example.news", reviewStatusCheck?.packageName)
        assertEquals("production", reviewStatusCheck?.track)
        assertEquals("20260601", reviewStatusCheck?.lastVersion)
        assertEquals("2026-06-02T00:00:00Z", reviewStatusCheck?.lastCheckedAt)
        assertEquals(ReviewStatusCheckStatus.ONLINE, reviewStatusCheck?.lastStatus)
        assertEquals("VersionCode 20260601 is online.", reviewStatusCheck?.lastResult)
        assertTrue(configFile.readText().contains("\"reviewStatusCheck\""))
    }

    @Test
    fun `review status check without package name records first failure result`() {
        val checked = ReviewStatusChecker().check(
            minimalConfig("news").copy(reviewStatusCheck = ReviewStatusCheckConfig()),
            versionCode = 20260601,
        )
        val config = checked.config

        assertEquals("20260601", config.lastVersion)
        assertEquals(ReviewStatusCheckStatus.FAILED, config.lastStatus)
        assertTrue(config.lastCheckedAt?.isNotBlank() == true)
        assertEquals("Google Play package name is not configured.", config.lastResult)
    }

    @Test
    fun `review status check preserves previous successful result after later failure`() {
        val checked = ReviewStatusChecker { _, _, _ -> error("network down") }.check(
            minimalConfig("news").copy(
                reviewStatusCheck = ReviewStatusCheckConfig(
                    packageName = "com.example.news",
                    track = "production",
                    lastVersion = "20260601",
                    lastCheckedAt = "2026-06-02T00:00:00Z",
                    lastStatus = ReviewStatusCheckStatus.ONLINE,
                    lastResult = "VersionCode 20260601 is online on production.",
                ),
            ),
            versionCode = 20260602,
        )
        val config = checked.config

        assertEquals("network down", checked.error)
        assertEquals("20260601", config.lastVersion)
        assertEquals("2026-06-02T00:00:00Z", config.lastCheckedAt)
        assertEquals(ReviewStatusCheckStatus.ONLINE, config.lastStatus)
        assertEquals("VersionCode 20260601 is online on production.", config.lastResult)
    }

    @Test
    fun `review status check marks published release as online`() {
        val checked = ReviewStatusChecker { _, _, _ -> AppStatusLookupResult(AppStatus.ONLINE, lifecycleState = "RELEASE_LIFECYCLE_STATE_PUBLISHED") }.check(
            minimalConfig("news").copy(
                reviewStatusCheck = ReviewStatusCheckConfig(
                    packageName = "com.example.news",
                    track = "production",
                ),
            ),
            versionCode = 20260601,
        )

        assertEquals(ReviewStatusCheckStatus.ONLINE, checked.config.lastStatus)
        assertTrue(checked.config.lastResult?.contains("Track production is online") == true)
    }

    @Test
    fun `published release lifecycle state is treated as online`() {
        assertEquals(true, isOnlineReleaseLifecycleState("RELEASE_LIFECYCLE_STATE_PUBLISHED"))
        assertEquals(false, isOnlineReleaseLifecycleState("completed"))
        assertEquals(false, isOnlineReleaseLifecycleState("inProgress"))
    }

    @Test
    fun `review status check maps in review state`() {
        val checked = ReviewStatusChecker { _, _, _ ->
            AppStatusLookupResult(AppStatus.IN_REVIEW, lifecycleState = "RELEASE_LIFECYCLE_STATE_IN_REVIEW")
        }.check(
            minimalConfig("news").copy(
                reviewStatusCheck = ReviewStatusCheckConfig(packageName = "com.example.news", track = "production"),
            ),
            versionCode = 20260601,
        )

        assertEquals(ReviewStatusCheckStatus.IN_REVIEW, checked.config.lastStatus)
        assertTrue(checked.config.lastResult?.contains("in review or pending publish") == true)
    }

    @Test
    fun `review status check maps rejected state`() {
        val checked = ReviewStatusChecker { _, _, _ ->
            AppStatusLookupResult(AppStatus.REJECTED, lifecycleState = "RELEASE_LIFECYCLE_STATE_NOT_APPROVED")
        }.check(
            minimalConfig("news").copy(
                reviewStatusCheck = ReviewStatusCheckConfig(packageName = "com.example.news", track = "production"),
            ),
            versionCode = 20260601,
        )

        assertEquals(ReviewStatusCheckStatus.REJECTED, checked.config.lastStatus)
    }

    @Test
    fun `review status check preserves previous result for permission denied`() {
        val checked = ReviewStatusChecker { _, _, _ ->
            AppStatusLookupResult(AppStatus.PERMISSION_DENIED, detail = "The caller does not have permission")
        }.check(
            minimalConfig("news").copy(
                reviewStatusCheck = ReviewStatusCheckConfig(
                    packageName = "com.example.news",
                    track = "production",
                    lastVersion = "20260601",
                    lastCheckedAt = "2026-06-02T00:00:00Z",
                    lastStatus = ReviewStatusCheckStatus.ONLINE,
                    lastResult = "Track production is online for package status query.",
                ),
            ),
            versionCode = 20260602,
        )

        assertEquals(ReviewStatusCheckStatus.PERMISSION_DENIED, checked.config.lastStatus)
        assertTrue(checked.config.lastResult?.contains("permission denied") == true)
    }

    @Test
    fun `review status check defaults track to alpha`() {
        val reviewStatusCheck = ReviewStatusCheckConfig(track = null).validated()

        assertEquals("alpha", reviewStatusCheck.track)
    }

    @Test
    fun `review status check rejects unsupported track`() {
        val error = assertFailsWith<IllegalArgumentException> {
            ReviewStatusCheckConfig(track = "product").validated()
        }

        assertEquals("reviewStatusCheck.track must be one of production, beta, internal, alpha.", error.message)
    }

    @Test
    fun `service account falls back to firebase adminsdk file in same directory`() {
        val configDirectory = createTempDirectory().toFile()
        val missingConfiguredFile = configDirectory.resolve("google-firebase-account.json")
        val fallbackFile = configDirectory.resolve("project-firebase-adminsdk-abc-123.json").apply {
            writeText("{}")
        }
        configDirectory.resolve("not-a-service-account.json").writeText("{}")

        val resolvedFile = GoogleServiceAccountConfig(missingConfiguredFile.path).resolveFile()

        assertEquals(fallbackFile.absoluteFile, resolvedFile)
    }

    @Test
    fun `android config ttl defaults to ten seconds`() {
        val androidConfig = AndroidMessageConfig().toFirebaseConfig(notification = null)

        assertEquals("10s", androidConfig.privateFieldValue("ttl"))
        assertEquals("high", androidConfig.privateFieldValue("priority"))
    }

    private fun AndroidConfig.privateFieldValue(name: String): Any? {
        val field = AndroidConfig::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.get(this)
    }

    private fun minimalConfig(id: String): SenderConfig = SenderConfig(
        id = id,
        googleServiceAccount = GoogleServiceAccountConfig("google-service-account.json"),
        pollIntervalSeconds = 30,
        topic = "news",
        notification = NotificationConfig("Title", "Body"),
    )
}
