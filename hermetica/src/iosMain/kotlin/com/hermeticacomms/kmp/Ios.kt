package com.hermeticacomms.kmp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import platform.Foundation.NSBundle
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSUserDefaults
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIDevice

/** NSUserDefaults (thread-safe) in the suite "hermetica". */
class UserDefaultsStore(suiteName: String = "hermetica") : KeyValueStore {
    private val d = NSUserDefaults(suiteName = suiteName)
    override fun get(key: String): String? = d.stringForKey(key)
    override fun set(key: String, value: String) = d.setObject(value, forKey = key)
    override fun remove(key: String) = d.removeObjectForKey(key)
}

/** The client for iOS, with the device details filled in. Flushes the queue when the app goes to the background. Call once at launch. */
fun hermeticaIos(siteKey: String, baseUrl: String, config: Config = Config()): Hermetica {
    val info = NSBundle.mainBundle.infoDictionary
    fun str(k: String) = info?.get(k) as? String ?: ""
    val device = DeviceMetadata(
        deviceModel = UIDevice.currentDevice.model, osName = "ios", osVersion = UIDevice.currentDevice.systemVersion,
        appName = str("CFBundleName"), appVersion = str("CFBundleShortVersionString"), appBuild = str("CFBundleVersion"), appId = NSBundle.mainBundle.bundleIdentifier ?: "",
    )
    val hm = Hermetica(siteKey, baseUrl, UserDefaultsStore(), "ios", device, config)
    NSNotificationCenter.defaultCenter.addObserverForName(UIApplicationDidEnterBackgroundNotification, null, NSOperationQueue.mainQueue) { _ ->
        CoroutineScope(Dispatchers.Default).launch { hm.flush() }
    }
    return hm
}
