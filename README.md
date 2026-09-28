# BLEGossip

**Nearby messaging over Bluetooth Low Energy, built with Kotlin for Android.**

BLEGossip explores how Android phones can exchange short messages directly through BLE advertisements—without internet access, pairing, accounts, or a central messaging server.

> **Early prototype.** Broadcasts and addressed messages have been demonstrated between Android phones. Messages currently travel only to devices within direct radio range. Multi-hop store-and-forward, encrypted DMs, and reliable background operation are roadmap items.
>
> **DMs are addressed, not encrypted.** The recipient ID controls which app displays a message. A nearby BLE observer can still read its payload.

## What works today

| Capability | Current behavior |
| --- | --- |
| Nearby broadcasts | Scanning BLEGossip devices in range can display short broadcast messages. |
| Addressed messages | Enter an 8-hex recipient ID to send a message that the matching app displays as a DM. |
| Device IDs | Each installation uses a locally generated 8-hex ID saved in SharedPreferences and visible in the app. This is an app identifier, not a Bluetooth address or verified identity. |
| Connectionless transport | Message payloads are carried in BLE manufacturer-specific advertising data; Bluetooth pairing is not required. |
| Android compatibility | Permission handling covers older Android releases and the newer Nearby devices permission model. Hardware and OS behavior still vary. |
| Simple interface | View your ID, enter an optional recipient, compose short text, send, toggle scanning, and inspect the message log. |

**Known addressing pitfall:** a non-empty recipient that is not exactly eight hexadecimal characters currently falls back to a broadcast. Double-check the recipient before sending.

This is an Android-only experiment. The project name describes its direction; the current implementation is not yet a gossip mesh or a Bluetooth Mesh implementation.

## How it works

```text
Sender                                            Nearby receiver
Message + optional recipient ID                   Scanning enabled
             |                                           |
             v                                           v
Encode a small packet --> BLE advertisement --> Read manufacturer data
                                                       |
                                                       v
                                                Validate and decode
                                                       |
                                                       v
                                          Broadcast? Display the message.
                                          DM? Display if recipient = my ID.
```

The Kotlin activity coordinates the interface, local ID, permissions, advertising, and scan callbacks. Android's BLE advertiser emits manufacturer data, and the BLE scanner reads matching data from nearby advertisements.

At a high level, the application payload contains:

| Field | Purpose |
| --- | --- |
| Protocol marker | Four ASCII bytes: `BGOS`. |
| Message type | One byte: `B` for broadcast or `D` for an addressed DM. |
| Recipient ID | Eight ASCII hex characters; broadcasts use `FFFFFFFF`. |
| Text | UTF-8 text following the 13-byte application header. |

This describes the prototype's design, not a versioned interoperability specification. The current code uses manufacturer-data identifier `0xFFFF`. Devices need compatible packet formats and identifiers. Scanning is currently unfiltered at the Android scan call; the callback selects manufacturer data and checks the `BGOS` marker. Although the logs mention a service UUID, that UUID is not included in the outgoing advertisement or applied as a scan filter. The advertising payload is the message itself; there is currently no GATT connection to fetch a larger body.

Advertising repeats while active, so a scanner can observe the same packet many times. The receiver keeps up to 200 in-memory fingerprints of message type, recipient, text, and observed device address. This suppresses repeated display but can also hide a deliberately repeated identical message. The cache is lost when the activity is recreated. This is distinct from stable message IDs and deduplication suitable for relaying. Neither repeated advertisements nor duplicate suppression provides acknowledgements or guaranteed delivery.

## Setup and build

### Requirements

- Android Studio compatible with Android Gradle Plugin 9.0.0, Android SDK 36, and a compatible Gradle JDK. The repository pins Gradle 9.1.0 through its wrapper.
- Android 7.0 (API 24) or newer on the test phones; the current compile/target SDK is 36.
- Two physical Android phones for end-to-end testing. A sender must support BLE advertising, and a receiver must support BLE scanning. For testing both directions, both phones need both capabilities.
- Bluetooth enabled, plus the permissions requested by the app.
- USB debugging enabled when installing through Android Studio or ADB.

Use the repository's Gradle wrapper and build configuration as the authority for the minimum Android version and toolchain. BLE support alone does not guarantee advertising support; verify on actual devices.

### Run from Android Studio

1. Clone the repository and open its root in Android Studio:

   ```sh
   git clone https://github.com/ParsiaJoon/Bluetooth-Chat-App.git
   cd Bluetooth-Chat-App
   ```

2. Allow Gradle to sync and install any SDK components requested by the project.
3. Connect the first phone, select the app run configuration, and run it.
4. Repeat on the second phone using the same source revision.
5. Grant the requested permissions and keep both apps open in the foreground during testing.

The initial dependency download/build may require internet access. Nearby message exchange does not.

### Build from a terminal

From the repository root:

```sh
# macOS / Linux
./gradlew :app:assembleDebug

# Windows
gradlew.bat :app:assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. With one authorized phone connected, install it using `./gradlew :app:installDebug` (or `gradlew.bat :app:installDebug` on Windows).

### Android permissions

| Android release | What to check |
| --- | --- |
| Android 12+ | Grant Nearby devices access. The modern permission model separates `BLUETOOTH_SCAN`, `BLUETOOTH_ADVERTISE`, and `BLUETOOTH_CONNECT`; the last can be needed for protected Bluetooth APIs even though this protocol does not pair devices. |
| Android 7–11 | Grant the requested location permission for scanning. Legacy Bluetooth permissions are declared in the manifest. Some devices also require the system Location switch to be on for scan results. |

The current runtime code also requests fine location on Android 12+, but the manifest limits that permission to API 30. This can produce a location-denied log on newer Android; it is a known permission-flow cleanup item, not a reason to require location access there. After granting permissions, retry Send or toggle scanning off and on, because the permission callback does not automatically restart the operation.

Exact requirements depend on the OS, target SDK, and APIs used. See Android's [Bluetooth permission guide](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions) when changing the manifest or runtime permission flow.

## Test with two phones

Start with both phones 1–2 metres apart, Bluetooth on, permissions granted, and both apps in the foreground. Use a very short ASCII message such as `HI` for the first test.

Use different text for each send so the current fingerprint cache does not hide a repeated test message.

1. **Confirm IDs.** Each phone should show its own 8-hex ID, such as `A1B2C3D4`. Copy the receiver's displayed ID exactly.
2. **Enable reception.** Turn scanning on for phone A and check the log for scanning status.
3. **Send a broadcast.** On phone B, leave **To** blank, enter `HI`, and tap the send/broadcast button. Phone A should display the received text.
4. **Send a DM.** On phone B, enter phone A's ID in **To**, use a different short message, and send. Phone A should display it as a DM.
5. **Check address filtering.** Send a DM to a different, valid 8-hex ID that does not match phone A. Phone A should not display it. This verifies app filtering, not confidentiality.
6. **Reverse direction.** Enable scanning on phone B and repeat from phone A.

With a third scanning phone, broadcasts should appear on both receivers, while a DM should appear only on the matching receiver's normal UI. This still does not prevent the third phone from capturing the underlying radio data.

These are manual acceptance checks, not a claim of automated test coverage. Real-device testing is needed to verify BLE exchange and permission behavior.

## Limits and troubleshooting

| Symptom or limit | Explanation / next check |
| --- | --- |
| No received messages | Check Bluetooth, scan status, permissions, distance, matching builds, and advertising status. Check the Location switch on older Android devices. |
| Advertising fails | The sender may lack advertising support, permissions may be missing, or the packet may exceed the size budget. Start with a very short message and inspect the error log. |
| Long text fails or is cut short | Advertising space is small and shared with protocol/advertising overhead. UTF-8 bytes, not visible character count, determine size; emoji and many non-ASCII characters use multiple bytes. The sender currently applies `take(12)` before UTF-8 encoding: this limits Kotlin string units, not encoded bytes. Twelve ASCII characters fit this prototype's intended use; the same apparent length in other scripts may fail advertising. |
| The same message appears repeatedly | BLE advertisements repeat, and this build has no automatic send timeout. It stops the previous advertisement when sending a replacement and stops advertising in `onDestroy`. The 200-entry fingerprint cache only suppresses display while its entries remain available. |
| A DM does not appear | Verify all eight recipient-ID characters against the receiver's current display and ensure scanning is on. |
| The ID label is missing | Confirm both phones have the updated build and layout. UI fields must be bound before startup code updates them or appends to the log. |
| Messages stop with the screen off | Reliable background operation is not implemented. Keep the app in the foreground for prototype testing. |
| A distant device receives nothing | There is no relay path yet. Range depends on hardware, interference, and the environment; no range or delivery guarantee is provided. |

There are no delivery receipts, reliable ordering guarantees, or implemented multi-hop store-and-forward. Do not assume that an advertising-success log means another phone received the message, or that chat history and undelivered messages are durably stored.

For implementation details, see Android's [advertising data API](https://developer.android.com/reference/android/bluetooth/le/AdvertiseData.Builder).

## Privacy and security

- **All message bodies are currently plaintext over the air**, including DMs. Recipient-only display is a UI behavior, not access control.
- **Malformed recipients become broadcasts.** Invalid non-empty recipient input currently takes the broadcast path instead of showing a validation error.
- **IDs are not authentication.** An 8-hex ID is a short address, not a cryptographic identity. Collisions, impersonation, and crafted packets must be considered.
- **Integrity and replay protection are not established.** Do not trust received text as proof of who sent it or when it was originally sent.
- **Offline does not mean anonymous.** Nearby observers may capture message contents, recipient IDs, timing, and radio metadata. Persistent identifiers can help correlate activity.
- **Logs may expose messages.** Treat on-screen logs and any shared debugging output as potentially sensitive.

Use non-sensitive test messages. This early prototype is not suitable for confidential conversations or emergency communications that depend on reliable delivery.

## Roadmap

All items below are planned work, not current capabilities.

- [ ] **Input and permission cleanup:** reject malformed recipients, enforce the encoded byte budget, align runtime requests with the manifest, and give sends a bounded advertising window.
- [ ] **Message IDs and robust deduplication:** identify individual messages, distinguish a new message with identical text from a repeat, and bound the seen-message cache.
- [ ] **TTL and store-and-forward mesh:** relay unseen messages within a hop budget; add expiry, queue limits, persistence policy, and rate controls to prevent endless rebroadcasting.
- [ ] **Encrypted DMs:** introduce authenticated encryption, cryptographic identities, safe key exchange, and replay protection. An 8-hex destination alone is insufficient.
- [ ] **Longer messages through GATT:** use advertisements for discovery or a compact message reference, then fetch larger payloads with explicit size and resource limits.
- [ ] **Background operation:** design for Android lifecycle restrictions, permission requirements, user-visible service behavior where required, and battery impact; test screen-off behavior across devices.
- [ ] **Safer contact exchange:** make IDs and verified keys easier to exchange, for example with QR codes.
- [ ] **Protocol and compatibility tests:** document a versioned wire format and expand testing across Android releases and BLE hardware.

## Source guide

| File | Role |
| --- | --- |
| [MainActivity.kt](app/src/main/java/com/example/myapplication/MainActivity.kt) | UI wiring, ID storage, packet encoding/decoding, advertising, scanning, and duplicate filtering. Its declared package is `com.example.blegossip`. |
| [activity_main.xml](app/src/main/res/layout/activity_main.xml) | ID display, recipient and message inputs, Send button, scan toggle, and log. |
| [AndroidManifest.xml](app/src/main/AndroidManifest.xml) | Bluetooth capability and permission declarations. |
| [app/build.gradle.kts](app/build.gradle.kts) | App ID `com.example.blegossip`, SDK levels, and dependencies. |

## Development notes

Keep broadcast and DM display behavior distinct, validate packets before displaying them, and avoid describing an experiment as secure or reliable before those properties are implemented and tested. When reporting a bug, include device models, Android versions, the source revision, permission state, and a short reproduction using non-sensitive text.
