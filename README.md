# Travel.pk Android App

Android app for [travel.pk](https://travel.pk) (Kotlin, WebView).

- Package: `pk.travel.app`
- minSdk 24, targetSdk 37
- Bottom tabs: Home, Flights, Stays, Umrah, My Trips
- WhatsApp button, pull-to-refresh, offline screen, file upload, PDF download

## Build

Requires JDK 17 and the Android SDK.

1. Create `local.properties` with your SDK path:
   ```
   sdk.dir=C\:\\Users\\YOU\\AppData\\Local\\Android\\Sdk
   ```
2. Create `keystore.properties` (not in git — keep it private):
   ```
   storeFile=D\:/travel_pk_app/keystore/travelpk-release.jks
   storePassword=...
   keyAlias=travelpk
   keyPassword=...
   ```
3. Build the signed release APK:
   ```
   gradlew.bat assembleRelease
   ```
   Output: `app/build/outputs/apk/release/app-release.apk`

Main code: `app/src/main/java/pk/travel/app/MainActivity.kt`
