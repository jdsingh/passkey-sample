# WebView JavaScript Bridge

An Android application demonstrating WebView JavaScript bridge communication with JSON result
handling for testing purposes.

## Getting Started

### Prerequisites

- Android Studio Arctic Fox or later
- Android SDK 30+ (API level 30+)
- Android device or emulator for testing

### Testing

Run the comprehensive test suite:

```bash
./gradlew connectedAndroidTest
```

## Technical Details

### Dependencies

- **Android Gradle Plugin**: 8.5.2
- **Kotlin**: 1.9.0
- **Compose BOM**: 2024.04.01
- **AndroidX Core**: 1.15.0
- **Espresso**: 3.6.1 (for testing)

### Permissions

- `INTERNET`: Required for WebView network access (though using local HTML)

### API Level Support

- **Minimum SDK**: 30
- **Target SDK**: 35
- **Compile SDK**: 35
