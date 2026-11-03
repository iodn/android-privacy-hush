package org.kaijinlab.hush.util

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.kaijinlab.hush.data.FeatureState
import org.kaijinlab.hush.data.PrivacyFeature
import org.kaijinlab.hush.privacy.PrivacyManager
import org.kaijinlab.hush.root.RootManager

/** Exceptions are scoped to one lock cycle and never turn on a control Hush did not turn off. */
internal open class MapsConnectivityGuard(protected val context: Context) {
    companion object {
        val FEATURES = setOf(PrivacyFeature.LOCATION, PrivacyFeature.WIFI, PrivacyFeature.MOBILE_DATA)
        const val GRACE_MS = 15_000L
        const val WARNING_KEY = "maps_detection_unverified"
        private const val ACTIVE_GEN = "maps_active_generation"
        private const val ACTIVE_AT = "maps_active_at"
        private val mutex = Mutex()
        private fun retained(feature: PrivacyFeature) = "maps_retained_${feature.name}"
        private fun owned(feature: PrivacyFeature) = "maps_turned_off_${feature.name}"
        fun recordDisabled(context: Context, feature: PrivacyFeature, generation: Long) {
            val settings = PreferenceManager.getInstance(context)
            if (feature in FEATURES && settings.getFeatureKeepForMaps(feature) && settings.isGlobalPrivacyEnabled &&
                DeferredActionWork.generation(context) == generation && isScreenCurrentlyLocked(context, "hush-Maps")) {
                settings.getRawPreferences().edit().putLong(owned(feature), generation).remove(retained(feature)).commit()
            }
        }
    }
    private val settings get() = PreferenceManager.getInstance(context)
    private val prefs get() = settings.getRawPreferences()
    private val root by lazy { RootManager.getInstance(Unit) }
    private val detector by lazy { MapsUsageDetector(context, root) }
    private var scope: CoroutineScope? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val poll = object : Runnable {
        override fun run() {
            val activeScope = scope ?: return
            activeScope.launch {
                try { tick() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { android.util.Log.w("hush-Maps", "Maps exception check failed", error) }
                finally { if (scope === activeScope && isActive) scheduleNext() }
            }
        }
    }
    protected open fun now(): Long = SystemClock.elapsedRealtime()
    protected open fun locked(): Boolean = isScreenCurrentlyLocked(context, "hush-Maps")
    protected open suspend fun observe(): MapsUsage = detector.read()
    protected open suspend fun prepare(): Boolean { root.initialize(context); return root.isRootGranted() }
    protected open suspend fun state(feature: PrivacyFeature): FeatureState = PrivacyManager.getInstance(context).getFeatureState(feature)
    protected open suspend fun restore(feature: PrivacyFeature): Boolean {
        // Re-enabling a Wi-Fi client can interrupt an active hotspot on some phones.
        if (feature == PrivacyFeature.WIFI && ConnectionStateChecker(context, root).isHotspotActive()) return false
        return PrivacyManager.getInstance(context).enableFeatures(setOf(feature)).singleOrNull()?.success == true
    }
    protected open fun queueDisable(features: Set<PrivacyFeature>, generation: Long) =
        DeferredActionWork.enqueue(context, features, true, 0, generation)

    private suspend fun keep(generation: Long): Boolean {
        val usage = observe()
        prefs.edit().putBoolean(WARNING_KEY, !usage.verified).apply()
        if (usage.active) prefs.edit().putLong(ACTIVE_GEN, generation).putLong(ACTIVE_AT, now()).apply()
        val recent = prefs.getLong(ACTIVE_GEN, -1) == generation &&
            now() - prefs.getLong(ACTIVE_AT, 0) in 0..GRACE_MS
        return usage.active || !usage.verified || recent
    }

    suspend fun shouldKeep(feature: PrivacyFeature): Boolean {
        if (feature !in FEATURES || !settings.getFeatureKeepForMaps(feature)) return false
        return mutex.withLock {
            val generation = DeferredActionWork.generation(context)
            val keep = keep(generation)
            if (keep && DeferredActionWork.generation(context) == generation) {
                prefs.edit().putLong(retained(feature), generation).apply()
            }
            keep
        }
    }

    suspend fun tick() {
        val configured = FEATURES.filter { settings.getFeatureKeepForMaps(it) && settings.getFeatureDisableOnLock(it) }
        if (!settings.isGlobalPrivacyEnabled || configured.isEmpty() || !locked() || !prepare()) return
        mutex.withLock {
            val generation = DeferredActionWork.generation(context)
            val keep = keep(generation)
            val release = mutableSetOf<PrivacyFeature>()
            for (feature in configured) {
                if (!settings.isGlobalPrivacyEnabled || !locked() || DeferredActionWork.generation(context) != generation) return@withLock
                if (keep && prefs.getLong(owned(feature), -1) == generation) {
                    val current = state(feature)
                    if (current == FeatureState.ENABLED || (current == FeatureState.DISABLED && restore(feature))) {
                        prefs.edit().remove(owned(feature)).putLong(retained(feature), generation).commit()
                    }
                } else if (!keep && prefs.getLong(retained(feature), -1) == generation) {
                    val current = state(feature)
                    // An unreadable state is retried; it is not permission to switch anything.
                    if (current == FeatureState.ENABLED || current == FeatureState.DISABLED) {
                        if (current == FeatureState.DISABLED) prefs.edit().remove(retained(feature)).apply()
                        else release += feature
                    }
                }
            }
            if (release.isNotEmpty() && settings.isGlobalPrivacyEnabled && locked() && DeferredActionWork.generation(context) == generation) {
                try { queueDisable(release, generation) }
                catch (error: Exception) {
                    for (feature in release) prefs.edit().putLong(retained(feature), generation).apply()
                    throw error
                }
            }
        }
    }

    private fun scheduleNext() { handler.postDelayed(poll, 5000) }
    fun start() {
        if (scope != null) return
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        handler.postDelayed(poll, 5000)
    }
    fun stop() {
        scope?.cancel()
        scope = null
        handler.removeCallbacks(poll)
    }
}
