package org.example

import com.google.auth.oauth2.GoogleCredentials
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
private val configJson: Gson = GsonBuilder().setPrettyPrinting().create()

private fun defaultGoogleServiceAccountPath(itemName: String): String =
    "$itemName/$GOOGLE_SERVICE_ACCOUNT_FILE_NAME"

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
    .actions { display: flex; flex-wrap: wrap; gap: 10px; margin-top: 18px; }
    .message { min-height: 20px; margin-top: 12px; color: var(--muted); font-size: 13px; }
    .message.error { color: var(--danger); font-weight: 800; }
    .empty { padding: 18px; color: var(--muted); }
    @media (max-width: 860px) {
      header { padding: 18px 14px 6px; align-items: flex-start; flex-direction: column; }
      main { grid-template-columns: 1fr; padding: 14px; }
      .grid { grid-template-columns: 1fr; }
      .path-field { grid-template-columns: 1fr; }
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
      androidClickAction: document.getElementById('androidClickAction')
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
      if (!config) return;
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
      lastNameInputValue = fields.name.value;
      document.getElementById('toggleEnabled').textContent = config.enabled === false ? 'Enable' : 'Pause';
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
        }
      };
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
        enabled: true,
        googleServiceAccount: { path: defaultServiceAccountPath(name) },
        pollIntervalSeconds: ${DEFAULT_POLL_INTERVAL_SECONDS},
        topic: 'news',
        notification: { title: '${DEFAULT_NOTIFICATION_TEXT}', body: '${DEFAULT_NOTIFICATION_TEXT}', imageUrl: null },
        data: {},
        android: { priority: 'high', channelId: null, clickAction: null }
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
    document.getElementById('refresh').addEventListener('click', () => loadConfig().catch(error => showMessage(error.message, true)));
    loadConfig().catch(error => showMessage(error.message, true));
  </script>
</body>
</html>
"""
