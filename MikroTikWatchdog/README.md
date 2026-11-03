# MikroTik Watchdog (Android)

An Android Studio project for monitoring multiple MikroTik routers from the phone's current internet connection. Each router uses exactly three user-entered fields:

1. Remote IP address or DNS name (optionally `host:port`; default port is TCP 8729)
2. RouterOS username
3. RouterOS password

## What it does

- Runs an Android foreground service and probes each saved router approximately every 15 seconds.
- Sends one ICMP ping from the phone to the router's remote IP. Packet-loss percentage is calculated over the latest 20 probes.
- Performs a RouterOS API-SSL login check every 5 minutes on TCP 8729 (or the port appended to the address).
- Sends an alert after three consecutive missed pings and another when ping responses return.
- Stores router credentials encrypted with AES-GCM using an Android Keystore key. Android backup is disabled.
- Restarts monitoring after device reboot when routers have been saved.

## Important networking requirements

This build uses **RouterOS API-SSL**, not SSTP. On each MikroTik:

1. Enable the `api-ssl` service (normally TCP 8729) under **IP → Services**.
2. Configure a valid TLS certificate for API-SSL. Android's normal certificate validation is intentionally enabled; the app does not disable TLS verification.
3. Add a firewall rule allowing TCP 8729 only from the phone's public source IP (or another tightly controlled source range). Do not expose the RouterOS API to the entire internet.
4. Use a dedicated RouterOS user with only the permissions required for API login/monitoring. Do not use the full administrator account if a restricted account is sufficient.
5. Ensure the router has a public/reachable address or DNS name and that upstream NAT forwards the API-SSL port to the router.

If you use a nonstandard API-SSL port, enter it in the first field as `your-host:port`. The connection is still TLS. Plaintext API port 8728 is not used by this app.

## What the status means

- **ONLINE / NO PING** is based on ICMP reachability from the Android phone. Some mobile carriers, Wi-Fi networks, or firewalls block ICMP. In that case, the router may be reachable for API login while ping reports loss.
- **API-SSL authenticated** confirms that the supplied RouterOS credentials were accepted over TLS.
- **API login / port / TLS check failed** can mean wrong credentials, disabled API-SSL, blocked TCP port, an untrusted/expired certificate, or an incorrect port. It does not by itself prove the router is offline.
- This measures reachability and packet loss between the phone's current network and the router's public address. It is not a measurement of packet loss on the router's LAN or on a separate backhaul path.

## Build the APK

This workspace does not include an Android SDK or a prebuilt APK. To build:

1. Install Android Studio on a computer.
2. Extract/open this `MikroTikWatchdog` project in Android Studio.
3. Allow Gradle to sync and install Android SDK Platform 35 if prompted.
4. Select **Build → Build Bundle(s) / APK(s) → Build APK(s)**.
5. Android Studio will show the generated APK location (normally `app/build/outputs/apk/debug/app-debug.apk`).
6. Copy the APK to your Android phone and install it. Allow notifications and keep the app's battery usage unrestricted for best continuous monitoring.

## Operational limitations

Android and some phone manufacturers may stop background work under aggressive battery-saving policies. The app uses a foreground service and persistent notification, but true uninterrupted 24/7 operation cannot be guaranteed if the OS force-stops the app, the phone reboots without allowing startup, the phone loses internet, or the manufacturer suppresses foreground services. Test on your device before relying on it for critical network operations.

This is version 1.0 source. It has not been compiled or tested on a physical Android device in this environment because the Android SDK/build tools are not installed here.
