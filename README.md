# Hermetica for Kotlin Multiplatform

One client for Android, iOS (and JVM): events, identity, consent, push tokens and in-app messages, written once in common
Kotlin (ktor, kotlinx.serialization). Events are queued on the device, sent in batches, retried with backoff using one
`Idempotency-Key` per batch, kept while offline, and flushed when the app goes to the background.

```kotlin
// shared/build.gradle.kts
commonMain.dependencies { implementation("com.hermeticacomms:hermetica-kmp:0.1.0") }
```

```kotlin
// Android: Application.onCreate          iOS: at launch (from Swift: HermeticaKt.hermeticaIos(siteKey:baseUrl:config:))
val hm = hermeticaAndroid(this, "hm_pk_...", "https://api.example.com")
val hm = hermeticaIos("hm_pk_...", "https://api.example.com")

hm.setConsent(analytics = true, marketing = true)
hm.identify("user-42", email = "ada@example.com", traits = mapOf("plan" to "pro"))
hm.screen("Cart")
hm.track("added_to_cart", mapOf("sku" to "A1"), value = 1999, currency = "USD")
hm.reset() // logout
```

Every call returns at once and is safe from any thread; `flush()`, the push and in-app calls are `suspend`.

## Push

Hand over the token your platform code gets (FCM on Android, APNs on iOS):

```kotlin
hm.setPushToken(PushToken(token, vendor = "fcm", environment = if (isDebugBuild(context)) "debug" else "release"))
val link = hm.handlePushOpened(data) // the notification's data map; tracks $opened_push and returns the deep link
hm.unregisterPushToken()            // logout, before reset()
```

The token follows the profile: `identify` and `reset` re-register it.

## In-app messages

```kotlin
hm.onInAppTrigger = { t -> scope.launch {
    val m = hm.fetchInAppMessages(t).firstOrNull() ?: return@launch
    withContext(Dispatchers.Main) { showMessage(m) } // m.layout, m.content["blocks"], m.style: your UI
    hm.reportInAppEvent(m, "impression")
} }
```

It runs on every `screen` and `track`; fetch `InAppTrigger.AppOpen` yourself at launch. Targeting and frequency caps are applied
by the server per session.

## Consent

`setTrackingOptOut(true)` (or `setConsent(analytics = false, ...)`) drops the queue and stops tracking until opted back in;
requests carry `Sec-GPC: 1` meanwhile. Consent and suppression are enforced by the server too. Use the public site key only; a
secret `hm_sk_` key is refused.
