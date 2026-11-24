# BLACKSTRINGS

Serverless P2P Tor Hidden Service Chat App for Android (Kotlin + WebView SPA).

## Features
- **Zero-Server Tor v3 Onion Transport**: Each device generates its own Tor v3 `.onion` address and X25519 identity keypair on first launch.
- **Double Ratchet + AES-256-GCM**: End-to-end encryption for text, photos, videos, files, and voice notes.
- **Offline Outbox Queue**: Messages are saved to SQLite `outbox` with `PENDING` status and retried with exponential backoff (`1m`, `5m`, `15m`, `1h`).
- **Store-and-Forward Mailbox Mode**: Any node can act as an encrypted blind relay for offline friends.
- **Battery-Aware Tor Lifecycle**: Tor syncs for 30 seconds on app open and sleeps; `WorkManager` runs background sync every 15 minutes on unmetered WiFi.

## Build APK via GitHub Actions
1. Push this repository to GitHub (`main` branch).
2. Open the **Actions** tab -> **Build BLACKSTRINGS Debug APK**.
3. Download the `blackstrings-debug-apk` artifact (`app-debug.apk`).
