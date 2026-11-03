package il.ac.tau.lecturesummarizer.data

import android.content.Context
import il.ac.tau.lecturesummarizer.BuildConfig

/** Subscription features. Every Pro-gated code path checks [Entitlements.has] (UI *and* workers). */
enum class ProFeature(val title: String, val he: String) {
    RETRO_SUMMARIZER("Retro summarizer", "סיכום רטרואקטיבי של שיעורים קודמים"),
    LIVE_RECORDINGS("Live recordings watch", "מעקב אחר הקלטות Panopto חדשות"),
}

/**
 * Single source of truth for Pro. Today: a local entitlement flag (debug/beta builds can self-activate a beta trial
 * from Settings). Later: Play Billing purchase / server-verified entitlement writes [setPro]; nothing else changes.
 */
class Entitlements(ctx: Context) {
    private val p = ctx.getSharedPreferences("entitlements", Context.MODE_PRIVATE)
    private val deviceId = Settings(ctx).deviceId
    /** Build-granted Pro (debug builds only): DEBUG_PRO or this install's device id is allowlisted. */
    val buildGrant: String? get() = when {
        !BuildConfig.DEBUG -> null
        BuildConfig.DEBUG_PRO -> "debug"
        isAllowlisted(deviceId, BuildConfig.PRO_DEVICE_IDS) -> "allowlist"
        else -> null
    }
    val isPro: Boolean get() = buildGrant != null || p.getBoolean("pro", false)
    /** "allowlist"/"debug" (build grant), "beta" (self-activated test), later "play:<orderId>" / "server". */
    val source: String? get() = buildGrant ?: p.getString("source", null)
    fun has(f: ProFeature) = isPro
    fun setPro(on: Boolean, source: String) = p.edit().putBoolean("pro", on).putString("source", if (on) source else null).apply()
    /** Upgrade path available in this build without a store purchase (beta testers). */
    val canSelfActivate get() = BuildConfig.DEBUG

    companion object {
        fun isAllowlisted(id: String, csv: String) = id.isNotBlank() && csv.split(',').any { it.trim().equals(id, ignoreCase = true) }
    }
}
