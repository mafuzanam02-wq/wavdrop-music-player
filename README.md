# Wavdrop Music Player

An offline-first Android music player built with Kotlin, Jetpack Compose, and Media3.

## Requirements
- Android 8.0+ (API 26)
- Java 17+
- Android Studio Ladybug or newer

## Getting Started

### Open in Android Studio
File → Open → select this directory. Studio will generate the Gradle wrapper and sync automatically.

### Build from command line
```bat
gradlew.bat assembleDebug
```
> First run requires a `gradle-wrapper.jar`. Generate it via Android Studio or by running  
> `gradle wrapper --gradle-version=8.9` with Gradle on your PATH.

## Project Structure
See [PROJECT_CONTEXT.md](PROJECT_CONTEXT.md) for full architecture details.

## Package
`com.launchpoint.wavdrop`

## Stack
Kotlin · Jetpack Compose · Hilt · Room · Media3 · DataStore · Coil
