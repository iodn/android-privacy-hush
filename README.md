# Hush - Android Privacy Control

<img src="assets/monkey.png" width="120" alt="Hush icon" align="left" />

Hush changes privacy and connectivity settings on your phone when you lock or unlock the screen. You choose which settings change and when they change.

<br clear="left" />

## What it does

Hush can turn off Wi-Fi, Bluetooth, mobile data, location, NFC, the camera, and the microphone when you lock your phone. It can also turn on airplane mode or battery saver. Each control has its own lock and unlock rules, with delays up to 30 minutes.

You can make exceptions for calls, connected devices, media playback, and apps you want to leave alone. The available options depend on the control and your phone.

Once protection is enabled, you can close the app. The Android service handles your rules in the background and starts again after a reboot and the first unlock. If you force-stop Hush in Android settings, open it again to resume protection.

## Getting started

Hush needs Android 8.0 or newer and access through Root, Sui, Shizuku, or Dhizuku. Some controls depend on your Android version and access method. Camera and microphone privacy controls need Android 12 or newer.

Install the APK, open Hush, and go to **Settings > Permissions**. Grant access and complete the background setup shown there.

Tap a control on the home screen to configure it. Choose what happens when the phone locks, what happens when it unlocks, and how long to wait. **Use default** follows the timer in Settings. Turn on **Protection** when you are ready.

## Keeping Maps working

If you use Maps with the screen locked, open **Location Services** and enable **Keep on for Google Maps**. Enable the same option under **WiFi** and **Mobile Data** if you also want internet access to stay available.

The exception covers navigation and viewing Maps. It keeps services that are already on available while Maps is in use, then returns to your lock rules after Maps stops, with a short grace period. It needs Root, Sui, or Shizuku.

## Building

Use Flutter with Dart 3.11 or newer, the Android SDK, and Java 17 or newer.

```sh
flutter pub get
flutter run
```

To build an APK:

```sh
flutter build apk --release
```

The APK is written to `build/app/outputs/flutter-apk/app-release.apk`. Local release builds use development signing; configure your own release key before publishing.

## Checking changes

```sh
flutter analyze
flutter test
cd android
./gradlew :app:testDebugUnitTest
```

The interface is built with Flutter. Kotlin and Java handle the Android controls and background service. Test those controls on a physical phone, since permissions and system behavior vary between devices.

## License

Licensed under the GNU GPLv3. See [LICENSE](LICENSE).

## More Apps by KaijinLab

| App                                                               | What it does                                                                 |
| ----------------------------------------------------------------- | ---------------------------------------------------------------------------- |
| **[IR Blaster](https://github.com/iodn/android-ir-blaster)**      | Control and test infrared functionality for compatible devices.              |
| **[USBDevInfo](https://github.com/iodn/android-usb-device-info)** | Inspect USB device details and behavior to understand what is connected.      |
| **[GadgetFS](https://github.com/iodn/gadgetfs)**                  | Manage rooted Android USB gadget roles with ConfigFS.                        |
| **[TapDucky](https://github.com/iodn/tap-ducky)**                 | Run controlled DuckyScript HID workflows on supported rooted devices.        |
| **[HIDWiggle](https://github.com/iodn/hid-wiggle)**               | Keep a connected host awake with Android-powered USB HID mouse movement.     |
| **[AKTune](https://github.com/iodn/android-kernel-tweaker)**      | Adaptive Android kernel auto-tuner for CPU/GPU/scheduler/memory/I/O.         |
