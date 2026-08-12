# Installation

## Prerequisites

### JDK 21+

The Fuel Dashboard is a JVM application built with Kotlin 2.3.21. You need
**JDK 21 or later** installed.

```bash
# Verify your JDK version
java -version
# openjdk version "21.0.x" or later
```

??? tip "Installing JDK 21"
    === "Ubuntu/Debian"
        ```bash
        sudo apt install openjdk-21-jdk
        ```
    === "macOS (Homebrew)"
        ```bash
        brew install openjdk@21
        ```
    === "SDKMAN"
        ```bash
        sdk install java 21.0.x-tem
        ```

### Gradle

The project includes the Gradle wrapper, so you do **not** need to install Gradle
separately. The wrapper script (`./gradlew`) downloads the correct version
automatically (Gradle 9.6.1).

If you prefer a system Gradle install, ensure it's 9.6+.

### Git

Standard `git` for cloning the repository.

## Clone the Repository

```bash
git clone https://git.angussoftware.dev/coda/fuel-dashboard-kmp.git
cd fuel-dashboard-kmp
```

## Building

### Desktop Distribution

Build native installers for your current OS (DEB/RPM on Linux, DMG on macOS,
MSI/EXE on Windows):

```bash
./gradlew :composeApp:packageDistributionForCurrentOS
```

Artifacts are written to `composeApp/build/compose/binaries/main/`.

### Android APK

```bash
./gradlew :composeApp:assembleDebug
```

The APK is written to `composeApp/build/outputs/apk/debug/`.

## Running the Desktop App

### From Source (Development)

```bash
./gradlew :composeApp:run
```

This launches the Compose Desktop window (1200×800 default) and starts the embedded
HTTP server on port **8322**.

### First Launch

On first launch:

1. The embedded server generates a random API key and persists it.
2. The app opens with a default theme.
3. No providers are configured yet — see [Configuration](configuration.md) to add
   your API keys.

### Verifying the Server

Once the app is running, verify the server is up:

```bash
# Health check (no auth required)
curl http://localhost:8322/health
# {"status":"ok"}

# Fuel state (requires API key — get it from Settings → Server)
curl -H "Authorization: Bearer YOUR_API_KEY" http://localhost:8322/fuel
```

## Project Structure

```
composeApp/src/
├── commonMain/kotlin/.../fueldashboard/
│   ├── model/         — API data classes + FuelSource interface
│   ├── network/       — Ktor HTTP client + provider adapters
│   ├── engine/        — Decision engine (tier selection, utilization ratio)
│   ├── presentation/  — ViewModel with StateFlow + polling
│   ├── settings/      — ThemeController + fuel settings storage
│   ├── storage/       — Local history + burn rate calculator
│   └── ui/            — Compose UI (dashboard, components, settings panel)
├── desktopMain/       — JVM entry point, EmbeddedServer, FuelMcpServer
├── androidMain/       — Android entry point (MainActivity)
└── iosMain/           — iOS stub
```
