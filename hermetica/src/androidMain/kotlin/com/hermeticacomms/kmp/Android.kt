package com.hermeticacomms.kmp

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** SharedPreferences (thread-safe) under the name "hermetica". */
class SharedPreferencesStore(context: Context) : KeyValueStore {
    private val prefs = context.applicationContext.getSharedPreferences("hermetica", Context.MODE_PRIVATE)
    override fun get(key: String): String? = prefs.getString(key, null)
    override fun set(key: String, value: String) = prefs.edit().putString(key, value).apply()
    override fun remove(key: String) = prefs.edit().remove(key).apply()
}

/** True in debuggable builds: the push environment to report. */
fun isDebugBuild(context: Context) = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

/**
 * The client for Android, with the device details filled in. Flushes the queue when the last activity stops (the app went
 * to the background). Call once, in `Application.onCreate`.
 */
fun hermeticaAndroid(context: Context, siteKey: String, baseUrl: String, config: Config = Config()): Hermetica {
    val app = context.applicationContext
    val pkg = runCatching { app.packageManager.getPackageInfo(app.packageName, 0) }.getOrNull()
    val build = if (pkg == null) "" else if (Build.VERSION.SDK_INT >= 28) pkg.longVersionCode.toString() else @Suppress("DEPRECATION") pkg.versionCode.toString()
    val device = DeviceMetadata(
        deviceModel = Build.MODEL ?: "", osName = "android", osVersion = Build.VERSION.RELEASE ?: "",
        appName = app.applicationInfo.loadLabel(app.packageManager).toString(), appVersion = pkg?.versionName ?: "", appBuild = build, appId = app.packageName,
    )
    val hm = Hermetica(siteKey, baseUrl, SharedPreferencesStore(app), "android", device, config)
    (app as? Application)?.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
        private var started = 0
        override fun onActivityStarted(a: Activity) { started++ }
        override fun onActivityStopped(a: Activity) { if (--started == 0) CoroutineScope(Dispatchers.Default).launch { hm.flush() } }
        override fun onActivityCreated(a: Activity, b: Bundle?) = Unit
        override fun onActivityResumed(a: Activity) = Unit
        override fun onActivityPaused(a: Activity) = Unit
        override fun onActivitySaveInstanceState(a: Activity, b: Bundle) = Unit
        override fun onActivityDestroyed(a: Activity) = Unit
    })
    return hm
}
