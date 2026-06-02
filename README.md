# FCM Server

Kotlin command-line service for periodically sending custom Android Firebase Cloud Messaging messages to multiple topics.

## Configuration

Copy the example config and fill in your own values:

```bash
cp config/fcm-sender.example.json config/fcm-sender.json
```

The JSON root contains:

- `serverPort`: local management page port. Default is `9999`.
- `configs`: FCM sender list. Each item is managed independently.

Each `configs[]` item supports:

- `id`: unique config id.
- `name`: display/log name.
- `enabled`: `true` sends on schedule, `false` pauses.
- `googleServiceAccount.path`: Google Console service-account JSON path. If omitted, it defaults to `<name>/google-service-account.json`, relative to the directory that contains `fcm-sender.json`.
- `pollIntervalSeconds`: send interval in seconds.
- `topic`: FCM topic name. Both `news` and `/topics/news` are accepted.
- `notification`: visible push notification content.
- `data`: custom key-value payload delivered with the message.
- `android`: optional Android-specific priority/channel/click action.
- `reviewStatusCheck`: optional manual Google Play review-status checker. Set `packageName` to the Android application id and `track` to one of these Google Play tracks: `production` (正式版 / Production), `beta` (开放式测试 / Open Testing), `internal` (内部测试 / Internal Testing), or `alpha` (封闭式测试 / Closed Testing, default). The console checks the entered Google Play `versionCode` with the configured service account and persists the last displayed result.

Place Google Console service-account JSON files under each config item's folder next to `fcm-sender.json`, or set `googleServiceAccount.path` to an absolute path. Relative paths are resolved from the directory that contains `fcm-sender.json`. If the configured file is missing, the sender also checks the same directory for a Firebase Admin SDK file named like `xxx-firebase-adminsdk-xxx-xxx.json`.

For review-status checks, grant the same service account access to the app in Google Play Console and allow the Google Play Android Developer API. The checker uses the read-only Google Play releases endpoint, so it only reads release status and does not create or modify Play Console edits. The check treats `COMPLETED` and `IN_PROGRESS` releases on the selected track as online.

Android messages are sent with high priority and a 10-second TTL, so undelivered messages expire quickly instead of being cached for later delivery.

## Run

```bash
./gradlew run
```

Open the local console:

```text
http://127.0.0.1:9999/
```

If `serverPort` is changed, use that port instead. The console loads existing configs and supports adding, pausing, re-enabling, removing items, and manually checking Google Play status by versionCode. New items start paused until enabled. Saves update `fcm-sender.json` and notify the sender to reload immediately; the sender also refreshes from disk every 1 minute.

You can point to another config file:

```bash
./gradlew run --args="/absolute/path/to/fcm-sender.json"
```

or:

```bash
FCM_SENDER_CONFIG=/absolute/path/to/fcm-sender.json ./gradlew run
```

The process keeps running until stopped with `Ctrl+C`.
