package org.example

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.AndroidConfig
import com.google.firebase.messaging.AndroidNotification
import com.google.firebase.messaging.ApnsConfig
import com.google.firebase.messaging.Aps
import com.google.firebase.messaging.ApsAlert
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.Message
import com.google.firebase.messaging.Notification
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import java.io.File
import java.time.Duration
import java.time.Instant

private const val DEFAULT_CONFIG_PATH = "config/fcm-sender.json"
private val configJson: Gson = GsonBuilder().create()

fun main(args: Array<String>) {
    val configPath = args.firstOrNull()
        ?: System.getenv("FCM_SENDER_CONFIG")
        ?: DEFAULT_CONFIG_PATH

    val config = loadSenderConfig(File(configPath))
    val serviceAccount = requireNotNull(config.googleServiceAccount) { "googleServiceAccount must be provided." }
    val messaging = initializeFirebase(serviceAccount)
    val sender = FcmTopicSender(messaging)

    println("FCM sender started.")
    println("Config file: ${File(configPath).absolutePath}")
    println("Topic: ${config.topic}")
    println("Polling interval: ${config.pollIntervalSeconds}s")
    println("Press Ctrl+C to stop.")

    while (true) {
        val startedAt = Instant.now()

        runCatching {
            sender.send(config.toMessageRequest())
        }.onSuccess { messageId ->
            println("[${Instant.now()}] Sent to topic '${config.topic}', messageId=$messageId")
        }.onFailure { error ->
            System.err.println("[${Instant.now()}] Send failed: ${error.message}")
            error.printStackTrace()
        }

        val elapsed = Duration.between(startedAt, Instant.now())
        val waitMillis = config.pollInterval.toMillis() - elapsed.toMillis()
        Thread.sleep(waitMillis.coerceAtLeast(0L))
    }
}

fun loadSenderConfig(file: File): SenderConfig {
    require(file.exists()) {
        "Config file not found: ${file.absolutePath}. Copy config/fcm-sender.example.json to $DEFAULT_CONFIG_PATH and fill it in."
    }
    require(file.isFile) { "Config path is not a file: ${file.absolutePath}" }

    val config = requireNotNull(configJson.fromJson(file.readText(), SenderConfig::class.java)) {
        "Config file must contain a JSON object."
    }

    return config.validated(file.parentFile ?: File("."))
}

private fun initializeFirebase(serviceAccountConfig: GoogleServiceAccountConfig): FirebaseMessaging {
    val serviceAccountFile = serviceAccountConfig.resolveFile()
    require(serviceAccountFile.exists()) {
        "Google service account JSON not found: ${serviceAccountFile.absolutePath}"
    }

    val app = FirebaseApp.getApps().firstOrNull() ?: serviceAccountFile.inputStream().use { serviceAccount ->
        val options = FirebaseOptions.builder()
            .setCredentials(GoogleCredentials.fromStream(serviceAccount))
            .build()

        FirebaseApp.initializeApp(options)
    }

    return FirebaseMessaging.getInstance(app)
}

class FcmTopicSender(
    private val messaging: FirebaseMessaging,
) {
    fun send(request: FcmTopicMessageRequest): String {
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

        request.apns?.let { apns ->
            messageBuilder.setApnsConfig(apns.toFirebaseConfig(request.notification))
        }

        return messaging.send(messageBuilder.build())
    }
}

data class SenderConfig(
    val googleServiceAccount: GoogleServiceAccountConfig?,
    val pollIntervalSeconds: Long,
    val topic: String?,
    val notification: NotificationConfig? = null,
    val data: Map<String, String> = emptyMap(),
    val android: AndroidMessageConfig? = null,
    val apns: ApnsMessageConfig? = null,
) {
    val pollInterval: Duration
        get() = Duration.ofSeconds(pollIntervalSeconds)

    fun validated(configDirectory: File): SenderConfig {
        val serviceAccount = requireNotNull(googleServiceAccount) { "googleServiceAccount must be provided." }
        val configuredTopic = requireNotNull(topic) { "topic must be provided." }
        val messageData = data.orEmpty()

        require(pollIntervalSeconds > 0) { "pollIntervalSeconds must be greater than 0." }
        require(configuredTopic.isNotBlank()) { "topic must not be blank." }
        require(notification != null || messageData.isNotEmpty()) {
            "At least one of notification or data must be provided."
        }
        require(messageData.keys.none { it.isBlank() }) { "data keys must not be blank." }
        require(messageData.values.none { it.isBlank() }) { "data values must not be blank." }

        return copy(
            googleServiceAccount = serviceAccount.validated().resolvedAgainst(configDirectory),
            topic = configuredTopic.trim().removePrefix("/topics/"),
            notification = notification?.validated(),
            data = messageData,
            android = android?.validated(),
            apns = apns?.validated(),
        )
    }

    fun toMessageRequest(): FcmTopicMessageRequest = FcmTopicMessageRequest(
        topic = requireNotNull(topic) { "topic must be provided." },
        notification = notification,
        data = data,
        android = android,
        apns = apns,
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

    fun resolveFile(): File = File(requireNotNull(path) { "googleServiceAccount.path must be provided." }).absoluteFile
}

data class NotificationConfig(
    val title: String?,
    val body: String?,
    val imageUrl: String? = null,
) {
    fun validated(): NotificationConfig {
        val configuredTitle = requireNotNull(title) { "notification.title must be provided." }
        val configuredBody = requireNotNull(body) { "notification.body must be provided." }

        require(configuredTitle.isNotBlank()) { "notification.title must not be blank." }
        require(configuredBody.isNotBlank()) { "notification.body must not be blank." }
        return copy(
            title = configuredTitle.trim(),
            body = configuredBody.trim(),
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

data class ApnsMessageConfig(
    val sound: String? = "default",
    val badge: Int? = null,
) {
    fun validated(): ApnsMessageConfig {
        require(badge == null || badge >= 0) { "apns.badge must be greater than or equal to 0." }
        return copy(sound = sound?.takeIf { it.isNotBlank() })
    }

    fun toFirebaseConfig(notification: NotificationConfig?): ApnsConfig {
        val apsBuilder = Aps.builder()

        sound?.let { apsBuilder.setSound(it) }
        badge?.let { apsBuilder.setBadge(it) }

        notification?.let {
            apsBuilder.setAlert(
                ApsAlert.builder()
                    .setTitle(it.title)
                    .setBody(it.body)
                    .build(),
            )
        }

        return ApnsConfig.builder()
            .setAps(apsBuilder.build())
            .build()
    }
}

data class FcmTopicMessageRequest(
    val topic: String,
    val notification: NotificationConfig?,
    val data: Map<String, String>,
    val android: AndroidMessageConfig?,
    val apns: ApnsMessageConfig?,
)
