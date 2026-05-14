# FCM Server

Kotlin command-line service for periodically sending a custom Firebase Cloud Messaging message to a topic.

## Configuration

Copy the example config and fill in your own values:

```bash
cp config/fcm-sender.example.json config/fcm-sender.json
```

Place the Google Console service-account JSON in `config/google-service-account.json`, or set `googleServiceAccount.path` to the absolute path of that file. Relative paths are resolved from the directory that contains `fcm-sender.json`.

Example fields:

- `googleServiceAccount.path`: Google Console service-account JSON path.
- `pollIntervalSeconds`: send interval in seconds.
- `topic`: FCM topic name. Both `news` and `/topics/news` are accepted.
- `notification`: visible push notification content.
- `data`: custom key-value payload delivered with the message.
- `android`: optional Android-specific priority/channel/click action.
- `apns`: optional iOS-specific sound/badge options.

## Run

```bash
./gradlew run
```

You can also point to another config file:

```bash
./gradlew run --args="/absolute/path/to/fcm-sender.json"
```

or:

```bash
FCM_SENDER_CONFIG=/absolute/path/to/fcm-sender.json ./gradlew run
```

The process keeps running and sends once every `pollIntervalSeconds` until stopped with `Ctrl+C`.
