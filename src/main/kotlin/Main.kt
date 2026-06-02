package org.example

import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.androidpublisher.AndroidPublisher
import com.google.api.services.androidpublisher.model.ReleaseSummary
import com.google.auth.oauth2.GoogleCredentials
import com.google.auth.http.HttpCredentialsAdapter
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.AndroidConfig
import com.google.firebase.messaging.AndroidNotification
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.Message
import com.google.firebase.messaging.Notification
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonParseException
import com.google.gson.annotations.SerializedName
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

private const val DEFAULT_CONFIG_PATH = "config/fcm-sender.json"
private const val DEFAULT_SERVER_PORT = 9999
private const val GOOGLE_SERVICE_ACCOUNT_FILE_NAME = "google-service-account.json"
private const val DEFAULT_POLL_INTERVAL_SECONDS = 20L
private const val DEFAULT_NOTIFICATION_TEXT = "  "
private const val FCM_MESSAGE_TTL_SECONDS = 10L
private const val DEFAULT_REVIEW_TRACK = "alpha"
private const val GOOGLE_PLAY_APPLICATION_NAME = "FCM_Server/1.0"
private val configJson: Gson = GsonBuilder().setPrettyPrinting().create()

private fun defaultGoogleServiceAccountPath(itemName: String): String =
    "$itemName/$GOOGLE_SERVICE_ACCOUNT_FILE_NAME"

private val supportedReviewTracks = linkedMapOf(
    "production" to "正式版 (Production)",
    "beta" to "开放式测试 (Open Testing)",
    "internal" to "内部测试 (Internal Testing)",
    "alpha" to "封闭式测试 (Closed Testing)",
)

private fun validateReviewTrack(track: String?): String {
    val normalized = track?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: DEFAULT_REVIEW_TRACK
    require(normalized in supportedReviewTracks) {
        "reviewStatusCheck.track must be one of ${supportedReviewTracks.keys.joinToString()}."
    }
    return normalized
}

private fun normalizeReviewReleaseState(state: String?): String =
    state?.trim()?.uppercase()?.replace('-', '_').orEmpty()

internal fun isOnlineReleaseLifecycleState(state: String?): Boolean =
    normalizeReviewReleaseState(state) == "RELEASE_LIFECYCLE_STATE_PUBLISHED"

enum class AppStatus {
    ONLINE,
    IN_REVIEW,
    REJECTED,
    REMOVED_OR_NOT_FOUND,
    QUOTA_EXCEEDED,
    PERMISSION_DENIED,
    UNKNOWN,
}

data class AppStatusLookupResult(
    val status: AppStatus,
    val detail: String? = null,
    val lifecycleState: String? = null,
    val releaseName: String? = null,
) {
    fun toReviewStatusCheckStatus(): ReviewStatusCheckStatus = when (status) {
        AppStatus.ONLINE -> ReviewStatusCheckStatus.ONLINE
        AppStatus.IN_REVIEW -> ReviewStatusCheckStatus.IN_REVIEW
        AppStatus.REJECTED -> ReviewStatusCheckStatus.REJECTED
        AppStatus.REMOVED_OR_NOT_FOUND -> ReviewStatusCheckStatus.REMOVED_OR_NOT_FOUND
        AppStatus.QUOTA_EXCEEDED -> ReviewStatusCheckStatus.QUOTA_EXCEEDED
        AppStatus.PERMISSION_DENIED -> ReviewStatusCheckStatus.PERMISSION_DENIED
        AppStatus.UNKNOWN -> ReviewStatusCheckStatus.UNKNOWN
    }

    fun describe(track: String, versionCode: Long): String {
        val lifecycleText = lifecycleState?.let { " lifecycleState=$it." }.orEmpty()
        val releaseText = releaseName?.takeIf { it.isNotBlank() }?.let { " release=$it." }.orEmpty()
        val detailText = detail?.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
        return when (status) {
            AppStatus.ONLINE -> "Track $track is online for package status query. Entered versionCode $versionCode.$lifecycleText$releaseText$detailText".trim()
            AppStatus.IN_REVIEW -> "Track $track is still in review or pending publish. Entered versionCode $versionCode.$lifecycleText$releaseText$detailText".trim()
            AppStatus.REJECTED -> "Track $track was rejected in review. Entered versionCode $versionCode.$lifecycleText$releaseText$detailText".trim()
            AppStatus.REMOVED_OR_NOT_FOUND -> "Track $track or package was removed, blocked, or not found. Entered versionCode $versionCode.$detailText".trim()
            AppStatus.QUOTA_EXCEEDED -> "Google Play API quota exceeded while checking track $track. Entered versionCode $versionCode.$detailText".trim()
            AppStatus.PERMISSION_DENIED -> "Google Play API permission denied while checking track $track. Entered versionCode $versionCode.$detailText".trim()
            AppStatus.UNKNOWN -> "Track $track returned an unknown status for entered versionCode $versionCode.$lifecycleText$releaseText$detailText".trim()
        }
    }
}

fun main(args: Array<String>) {
    val configPath = args.firstOrNull()
        ?: System.getenv("FCM_SENDER_CONFIG")
        ?: DEFAULT_CONFIG_PATH
    println("config file path: $configPath")
    val configFile = File(configPath)
    val store = SenderConfigStore(configFile)
    val initialDocument = store.read()
    val appRegistry = FirebaseAppRegistry()
    val sender = FcmTopicSender(appRegistry)
    val scheduler = FcmScheduler(store, sender)
    val webServer = ConfigWebServer(store, scheduler::requestReload)

    val serverPort = initialDocument.serverPort ?: DEFAULT_SERVER_PORT
    webServer.start(serverPort)
    scheduler.start()

    println("FCM sender started.")
    println("Config file: ${configFile.absolutePath}")
    println("Config reload interval: ${FcmScheduler.RELOAD_INTERVAL.seconds}s")
    println("Web console: http://127.0.0.1:$serverPort/")
    println("Press Ctrl+C to stop.")

    Runtime.getRuntime().addShutdownHook(
        Thread {
            webServer.stop()
            scheduler.stop()
        },
    )

    while (true) {
        Thread.sleep(Duration.ofHours(1).toMillis())
    }
}

class SenderConfigStore(
    private val file: File,
) {
    val configPath: String
        get() = file.toPath().toAbsolutePath().normalize().toString()

    private val configDirectory: File
        get() = file.parentFile ?: File(".")

    @Synchronized
    fun read(): SenderConfigDocument {
        require(file.exists()) {
            "Config file not found: ${file.absolutePath}. Copy config/fcm-sender.example.json to $DEFAULT_CONFIG_PATH and fill it in."
        }
        require(file.isFile) { "Config path is not a file: ${file.absolutePath}" }

        val document = try {
            requireNotNull(configJson.fromJson(file.readText(), SenderConfigDocument::class.java)) {
                "Config file must contain a JSON object."
            }
        } catch (error: JsonParseException) {
            throw IllegalArgumentException("Config JSON parse failed: ${error.message}", error)
        }

        return document.validated(configDirectory)
    }

    @Synchronized
    fun readForDisplay(): SenderConfigDocument {
        return read().toPersisted(configDirectory)
    }

    @Synchronized
    fun write(document: SenderConfigDocument): SenderConfigDocument {
        val validatedDocument = document.validated(configDirectory)
        file.parentFile?.mkdirs()
        file.writeText(configJson.toJson(validatedDocument.toPersisted(configDirectory)) + "\n")
        return readForDisplay()
    }

    fun toDisplayResponse(document: SenderConfigDocument): SenderConfigResponse =
        SenderConfigResponse(
            configPath = configPath,
            serverPort = document.serverPort,
            configs = document.configs,
        )
}

class FcmScheduler(
    private val store: SenderConfigStore,
    private val sender: FcmTopicSender,
) {
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "fcm-scheduler").apply { isDaemon = false }
    }
    private val reloadSignal = AtomicLong(0)
    @Volatile
    private var running = true
    private var loadedOnce = false
    private var seenReloadSignal = -1L
    private var configs: Map<String, SenderConfig> = emptyMap()
    private val nextRunAtById = mutableMapOf<String, Instant>()

    fun start() {
        worker.submit { runLoop() }
    }

    fun stop() {
        running = false
        worker.shutdownNow()
    }

    fun requestReload() {
        reloadSignal.incrementAndGet()
    }

    private fun runLoop() {
        while (running) {
            runCatching {
                reloadIfDue()
                runDueConfigs()
            }.onFailure { error ->
                System.err.println("[${Instant.now()}] Scheduler error: ${error.message}")
                error.printStackTrace()
            }

            Thread.sleep(1_000)
        }
    }

    private fun reloadIfDue() {
        val signal = reloadSignal.get()
        val shouldReload = !loadedOnce || signal != seenReloadSignal || lastReloadAt.elapsedAtLeast(RELOAD_INTERVAL)
        if (!shouldReload) {
            return
        }

        val document = store.read()
        val normalizedConfigs = document.configs.orEmpty()
        val activeIds = normalizedConfigs.map { requireNotNull(it.id) }.toSet()
        nextRunAtById.keys.retainAll(activeIds)
        configs = normalizedConfigs.associateBy { requireNotNull(it.id) }
        loadedOnce = true
        seenReloadSignal = signal
        lastReloadAt = Instant.now()

        println("[${Instant.now()}] Loaded ${configs.size} FCM config(s), enabled=${configs.values.count { it.enabled == true }}")
    }

    private var lastReloadAt: Instant = Instant.EPOCH

    private fun runDueConfigs() {
        val now = Instant.now()
        configs.values
            .filter { it.enabled == true }
            .forEach { config ->
                val id = requireNotNull(config.id)
                val nextRunAt = nextRunAtById[id] ?: now
                if (now.isBefore(nextRunAt)) {
                    return@forEach
                }

                runCatching {
                    sender.send(config)
                }.onSuccess { messageId ->
                    println("[${Instant.now()}] Sent '${config.displayName}' to topic '${config.topic}', messageId=$messageId")
                }.onFailure { error ->
                    System.err.println("[${Instant.now()}] Send failed for '${config.displayName}': ${error.message}")
                    error.printStackTrace()
                }

                nextRunAtById[id] = Instant.now().plus(config.pollInterval)
            }
    }

    companion object {
        val RELOAD_INTERVAL: Duration = Duration.ofMinutes(1)
    }
}

class ConfigWebServer(
    private val store: SenderConfigStore,
    private val onConfigChanged: () -> Unit,
    private val reviewStatusChecker: ReviewStatusChecker = ReviewStatusChecker(),
) {
    private var server: HttpServer? = null

    fun start(port: Int) {
        val httpServer = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
        httpServer.executor = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "fcm-web").apply { isDaemon = true }
        }
        httpServer.createContext("/") { exchange ->
            when {
                exchange.requestMethod == "GET" && exchange.requestURI.path == "/" -> exchange.respondHtml(MANAGEMENT_PAGE)
                exchange.requestMethod == "GET" && exchange.requestURI.path == "/api/config" -> handleReadConfig(exchange)
                exchange.requestMethod == "PUT" && exchange.requestURI.path == "/api/config" -> handleWriteConfig(exchange)
                exchange.requestMethod == "POST" && exchange.requestURI.path == "/api/review-status/check" -> handleReviewStatusCheck(exchange)
                else -> exchange.respondText(404, "Not found")
            }
        }
        httpServer.start()
        server = httpServer
    }

    fun stop() {
        server?.stop(0)
    }

    private fun handleReadConfig(exchange: HttpExchange) {
        runCatching {
            store.readForDisplay()
        }.onSuccess { document ->
            exchange.respondJson(200, store.toDisplayResponse(document))
        }.onFailure { error ->
            exchange.respondJson(500, ErrorResponse(error.message ?: "Config read failed."))
        }
    }

    private fun handleWriteConfig(exchange: HttpExchange) {
        val body = exchange.requestBody.bufferedReader().use { it.readText() }
        val document = runCatching {
            requireNotNull(configJson.fromJson(body, SenderConfigDocument::class.java)) {
                "Request body must contain a JSON object."
            }
        }.getOrElse { error ->
            exchange.respondJson(400, ErrorResponse("Invalid JSON: ${error.message}"))
            return
        }

        runCatching {
            store.write(document)
        }.onSuccess { saved ->
            onConfigChanged()
            exchange.respondJson(200, store.toDisplayResponse(saved))
        }.onFailure { error ->
            exchange.respondJson(400, ErrorResponse(error.message ?: "Config save failed."))
        }
    }

    private fun handleReviewStatusCheck(exchange: HttpExchange) {
        val body = exchange.requestBody.bufferedReader().use { it.readText() }
        val request = runCatching {
            requireNotNull(configJson.fromJson(body, ReviewStatusCheckRequest::class.java)) {
                "Request body must contain a JSON object."
            }
        }.getOrElse { error ->
            exchange.respondJson(400, ErrorResponse("Invalid JSON: ${error.message}"))
            return
        }

        runCatching {
            val configId = request.configId?.trim()?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("configId must be provided.")
            val version = request.version?.trim()?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("version must be provided.")
            val versionCode = version.toLongOrNull()
                ?: throw IllegalArgumentException("version must be a numeric Google Play versionCode.")
            require(versionCode > 0) { "version must be a positive Google Play versionCode." }

            val resolvedDocument = store.read()
            val displayDocument = store.readForDisplay()
            val resolvedConfigs = resolvedDocument.configs.orEmpty()
            val displayConfigs = displayDocument.configs.orEmpty()
            val configIndex = displayConfigs.indexOfFirst { it.id == configId }
            require(configIndex >= 0) { "Config not found: $configId" }
            val resolvedConfig = resolvedConfigs.firstOrNull { it.id == configId }
                ?: throw IllegalArgumentException("Config not found: $configId")

            val updatedConfigs = displayConfigs.toMutableList()
            val currentDisplayConfig = updatedConfigs[configIndex]
            val requestedReviewStatusCheck = (currentDisplayConfig.reviewStatusCheck ?: ReviewStatusCheckConfig()).copy(
                packageName = request.packageName?.trim()?.takeIf { it.isNotBlank() },
                track = request.track?.trim()?.takeIf { it.isNotBlank() },
            )
            val configForCheck = resolvedConfig.copy(
                reviewStatusCheck = (resolvedConfig.reviewStatusCheck ?: ReviewStatusCheckConfig()).copy(
                    packageName = request.packageName?.trim()?.takeIf { it.isNotBlank() },
                    track = request.track?.trim()?.takeIf { it.isNotBlank() },
                ),
            )
            val checkResult = reviewStatusChecker.check(configForCheck, versionCode)
            updatedConfigs[configIndex] = currentDisplayConfig.copy(
                reviewStatusCheck = requestedReviewStatusCheck.copy(
                    lastVersion = checkResult.config.lastVersion,
                    lastCheckedAt = checkResult.config.lastCheckedAt,
                    lastStatus = checkResult.config.lastStatus,
                    lastResult = checkResult.config.lastResult,
                ),
            )

            val saved = store.write(displayDocument.copy(configs = updatedConfigs))
            ReviewStatusCheckResponse.from(store.toDisplayResponse(saved), checkResult.error)
        }.onSuccess { saved ->
            exchange.respondJson(200, saved)
        }.onFailure { error ->
            System.err.println("[${Instant.now()}] Review status check request failed: ${error.message}")
            error.printStackTrace()
            exchange.respondJson(400, ErrorResponse(error.message ?: "Review status check failed."))
        }
    }
}

class FirebaseAppRegistry {
    private val appsByCredentialPath = ConcurrentHashMap<String, FirebaseApp>()

    fun messaging(serviceAccountConfig: GoogleServiceAccountConfig): FirebaseMessaging {
        val serviceAccountFile = serviceAccountConfig.resolveFile()
        require(serviceAccountFile.exists()) {
            "Google service account JSON not found: ${serviceAccountFile.absolutePath}"
        }

        val app = appsByCredentialPath.computeIfAbsent(serviceAccountFile.canonicalPath) { credentialPath ->
            serviceAccountFile.inputStream().use { serviceAccount ->
                val options = FirebaseOptions.builder()
                    .setCredentials(GoogleCredentials.fromStream(serviceAccount))
                    .build()

                FirebaseApp.initializeApp(options, appNameFor(credentialPath))
            }
        }

        return FirebaseMessaging.getInstance(app)
    }

    private fun appNameFor(credentialPath: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(credentialPath.toByteArray())
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(digest).take(16)
        return "fcm-sender-$encoded"
    }
}

class FcmTopicSender(
    private val appRegistry: FirebaseAppRegistry,
) {
    fun send(config: SenderConfig): String {
        val request = config.toMessageRequest()
        val messaging = appRegistry.messaging(config.requireServiceAccount())
        val messageBuilder = Message.builder()
            .setTopic(request.topic)

        request.notification?.let { notification ->
            val notificationBuilder = Notification.builder()
                .setTitle(notification.title)
                .setBody(notification.body)

            notification.imageUrl?.let { notificationBuilder.setImage(it) }

            messageBuilder.setNotification(
                notificationBuilder.build(),
            )
        }

        if (request.data.isNotEmpty()) {
            messageBuilder.putAllData(request.data)
        }

        request.android?.let { android ->
            messageBuilder.setAndroidConfig(android.toFirebaseConfig(request.notification))
        }

        return messaging.send(messageBuilder.build())
    }
}

class ReviewStatusChecker(
    private val appStatusLookup: (SenderConfig, ReviewStatusCheckConfig, Long) -> AppStatusLookupResult = { config, reviewStatusCheck, versionCode ->
        checkAppStatus(config, reviewStatusCheck, versionCode)
    },
) {
    fun check(config: SenderConfig, versionCode: Long): ReviewStatusCheckResult {
        val base = config.reviewStatusCheck?.validated() ?: ReviewStatusCheckConfig()
        val checkedAt = Instant.now().toString()
        val packageName = base.packageName?.takeIf { it.isNotBlank() }

        if (packageName == null) {
            logReviewStatusFailure(
                config = config,
                reviewStatusCheck = base,
                versionCode = versionCode,
                message = "Google Play package name is not configured.",
                preservedPrevious = base.hasDisplayedResult(),
            )
            return base.failureResult(
                checkedAt = checkedAt,
                versionCode = versionCode,
                message = "Google Play package name is not configured.",
            )
        }

        return runCatching {
            val lookupResult = appStatusLookup(config, base, versionCode)
            ReviewStatusCheckResult(
                config = base.copy(
                    lastVersion = versionCode.toString(),
                    lastCheckedAt = checkedAt,
                    lastStatus = lookupResult.toReviewStatusCheckStatus(),
                    lastResult = lookupResult.describe(track = base.track ?: DEFAULT_REVIEW_TRACK, versionCode = versionCode),
                ),
            )
        }.getOrElse { error ->
            if (error is InterruptedException) {
                Thread.currentThread().interrupt()
            }
            val message = error.message ?: error.javaClass.simpleName
            logReviewStatusFailure(
                config = config,
                reviewStatusCheck = base,
                versionCode = versionCode,
                message = message,
                preservedPrevious = base.hasDisplayedResult(),
                error = error,
            )
            base.failureResult(
                checkedAt = checkedAt,
                versionCode = versionCode,
                message = message,
            )
        }
    }

    private fun logReviewStatusFailure(
        config: SenderConfig,
        reviewStatusCheck: ReviewStatusCheckConfig,
        versionCode: Long,
        message: String,
        preservedPrevious: Boolean,
        error: Throwable? = null,
    ) {
        val packageName = reviewStatusCheck.packageName ?: "(not configured)"
        val track = reviewStatusCheck.track ?: DEFAULT_REVIEW_TRACK
        val preservedText = if (preservedPrevious) ", preservedPreviousResult=true" else ""
        System.err.println(
            "[${Instant.now()}] Review status check failed for '${config.displayName}' " +
                "(id=${config.id}, package=$packageName, track=$track, versionCode=$versionCode$preservedText): $message",
        )
        error?.printStackTrace()
    }

    private fun ReviewStatusCheckConfig.hasDisplayedResult(): Boolean =
        lastStatus != null && lastStatus != ReviewStatusCheckStatus.FAILED

    private fun ReviewStatusCheckConfig.failureResult(
        checkedAt: String,
        versionCode: Long,
        message: String,
    ): ReviewStatusCheckResult =
        if (hasDisplayedResult()) {
            ReviewStatusCheckResult(config = this, error = message)
        } else {
            ReviewStatusCheckResult(
                config = copy(
                    lastVersion = versionCode.toString(),
                    lastCheckedAt = checkedAt,
                    lastStatus = ReviewStatusCheckStatus.FAILED,
                    lastResult = message,
                ),
            )
        }

    companion object {
        private fun checkAppStatus(
            config: SenderConfig,
            reviewStatusCheck: ReviewStatusCheckConfig,
            versionCode: Long,
        ): AppStatusLookupResult {
            val serviceAccountFile = config.requireServiceAccount().resolveFile()
            require(serviceAccountFile.exists()) {
                "Google service account JSON not found: ${serviceAccountFile.absolutePath}"
            }

            val publisher = createAndroidPublisher(serviceAccountFile)
            val packageName = requireNotNull(reviewStatusCheck.packageName) { "Google Play package name is not configured." }
            val targetTrack = validateReviewTrack(reviewStatusCheck.track)
            val trackParent = "applications/$packageName/tracks/$targetTrack"

            try {
                val releaseSummaries = publisher.applications()
                    .tracks()
                    .releases()
                    .list(trackParent)
                    .execute()
                logGooglePlayReleaseResponse(packageName, targetTrack, versionCode, releaseSummaries)
                val releases = releaseSummaries?.releases.orEmpty()
                val latestRelease = releases.firstOrNull()
                    ?: return AppStatusLookupResult(
                        status = AppStatus.UNKNOWN,
                        detail = "Google Play returned no releases for track '$targetTrack'.",
                    )
                val lifecycleState = resolveLifecycleState(latestRelease)

                return AppStatusLookupResult(
                    status = mapLifecycleStateToAppStatus(lifecycleState),
                    lifecycleState = lifecycleState,
                    releaseName = latestRelease.releaseName,
                )
            } catch (error: GoogleJsonResponseException) {
                logGooglePlayErrorResponse(packageName, targetTrack, versionCode, error)
                return error.toAppStatusLookupResult()
            } catch (error: IOException) {
                return AppStatusLookupResult(
                    status = AppStatus.UNKNOWN,
                    detail = error.message ?: "Google Play Developer API request failed for $trackParent.",
                )
            } catch (error: Exception) {
                return AppStatusLookupResult(
                    status = AppStatus.UNKNOWN,
                    detail = error.message ?: error.javaClass.simpleName,
                )
            }
        }

        private fun resolveLifecycleState(release: ReleaseSummary): String? =
            release.get("releaseLifecycleState")?.toString()
                ?: release.get("status")?.toString()

        private fun mapLifecycleStateToAppStatus(state: String?): AppStatus =
            when (normalizeReviewReleaseState(state)) {
                "RELEASE_LIFECYCLE_STATE_PUBLISHED", "COMPLETED" -> AppStatus.ONLINE
                "RELEASE_LIFECYCLE_STATE_IN_REVIEW",
                "RELEASE_LIFECYCLE_STATE_APPROVED_NOT_PUBLISHED",
                "RELEASE_LIFECYCLE_STATE_NOT_SENT_FOR_REVIEW",
                "DRAFT",
                -> AppStatus.IN_REVIEW
                "RELEASE_LIFECYCLE_STATE_NOT_APPROVED" -> AppStatus.REJECTED
                else -> AppStatus.ONLINE
            }

        private fun GoogleJsonResponseException.toAppStatusLookupResult(): AppStatusLookupResult {
            val detail = details?.message ?: statusMessage ?: message ?: "Google Play Developer API request failed."
            return when (statusCode) {
                404 -> AppStatusLookupResult(status = AppStatus.REMOVED_OR_NOT_FOUND, detail = detail)
                403 -> {
                    if (isQuotaExceeded(details?.message, details?.errors?.firstOrNull()?.reason)) {
                        AppStatusLookupResult(status = AppStatus.QUOTA_EXCEEDED, detail = detail)
                    } else {
                        AppStatusLookupResult(status = AppStatus.PERMISSION_DENIED, detail = detail)
                    }
                }
                else -> AppStatusLookupResult(status = AppStatus.UNKNOWN, detail = detail)
            }
        }

        private fun isQuotaExceeded(message: String?, reason: String?): Boolean {
            val normalizedMessage = message.orEmpty().lowercase()
            val normalizedReason = reason.orEmpty().lowercase()
            return "quota exceeded" in normalizedMessage ||
                "quotaexceeded" in normalizedReason ||
                "ratelimitexceeded" in normalizedReason
        }

        private fun logGooglePlayReleaseResponse(
            packageName: String,
            track: String,
            versionCode: Long,
            releaseSummaries: Any?,
        ) {
            System.out.println(
                "[${Instant.now()}] Google Play releases response " +
                    "(package=$packageName, track=$track, versionCode=$versionCode): ${configJson.toJson(releaseSummaries)}",
            )
        }

        private fun logGooglePlayErrorResponse(
            packageName: String,
            track: String,
            versionCode: Long,
            error: GoogleJsonResponseException,
        ) {
            System.err.println(
                "[${Instant.now()}] Google Play error response " +
                    "(package=$packageName, track=$track, versionCode=$versionCode, status=${error.statusCode}): " +
                    configJson.toJson(error.details),
            )
        }

        private fun createAndroidPublisher(serviceAccountFile: File): AndroidPublisher {
            val credentials = serviceAccountFile.inputStream().use { serviceAccount ->
                GoogleCredentials.fromStream(serviceAccount)
                    .createScoped(listOf("https://www.googleapis.com/auth/androidpublisher"))
            }
            val httpTransport = GoogleNetHttpTransport.newTrustedTransport()
            val jsonFactory = GsonFactory.getDefaultInstance()

            return AndroidPublisher.Builder(httpTransport, jsonFactory, HttpCredentialsAdapter(credentials))
                .setApplicationName(GOOGLE_PLAY_APPLICATION_NAME)
                .build()
        }
    }
}

data class SenderConfigDocument(
    val serverPort: Int? = DEFAULT_SERVER_PORT,
    val configs: List<SenderConfig>? = emptyList(),
) {
    fun validated(configDirectory: File): SenderConfigDocument {
        val configuredPort = serverPort ?: DEFAULT_SERVER_PORT
        require(configuredPort in 1..65535) { "serverPort must be between 1 and 65535." }

        val normalizedConfigs = configs.orEmpty().map { it.validated(configDirectory) }
        val duplicateIds = normalizedConfigs.groupBy { it.id }.filterValues { it.size > 1 }.keys
        require(duplicateIds.isEmpty()) { "Config ids must be unique. Duplicate ids: ${duplicateIds.joinToString()}" }

        return copy(serverPort = configuredPort, configs = normalizedConfigs)
    }

    fun toPersisted(configDirectory: File): SenderConfigDocument = copy(
        configs = configs.orEmpty().map { it.toPersisted(configDirectory) },
    )
}

data class SenderConfigResponse(
    val configPath: String,
    val serverPort: Int?,
    val configs: List<SenderConfig>?,
)

data class SenderConfig(
    val id: String?,
    val name: String? = null,
    val enabled: Boolean? = true,
    val googleServiceAccount: GoogleServiceAccountConfig?,
    val pollIntervalSeconds: Long,
    val topic: String?,
    val notification: NotificationConfig? = null,
    val data: Map<String, String> = emptyMap(),
    val android: AndroidMessageConfig? = null,
    val reviewStatusCheck: ReviewStatusCheckConfig? = null,
) {
    val pollInterval: Duration
        get() = Duration.ofSeconds(pollIntervalSeconds)

    val displayName: String
        get() = name?.takeIf { it.isNotBlank() } ?: requireNotNull(id)

    fun validated(configDirectory: File): SenderConfig {
        val configuredId = requireNotNull(id) { "config.id must be provided." }
        val configuredName = name?.trim()?.takeIf { it.isNotBlank() } ?: configuredId.trim()
        val serviceAccount = googleServiceAccount ?: GoogleServiceAccountConfig(defaultGoogleServiceAccountPath(configuredName))
        val configuredTopic = requireNotNull(topic) { "topic must be provided for '$configuredId'." }
        val messageData = data.orEmpty()

        require(configuredId.isNotBlank()) { "config.id must not be blank." }
        require(pollIntervalSeconds > 0) { "pollIntervalSeconds must be greater than 0 for '$configuredId'." }
        require(configuredTopic.isNotBlank()) { "topic must not be blank for '$configuredId'." }
        require(notification != null || messageData.isNotEmpty()) {
            "At least one of notification or data must be provided for '$configuredId'."
        }
        require(messageData.keys.none { it.isBlank() }) { "data keys must not be blank for '$configuredId'." }
        require(messageData.values.none { it.isBlank() }) { "data values must not be blank for '$configuredId'." }

        return copy(
            id = configuredId.trim(),
            name = configuredName,
            enabled = enabled ?: true,
            googleServiceAccount = serviceAccount.validated().resolvedAgainst(configDirectory),
            topic = configuredTopic.trim().removePrefix("/topics/"),
            notification = notification?.validated(),
            data = messageData,
            android = android?.validated(),
            reviewStatusCheck = reviewStatusCheck?.validated(),
        )
    }

    fun toPersisted(configDirectory: File): SenderConfig = copy(
        googleServiceAccount = googleServiceAccount?.relativizedAgainst(configDirectory),
    )

    fun requireServiceAccount(): GoogleServiceAccountConfig =
        requireNotNull(googleServiceAccount) { "googleServiceAccount must be provided for '$id'." }

    fun toMessageRequest(): FcmTopicMessageRequest = FcmTopicMessageRequest(
        topic = requireNotNull(topic) { "topic must be provided for '$id'." },
        notification = notification,
        data = data,
        android = android,
    )
}

data class GoogleServiceAccountConfig(
    val path: String?,
) {
    fun validated(): GoogleServiceAccountConfig {
        val configuredPath = requireNotNull(path) { "googleServiceAccount.path must be provided." }
        require(configuredPath.isNotBlank()) { "googleServiceAccount.path must not be blank." }
        return copy(path = configuredPath.trim())
    }

    fun resolvedAgainst(configDirectory: File): GoogleServiceAccountConfig {
        val configuredPath = requireNotNull(path) { "googleServiceAccount.path must be provided." }
        val configuredFile = File(configuredPath)
        if (configuredFile.isAbsolute) {
            return this
        }

        return copy(path = File(configDirectory, configuredPath).path)
    }

    fun relativizedAgainst(configDirectory: File): GoogleServiceAccountConfig {
        val configuredPath = requireNotNull(path) { "googleServiceAccount.path must be provided." }
        val configuredFile = File(configuredPath)
        val relativePath = runCatching {
            configDirectory.toPath().toAbsolutePath().normalize().relativize(configuredFile.toPath().toAbsolutePath().normalize()).toString()
        }.getOrNull()

        return if (relativePath != null && !relativePath.startsWith("..")) {
            copy(path = relativePath)
        } else {
            this
        }
    }

    fun resolveFile(): File {
        val configuredFile = File(requireNotNull(path) { "googleServiceAccount.path must be provided." }).absoluteFile
        if (configuredFile.exists()) {
            return configuredFile
        }

        return configuredFile.parentFile
            ?.listFiles { file -> file.isFile && FIREBASE_ADMINSDK_FILE_PATTERN.matches(file.name) }
            ?.sortedBy { it.name }
            ?.firstOrNull()
            ?: configuredFile
    }

    companion object {
        private val FIREBASE_ADMINSDK_FILE_PATTERN = Regex(""".+-firebase-adminsdk-.+-.+\.json""")
    }
}

data class NotificationConfig(
    val title: String?,
    val body: String?,
    val imageUrl: String? = null,
) {
    fun validated(): NotificationConfig {
        val configuredTitle = requireNotNull(title) { "notification.title must be provided." }
        val configuredBody = requireNotNull(body) { "notification.body must be provided." }

        require(configuredTitle.isNotEmpty()) { "notification.title must not be empty." }
        require(configuredBody.isNotEmpty()) { "notification.body must not be empty." }
        return copy(
            title = configuredTitle,
            body = configuredBody,
            imageUrl = imageUrl?.takeIf { it.isNotBlank() },
        )
    }
}

data class AndroidMessageConfig(
    val priority: AndroidPriority? = AndroidPriority.HIGH,
    val channelId: String? = null,
    val clickAction: String? = null,
) {
    fun validated(): AndroidMessageConfig = copy(
        priority = priority ?: AndroidPriority.HIGH,
        channelId = channelId?.takeIf { it.isNotBlank() },
        clickAction = clickAction?.takeIf { it.isNotBlank() },
    )

    fun toFirebaseConfig(notification: NotificationConfig?): AndroidConfig {
        val configuredPriority = priority ?: AndroidPriority.HIGH
        val builder = AndroidConfig.builder()
            .setPriority(configuredPriority.toFirebasePriority())
            .setTtl(Duration.ofSeconds(FCM_MESSAGE_TTL_SECONDS).toMillis())

        if (notification != null && (channelId != null || clickAction != null)) {
            val notificationBuilder = AndroidNotification.builder()

            channelId?.let { notificationBuilder.setChannelId(it) }
            clickAction?.let { notificationBuilder.setClickAction(it) }

            builder.setNotification(
                notificationBuilder.build(),
            )
        }

        return builder.build()
    }
}

enum class AndroidPriority {
    @SerializedName("normal")
    NORMAL,

    @SerializedName("high")
    HIGH,
    ;

    fun toFirebasePriority(): AndroidConfig.Priority = when (this) {
        NORMAL -> AndroidConfig.Priority.NORMAL
        HIGH -> AndroidConfig.Priority.HIGH
    }
}

data class ReviewStatusCheckConfig(
    val packageName: String? = null,
    val track: String? = DEFAULT_REVIEW_TRACK,
    val lastVersion: String? = null,
    val lastCheckedAt: String? = null,
    val lastStatus: ReviewStatusCheckStatus? = null,
    val lastResult: String? = null,
) {
    fun validated(): ReviewStatusCheckConfig = copy(
        packageName = packageName?.trim()?.takeIf { it.isNotBlank() },
        track = validateReviewTrack(track),
        lastVersion = lastVersion?.trim()?.takeIf { it.isNotBlank() },
        lastCheckedAt = lastCheckedAt?.trim()?.takeIf { it.isNotBlank() },
        lastResult = lastResult?.trim()?.takeIf { it.isNotBlank() },
    )
}

enum class ReviewStatusCheckStatus {
    @SerializedName("online")
    ONLINE,

    @SerializedName("in_review")
    IN_REVIEW,

    @SerializedName("rejected")
    REJECTED,

    @SerializedName("removed_or_not_found")
    REMOVED_OR_NOT_FOUND,

    @SerializedName("quota_exceeded")
    QUOTA_EXCEEDED,

    @SerializedName("permission_denied")
    PERMISSION_DENIED,

    @SerializedName("unknown")
    UNKNOWN,

    @SerializedName("offline")
    OFFLINE,

    @SerializedName("failed")
    FAILED,
}

data class ReviewStatusCheckRequest(
    val configId: String?,
    val version: String?,
    val packageName: String? = null,
    val track: String? = null,
)

data class ReviewStatusCheckResult(
    val config: ReviewStatusCheckConfig,
    val error: String? = null,
)

data class ReviewStatusCheckResponse(
    val configPath: String,
    val serverPort: Int?,
    val configs: List<SenderConfig>?,
    val reviewError: String? = null,
) {
    companion object {
        fun from(response: SenderConfigResponse, reviewError: String?): ReviewStatusCheckResponse =
            ReviewStatusCheckResponse(
                configPath = response.configPath,
                serverPort = response.serverPort,
                configs = response.configs,
                reviewError = reviewError,
            )
    }
}

data class FcmTopicMessageRequest(
    val topic: String,
    val notification: NotificationConfig?,
    val data: Map<String, String>,
    val android: AndroidMessageConfig?,
)

data class ErrorResponse(
    val error: String,
)

private fun Instant.elapsedAtLeast(duration: Duration): Boolean =
    Duration.between(this, Instant.now()) >= duration

private fun HttpExchange.respondHtml(html: String) {
    respond(200, "text/html; charset=utf-8", html.toByteArray(Charsets.UTF_8))
}

private fun HttpExchange.respondText(status: Int, text: String) {
    respond(status, "text/plain; charset=utf-8", text.toByteArray(Charsets.UTF_8))
}

private fun HttpExchange.respondJson(status: Int, body: Any) {
    respond(status, "application/json; charset=utf-8", configJson.toJson(body).toByteArray(Charsets.UTF_8))
}

private fun HttpExchange.respond(status: Int, contentType: String, body: ByteArray) {
    responseHeaders.add("Content-Type", contentType)
    responseHeaders.add("Cache-Control", "no-store")
    sendResponseHeaders(status, body.size.toLong())
    responseBody.use { it.write(body) }
}

private val MANAGEMENT_PAGE = """
<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>FCM Sender Console</title>
  <style>
    :root {
      --bg: #eef3f7;
      --panel: rgba(255, 255, 255, .88);
      --text: #17202a;
      --muted: #637083;
      --line: #d8e0e8;
      --brand: #0f766e;
      --brand-dark: #0b5d56;
      --danger: #b42318;
      --warn: #9a5b00;
      --shadow: 0 18px 50px rgba(29, 46, 65, .12);
    }
    * { box-sizing: border-box; }
    body {
      margin: 0;
      min-height: 100vh;
      color: var(--text);
      background:
        radial-gradient(circle at 10% 0%, rgba(15, 118, 110, .14), transparent 28%),
        linear-gradient(135deg, #f8fbfd 0%, var(--bg) 100%);
      font: 14px/1.5 -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
    }
    header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: 16px;
      padding: 24px 30px 10px;
    }
    .header-copy {
      display: grid;
      gap: 6px;
      min-width: 0;
    }
    h1 { margin: 0; font-size: 26px; letter-spacing: 0; }
    .subtle { color: var(--muted); }
    .config-path {
      display: flex;
      flex-wrap: wrap;
      gap: 6px;
      align-items: center;
      max-width: min(920px, calc(100vw - 60px));
      color: var(--muted);
      font-size: 12px;
      font-weight: 700;
    }
    .config-path code {
      min-width: 0;
      padding: 3px 7px;
      border: 1px solid var(--line);
      border-radius: 6px;
      background: rgba(255, 255, 255, .78);
      color: var(--text);
      font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
      font-weight: 600;
      overflow-wrap: anywhere;
    }
    main {
      display: grid;
      grid-template-columns: minmax(290px, 410px) minmax(0, 1fr);
      gap: 20px;
      padding: 20px 30px 34px;
    }
    .panel {
      min-width: 0;
      background: var(--panel);
      border: 1px solid rgba(216, 224, 232, .9);
      border-radius: 8px;
      box-shadow: var(--shadow);
      backdrop-filter: blur(14px);
      overflow: hidden;
    }
    .toolbar {
      min-height: 62px;
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 12px;
      padding: 14px 16px;
      border-bottom: 1px solid var(--line);
    }
    h2 { margin: 0; font-size: 15px; letter-spacing: 0; }
    button {
      min-height: 36px;
      border: 1px solid var(--line);
      border-radius: 7px;
      background: #fff;
      color: var(--text);
      padding: 8px 12px;
      font: inherit;
      font-weight: 700;
      cursor: pointer;
      transition: transform .16s ease, box-shadow .16s ease, background .16s ease;
    }
    button:hover { transform: translateY(-1px); box-shadow: 0 8px 20px rgba(20, 32, 46, .1); }
    button:disabled { opacity: .5; cursor: not-allowed; transform: none; box-shadow: none; }
    .primary { border-color: var(--brand); background: var(--brand); color: #fff; }
    .primary:hover { background: var(--brand-dark); }
    .danger { border-color: rgba(180, 35, 24, .45); color: var(--danger); }
    .list {
      display: grid;
      gap: 10px;
      padding: 14px;
    }
    .item {
      width: 100%;
      display: grid;
      grid-template-columns: minmax(0, 1fr) auto;
      gap: 12px;
      padding: 13px;
      border: 1px solid var(--line);
      border-radius: 8px;
      background: #fff;
      text-align: left;
    }
    .item[aria-selected="true"] { border-color: var(--brand); box-shadow: inset 4px 0 0 var(--brand); }
    .item-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-weight: 800; }
    .item-topic { margin-top: 4px; color: var(--muted); font-size: 12px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .status {
      align-self: start;
      border-radius: 999px;
      padding: 3px 8px;
      background: var(--brand);
      color: #fff;
      font-size: 12px;
      font-weight: 800;
    }
    .status.paused { background: var(--warn); }
    .status.draft { background: #2563eb; }
    form { padding: 18px; }
    .grid {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 14px;
    }
    .full { grid-column: 1 / -1; }
    label { display: grid; gap: 6px; color: var(--muted); font-size: 12px; font-weight: 800; }
    input, textarea, select {
      width: 100%;
      min-width: 0;
      border: 1px solid var(--line);
      border-radius: 7px;
      background: #fff;
      color: var(--text);
      padding: 9px 10px;
      font: inherit;
    }
    input.field-attention {
      animation: fieldAttention 1s ease-in-out 2;
    }
    textarea {
      min-height: 96px;
      resize: vertical;
      font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
      font-size: 13px;
    }
    .checkbox { display: flex; align-items: center; gap: 9px; min-height: 40px; color: var(--text); font-size: 14px; }
    .checkbox input { width: 18px; height: 18px; }
    .path-field {
      display: grid;
      grid-template-columns: minmax(0, 1fr) auto;
      align-items: end;
      gap: 10px;
    }
    .review-section {
      display: grid;
      gap: 12px;
      padding-top: 14px;
      border-top: 1px solid var(--line);
    }
    .section-title {
      margin: 0;
      color: var(--text);
      font-size: 14px;
      letter-spacing: 0;
    }
    .review-settings {
      display: grid;
      grid-template-columns: minmax(0, 1fr) minmax(130px, 190px);
      gap: 10px;
    }
    .review-query {
      display: grid;
      grid-template-columns: minmax(150px, 210px) auto minmax(300px, 1fr);
      align-items: end;
      gap: 10px;
    }
    .review-query button { min-width: 96px; }
    .review-result {
      min-height: 40px;
      display: grid;
      align-content: center;
      gap: 4px;
      padding: 8px 10px;
      border: 1px solid var(--line);
      border-radius: 7px;
      background: rgba(248, 251, 253, .9);
      color: var(--muted);
      overflow-wrap: anywhere;
      font-size: 13px;
      transition: border-color .18s ease, background .18s ease, color .18s ease;
    }
    .review-line {
      display: flex;
      align-items: center;
      gap: 8px;
      min-width: 0;
      animation: reviewFadeIn .18s ease both;
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }
    .review-line + .review-line {
      color: var(--text);
      font-weight: 800;
    }
    .review-line.checking::before {
      width: 6px;
      height: 6px;
      flex: 0 0 auto;
      border-radius: 50%;
      background: currentColor;
      content: "";
      animation: reviewPulse .9s ease-in-out infinite;
    }
    .review-result.online { border-color: rgba(15, 118, 110, .45); }
    .review-result.in_review { border-color: rgba(202, 138, 4, .45); color: #a16207; }
    .review-result.rejected { border-color: rgba(180, 35, 24, .45); color: var(--danger); }
    .review-result.removed_or_not_found { border-color: rgba(107, 114, 128, .45); color: #4b5563; }
    .review-result.quota_exceeded { border-color: rgba(217, 119, 6, .45); color: #b45309; }
    .review-result.permission_denied { border-color: rgba(180, 35, 24, .45); color: var(--danger); }
    .review-result.unknown { border-color: rgba(59, 130, 246, .35); color: #1d4ed8; }
    .review-result.offline { border-color: rgba(154, 91, 0, .45); color: var(--warn); }
    .review-result.failed { border-color: rgba(180, 35, 24, .45); color: var(--danger); }
    .review-result.checking { border-color: rgba(37, 99, 235, .35); background: rgba(248, 251, 253, .98); }
    @keyframes reviewFadeIn {
      from { opacity: 0; transform: translateY(2px); }
      to { opacity: 1; transform: translateY(0); }
    }
    @keyframes reviewPulse {
      0%, 100% { opacity: .35; transform: scale(.88); }
      50% { opacity: 1; transform: scale(1); }
    }
    @keyframes fieldAttention {
      0%, 100% { border-color: var(--line); box-shadow: none; }
      25%, 75% { border-color: var(--danger); box-shadow: 0 0 0 3px rgba(180, 35, 24, .16); }
    }
    .actions { display: flex; flex-wrap: wrap; gap: 10px; margin-top: 18px; }
    .message { min-height: 20px; margin-top: 12px; color: var(--muted); font-size: 13px; }
    .message.error { color: var(--danger); font-weight: 800; }
    .empty { padding: 18px; color: var(--muted); }
    @media (max-width: 860px) {
      header { padding: 18px 14px 6px; align-items: flex-start; flex-direction: column; }
      main { grid-template-columns: 1fr; padding: 14px; }
      .grid { grid-template-columns: 1fr; }
      .path-field { grid-template-columns: 1fr; }
      .review-settings { grid-template-columns: 1fr; }
      .review-query { grid-template-columns: 1fr; }
    }
  </style>
</head>
<body>
  <header>
    <div class="header-copy">
      <h1>FCM Sender Console</h1>
      <div class="subtle">Local configuration manager</div>
      <div class="config-path">Config <code id="configPath">Loading...</code></div>
    </div>
    <button class="primary" id="refresh">Refresh</button>
  </header>
  <main>
    <section class="panel">
      <div class="toolbar">
        <h2>Configurations</h2>
        <button id="addConfig" type="button">Add</button>
      </div>
      <div class="list" id="configList"></div>
    </section>
    <section class="panel">
      <div class="toolbar">
        <h2>Editor</h2>
        <button id="toggleEnabled" type="button">Pause</button>
      </div>
      <form id="editorForm">
        <div class="grid">
          <label>Name<input id="name" required></label>
          <label>Topic<input id="topic" required></label>
          <div class="full path-field">
            <label>Google service-account JSON path<input id="serviceAccountPath" required></label>
            <button id="resetServiceAccountPath" type="button">Reset</button>
          </div>
          <label>Poll interval seconds<input id="pollIntervalSeconds" type="number" min="1" step="1" required></label>
          <label class="checkbox"><input id="enabled" type="checkbox">Enabled</label>
          <label>Notification title<input id="notificationTitle"></label>
          <label>Notification body<input id="notificationBody"></label>
          <label class="full">Notification image URL<input id="notificationImageUrl"></label>
          <label class="full">Data JSON<textarea id="dataJson" spellcheck="false"></textarea></label>
          <label>Android priority<select id="androidPriority"><option value="high">high</option><option value="normal">normal</option></select></label>
          <label>Android channel ID<input id="androidChannelId"></label>
          <label>Android click action<input id="androidClickAction"></label>
          <div class="full review-section">
            <h3 class="section-title">Review status</h3>
            <div class="review-settings">
              <label>Google Play package name<input id="reviewPackageName"></label>
              <label>轨道<select id="reviewTrack"><option value="production">正式版 (Production)</option><option value="beta">开放式测试 (Open Testing)</option><option value="internal">内部测试 (Internal Testing)</option><option value="alpha">封闭式测试 (Closed Testing)</option></select></label>
            </div>
            <div class="review-query">
              <label>Version code<input id="reviewVersion" inputmode="numeric"></label>
              <button id="checkReviewStatus" type="button">Check</button>
              <div class="review-result" id="reviewStatusResult"></div>
            </div>
          </div>
        </div>
        <div class="actions">
          <button class="primary" type="submit">Save</button>
          <button class="danger" id="removeConfig" type="button">Remove</button>
        </div>
        <div class="message" id="message"></div>
      </form>
    </section>
  </main>
  <script>
    const listElement = document.getElementById('configList');
    const form = document.getElementById('editorForm');
    const message = document.getElementById('message');
    const configPathElement = document.getElementById('configPath');
    const reviewStatusResult = document.getElementById('reviewStatusResult');
    const checkReviewStatusButton = document.getElementById('checkReviewStatus');
    const fields = {
      name: document.getElementById('name'),
      topic: document.getElementById('topic'),
      serviceAccountPath: document.getElementById('serviceAccountPath'),
      pollIntervalSeconds: document.getElementById('pollIntervalSeconds'),
      enabled: document.getElementById('enabled'),
      notificationTitle: document.getElementById('notificationTitle'),
      notificationBody: document.getElementById('notificationBody'),
      notificationImageUrl: document.getElementById('notificationImageUrl'),
      dataJson: document.getElementById('dataJson'),
      androidPriority: document.getElementById('androidPriority'),
      androidChannelId: document.getElementById('androidChannelId'),
      androidClickAction: document.getElementById('androidClickAction'),
      reviewPackageName: document.getElementById('reviewPackageName'),
      reviewTrack: document.getElementById('reviewTrack'),
      reviewVersion: document.getElementById('reviewVersion')
    };
    let documentConfig = { configPath: '', serverPort: 9999, configs: [] };
    let selectedId = null;
    let lastNameInputValue = '';

    async function loadConfig() {
      const response = await fetch('/api/config');
      if (!response.ok) throw new Error(await response.text());
      documentConfig = normalizeConfigResponse(await response.json());
      selectedId = documentConfig.configs.some(item => item.id === selectedId) ? selectedId : documentConfig.configs[0]?.id ?? null;
      render();
      showMessage('Loaded.');
    }

    async function saveConfig(text = 'Saved. Sender will reload now.', commitDraftId = null) {
      const draftsToKeep = documentConfig.configs.filter(config => config.__isDraft && config.id !== commitDraftId);
      const response = await fetch('/api/config', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(persistedDocument(commitDraftId))
      });
      const payload = await response.json();
      if (!response.ok) throw new Error(payload.error || 'Save failed.');
      const previousSelectedId = selectedId;
      documentConfig = normalizeConfigResponse(payload);
      const persistedIds = new Set(documentConfig.configs.map(config => config.id));
      documentConfig.configs.push(...draftsToKeep.filter(config => !persistedIds.has(config.id)));
      selectedId = documentConfig.configs.some(item => item.id === previousSelectedId) ? previousSelectedId : documentConfig.configs[0]?.id ?? null;
      render();
      showMessage(text);
    }

    function normalizeConfigResponse(payload) {
      return {
        configPath: payload.configPath || '',
        serverPort: payload.serverPort ?? 9999,
        configs: Array.isArray(payload.configs) ? payload.configs.map(stripClientState) : []
      };
    }

    function persistedDocument(commitDraftId = null) {
      return {
        serverPort: documentConfig.serverPort ?? 9999,
        configs: documentConfig.configs
          .filter(config => !config.__isDraft || config.id === commitDraftId)
          .map(stripClientState)
      };
    }

    function stripClientState(config) {
      const { __isDraft, ...persisted } = config;
      return persisted;
    }

    function currentConfig() {
      return documentConfig.configs.find(config => config.id === selectedId) ?? null;
    }

    function renderList() {
      listElement.textContent = '';
      if (documentConfig.configs.length === 0) {
        const empty = document.createElement('div');
        empty.className = 'empty';
        empty.textContent = 'No configurations';
        listElement.append(empty);
        return;
      }
      documentConfig.configs.forEach(config => {
        const item = document.createElement('button');
        item.type = 'button';
        item.className = 'item';
        item.setAttribute('aria-selected', String(config.id === selectedId));
        item.addEventListener('click', () => { selectedId = config.id; render(); });
        const text = document.createElement('div');
        const name = document.createElement('div');
        name.className = 'item-name';
        name.textContent = config.name || config.id;
        const topic = document.createElement('div');
        topic.className = 'item-topic';
        topic.textContent = `${'$'}{config.topic || '(no topic)'} · ${'$'}{config.pollIntervalSeconds || 0}s`;
        text.append(name, topic);
        const status = document.createElement('span');
        status.className = `status ${'$'}{config.__isDraft ? 'draft' : config.enabled === false ? 'paused' : ''}`;
        status.textContent = config.__isDraft ? 'Draft' : config.enabled === false ? 'Paused' : 'Active';
        item.append(text, status);
        listElement.append(item);
      });
    }

    function renderEditor() {
      const config = currentConfig();
      form.hidden = !config;
      document.getElementById('toggleEnabled').disabled = !config;
      document.getElementById('removeConfig').disabled = !config;
      checkReviewStatusButton.disabled = !config;
      if (!config) return;
      const reviewStatusCheck = config.reviewStatusCheck || {};
      fields.name.value = config.name || '';
      fields.topic.value = config.topic || '';
      fields.serviceAccountPath.value = config.googleServiceAccount?.path || '';
      fields.pollIntervalSeconds.value = config.pollIntervalSeconds || ${DEFAULT_POLL_INTERVAL_SECONDS};
      fields.enabled.checked = config.enabled !== false;
      fields.notificationTitle.value = config.notification?.title || '';
      fields.notificationBody.value = config.notification?.body || '';
      fields.notificationImageUrl.value = config.notification?.imageUrl || '';
      fields.dataJson.value = JSON.stringify(config.data || {}, null, 2);
      fields.androidPriority.value = config.android?.priority || 'high';
      fields.androidChannelId.value = config.android?.channelId || '';
      fields.androidClickAction.value = config.android?.clickAction || '';
      fields.reviewPackageName.value = reviewStatusCheck.packageName || '';
      fields.reviewTrack.value = reviewStatusCheck.track || '${DEFAULT_REVIEW_TRACK}';
      fields.reviewVersion.value = reviewStatusCheck.lastVersion || '';
      lastNameInputValue = fields.name.value;
      document.getElementById('toggleEnabled').textContent = config.enabled === false ? 'Enable' : 'Pause';
      renderReviewStatus(config);
    }

    function renderReviewStatus(config, transient = null) {
      const reviewStatusCheck = config.reviewStatusCheck || {};
      const lastStatus = reviewStatusCheck.lastStatus || 'unchecked';
      reviewStatusResult.textContent = '';
      reviewStatusResult.className = `review-result ${'$'}{reviewStatusClass(transient?.status || lastStatus)}`;
      if (transient) {
        reviewStatusResult.append(createReviewLine(transient.result, transient.status));
      }
      reviewStatusResult.append(createReviewLine(lastReviewText(reviewStatusCheck), lastStatus, reviewStatusCheck.lastResult));
    }

    function createReviewLine(text, status, title = '') {
      const line = document.createElement('div');
      line.className = `review-line ${'$'}{reviewStatusClass(status)}`;
      line.textContent = text;
      if (title) line.title = title;
      return line;
    }

    function lastReviewText(reviewStatusCheck) {
      if (!reviewStatusCheck.lastCheckedAt || !reviewStatusCheck.lastStatus) {
        return '尚未检查';
      }

      const trackLabel = reviewTrackLabel(reviewStatusCheck.track);
      return `上次检测${'$'}{formatReviewTime(reviewStatusCheck.lastCheckedAt)}，${'$'}{reviewStatusIcon(reviewStatusCheck.lastStatus)} ${'$'}{reviewStatusLabel(reviewStatusCheck.lastStatus)}${'$'}{trackLabel ? ` · ${'$'}{trackLabel}` : ''}`;
    }

    function reviewTrackLabel(track) {
      return {
        production: '正式版 (Production)',
        beta: '开放式测试 (Open Testing)',
        internal: '内部测试 (Internal Testing)',
        alpha: '封闭式测试 (Closed Testing)'
      }[track] || track || '';
    }

    function reviewStatusLabel(status) {
      return {
        online: '在线',
        in_review: '审核中',
        rejected: '已拒绝',
        removed_or_not_found: '已下架或不存在',
        quota_exceeded: '配额超限',
        permission_denied: '权限不足',
        unknown: '未知状态',
        offline: '不在线',
        failed: '失败',
        checking: '检查中',
        unchecked: '未检查'
      }[status] || status || '未检查';
    }

    function reviewStatusIcon(status) {
      return {
        online: '✅',
        in_review: '🟡',
        rejected: '⛔',
        removed_or_not_found: '📭',
        quota_exceeded: '⏳',
        permission_denied: '🔒',
        unknown: '❔',
        offline: '⚪',
        failed: '❌',
        checking: '🔎'
      }[status] || '•';
    }

    function reviewStatusClass(status) {
      return ['online', 'in_review', 'rejected', 'removed_or_not_found', 'quota_exceeded', 'permission_denied', 'unknown', 'offline', 'failed', 'checking'].includes(status) ? status : '';
    }

    function formatReviewTime(value) {
      if (!value) return '';
      const date = new Date(value);
      if (Number.isNaN(date.getTime())) return value;
      const pad = number => String(number).padStart(2, '0');
      return `${'$'}{date.getFullYear()}-${'$'}{pad(date.getMonth() + 1)}-${'$'}{pad(date.getDate())} ${'$'}{pad(date.getHours())}:${'$'}{pad(date.getMinutes())}:${'$'}{pad(date.getSeconds())}`;
    }

    function render() {
      configPathElement.textContent = documentConfig.configPath || 'Unknown';
      renderList();
      renderEditor();
    }

    function readEditor() {
      const existing = currentConfig();
      if (!existing) throw new Error('No configuration selected.');
      const data = JSON.parse(fields.dataJson.value.trim() || '{}');
      if (data === null || Array.isArray(data) || typeof data !== 'object') throw new Error('Data JSON must be an object.');
      const title = fields.notificationTitle.value;
      const body = fields.notificationBody.value;
      const imageUrl = fields.notificationImageUrl.value.trim();
      const notification = title || body || imageUrl ? { title, body, imageUrl: imageUrl || null } : null;
      if (notification && (!title || !body)) throw new Error('Notification title and body must both be set.');
      return {
        ...existing,
        name: fields.name.value.trim() || existing.id,
        enabled: fields.enabled.checked,
        googleServiceAccount: { path: fields.serviceAccountPath.value.trim() },
        pollIntervalSeconds: Number(fields.pollIntervalSeconds.value),
        topic: fields.topic.value.trim(),
        notification,
        data,
        android: {
          priority: fields.androidPriority.value,
          channelId: fields.androidChannelId.value.trim() || null,
          clickAction: fields.androidClickAction.value.trim() || null
        },
        reviewStatusCheck: readReviewStatusCheck(existing)
      };
    }

    function readReviewStatusCheck(existing) {
      const reviewStatusCheck = {
        ...(existing.reviewStatusCheck || {}),
        packageName: fields.reviewPackageName.value.trim() || null,
        track: fields.reviewTrack.value.trim() || '${DEFAULT_REVIEW_TRACK}'
      };
      if (
        !reviewStatusCheck.packageName &&
        !reviewStatusCheck.track &&
        !reviewStatusCheck.lastVersion &&
        !reviewStatusCheck.lastCheckedAt &&
        !reviewStatusCheck.lastStatus &&
        !reviewStatusCheck.lastResult
      ) {
        return null;
      }

      return reviewStatusCheck;
    }

    async function saveEditor() {
      const next = readEditor();
      if (!next.googleServiceAccount.path) throw new Error('Google service-account JSON path is required.');
      if (!next.topic) throw new Error('Topic is required.');
      if (!Number.isInteger(next.pollIntervalSeconds) || next.pollIntervalSeconds <= 0) throw new Error('Poll interval seconds must be a positive integer.');
      if (!next.notification && Object.keys(next.data).length === 0) throw new Error('Set at least notification or data.');
      const index = documentConfig.configs.findIndex(config => config.id === next.id);
      if (index < 0) throw new Error('Selected configuration no longer exists.');
      documentConfig.configs[index] = next;
      await saveConfig(
        next.__isDraft ? 'Added. Sender will reload now.' : 'Saved. Sender will reload now.',
        next.__isDraft ? next.id : null
      );
    }

    function addConfig() {
      const id = `config-${'$'}{Date.now().toString(36)}`;
      const name = 'New config';
      documentConfig.configs.push({
        __isDraft: true,
        id,
        name,
        enabled: false,
        googleServiceAccount: { path: defaultServiceAccountPath(name) },
        pollIntervalSeconds: ${DEFAULT_POLL_INTERVAL_SECONDS},
        topic: 'news',
        notification: { title: '${DEFAULT_NOTIFICATION_TEXT}', body: '${DEFAULT_NOTIFICATION_TEXT}', imageUrl: null },
        data: {},
        android: { priority: 'high', channelId: null, clickAction: null },
        reviewStatusCheck: { packageName: null, track: '${DEFAULT_REVIEW_TRACK}', lastVersion: null, lastCheckedAt: null, lastStatus: null, lastResult: null }
      });
      selectedId = id;
      render();
      showMessage('Draft created. Save to write it to the loaded config file.');
    }

    function currentItemName() {
      const config = currentConfig();
      return (fields.name.value.trim() || config?.name || config?.id || 'New config').trim();
    }

    function defaultServiceAccountPath(name) {
      return `${'$'}{name.trim() || 'New config'}/google-service-account.json`;
    }

    function configDirectory() {
      const path = documentConfig.configPath || '';
      const slashIndex = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\\\'));
      return slashIndex >= 0 ? path.slice(0, slashIndex) : '';
    }

    function isAbsolutePath(path) {
      return path.startsWith('/') || /^[A-Za-z]:[\\\\/]/.test(path);
    }

    function joinPath(base, relativePath) {
      if (!base) return relativePath;
      const separator = base.includes('\\\\') ? '\\\\' : '/';
      return `${'$'}{base.replace(/[\\\\/]+$/, '')}${'$'}{separator}${'$'}{relativePath}`;
    }

    function normalizePath(path) {
      const normalized = String(path || '').trim().split('\\\\').join('/').split('/').filter(Boolean).join('/');
      return path.startsWith('/') ? `/${'$'}{normalized}` : normalized;
    }

    function defaultServiceAccountPathForStyle(name, currentPath) {
      const relativePath = defaultServiceAccountPath(name);
      return isAbsolutePath(currentPath) ? joinPath(configDirectory(), relativePath) : relativePath;
    }

    function isDefaultServiceAccountPathForName(path, name) {
      const currentPath = String(path || '').trim();
      const relativePath = defaultServiceAccountPath(name);
      if (normalizePath(currentPath) === normalizePath(relativePath)) {
        return true;
      }

      if (!isAbsolutePath(currentPath)) {
        return false;
      }

      return normalizePath(currentPath) === normalizePath(joinPath(configDirectory(), relativePath));
    }

    function handleNameInput() {
      const previousName = (lastNameInputValue.trim() || currentConfig()?.name || currentConfig()?.id || 'New config').trim();
      const nextName = currentItemName();
      const currentPath = fields.serviceAccountPath.value;
      if (isDefaultServiceAccountPathForName(currentPath, previousName)) {
        fields.serviceAccountPath.value = defaultServiceAccountPathForStyle(nextName, currentPath);
      }
      lastNameInputValue = fields.name.value;
    }

    function resetServiceAccountPath() {
      fields.serviceAccountPath.value = defaultServiceAccountPath(currentItemName());
      showMessage('Service-account path reset.');
    }

    async function checkReviewStatus() {
      const config = currentConfig();
      if (!config) return;
      if (config.__isDraft) throw new Error('Save this configuration before checking review status.');
      const packageName = fields.reviewPackageName.value.trim();
      if (!packageName) {
        flashField(fields.reviewPackageName);
        showMessage('Google Play package name is required.', true);
        return;
      }
      const version = fields.reviewVersion.value.trim();
      if (!/^[1-9]\d*$/.test(version)) throw new Error('Version code must be a positive integer.');

      checkReviewStatusButton.disabled = true;
      checkReviewStatusButton.textContent = 'Checking...';
      renderReviewStatus(config, { status: 'checking', result: `${'$'}{reviewStatusIcon('checking')} 正在检查 versionCode ${'$'}{version}...` });

      try {
        const response = await fetch('/api/review-status/check', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            configId: config.id,
            version,
            packageName,
            track: fields.reviewTrack.value.trim() || '${DEFAULT_REVIEW_TRACK}'
          })
        });
        const payload = await response.json();
        if (!response.ok) throw new Error(payload.error || 'Review status check failed.');
        const previousSelectedId = selectedId;
        documentConfig = normalizeConfigResponse(payload);
        selectedId = documentConfig.configs.some(item => item.id === previousSelectedId) ? previousSelectedId : documentConfig.configs[0]?.id ?? null;
        render();
        showMessage(payload.reviewError ? `Review status check failed; kept previous successful result: ${'$'}{payload.reviewError}` : 'Review status checked.');
      } catch (error) {
        renderReviewStatus(config);
        throw error;
      } finally {
        checkReviewStatusButton.disabled = !currentConfig();
        checkReviewStatusButton.textContent = 'Check';
      }
    }

    function flashField(field) {
      field.classList.remove('field-attention');
      void field.offsetWidth;
      field.classList.add('field-attention');
      field.focus();
      window.setTimeout(() => field.classList.remove('field-attention'), 2100);
    }

    async function removeConfig() {
      const config = currentConfig();
      if (!config) return;
      const isDraft = config.__isDraft === true;
      documentConfig.configs = documentConfig.configs.filter(item => item.id !== config.id);
      selectedId = documentConfig.configs[0]?.id ?? null;
      if (isDraft) {
        render();
        showMessage('Draft removed.');
        return;
      }
      await saveConfig('Removed. Sender will reload now.');
    }

    async function toggleEnabled() {
      const config = currentConfig();
      if (!config) return;
      config.enabled = config.enabled === false;
      if (config.__isDraft) {
        render();
        showMessage(config.enabled ? 'Enabled draft.' : 'Paused draft.');
        return;
      }
      await saveConfig(config.enabled ? 'Enabled. Sender will reload now.' : 'Paused. Sender will reload now.');
    }

    function showMessage(text, isError = false) {
      message.textContent = text;
      message.className = `message ${'$'}{isError ? 'error' : ''}`;
    }

    form.addEventListener('submit', event => {
      event.preventDefault();
      saveEditor().catch(error => showMessage(error.message, true));
    });
    fields.name.addEventListener('input', handleNameInput);
    document.getElementById('resetServiceAccountPath').addEventListener('click', resetServiceAccountPath);
    document.getElementById('addConfig').addEventListener('click', () => {
      try {
        addConfig();
      } catch (error) {
        showMessage(error.message, true);
      }
    });
    document.getElementById('removeConfig').addEventListener('click', () => removeConfig().catch(error => showMessage(error.message, true)));
    document.getElementById('toggleEnabled').addEventListener('click', () => toggleEnabled().catch(error => showMessage(error.message, true)));
    checkReviewStatusButton.addEventListener('click', () => checkReviewStatus().catch(error => showMessage(error.message, true)));
    document.getElementById('refresh').addEventListener('click', () => loadConfig().catch(error => showMessage(error.message, true)));
    loadConfig().catch(error => showMessage(error.message, true));
  </script>
</body>
</html>
"""
