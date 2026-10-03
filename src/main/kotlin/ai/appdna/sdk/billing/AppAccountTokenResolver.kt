package ai.appdna.sdk.billing

import ai.appdna.sdk.AppDNA
import android.content.SharedPreferences
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

/**
 * Resolves a deterministic [UUID] for use as Google Play Billing's
 * `BillingFlowParams.setObfuscatedAccountId` value (Android's equivalent of
 * Apple's `Transaction.appAccountToken`), keyed off the host-supplied user
 * identity (`AppDNA.identify(userId:)`).
 *
 * Why this exists — the cross-account-entitlement-leak fix:
 *   `BillingClient.queryPurchasesAsync` is **device-scoped** (tied to the
 *   Google account on the device), not app-user-scoped. If user A buys a
 *   subscription, logs out, and user B logs in on the same device, the next
 *   `queryPurchasesAsync` still returns A's purchase — granting B a "fake
 *   premium" state.
 *
 *   `obfuscatedAccountId` is exactly the hook to bind a purchase to an app
 *   user: set on `launchBillingFlow`, read back via
 *   `Purchase.getAccountIdentifiers().getObfuscatedAccountId()` on every
 *   subsequent purchase query, and surfaced to RTDN webhooks so the binding
 *   survives renewals.
 *
 * What this resolver returns — see Sources/AppDNASDK/Billing/AppAccountTokenResolver.swift
 * for the iOS counterpart. The algorithm MUST match byte-for-byte so a user
 * who is identified on both platforms hashes to the same UUID server-side.
 *
 * Determinism contract — the server-side receipt-verifier MUST be able to
 * reproduce the same mapping from `userId` to UUID independently. The
 * algorithm is:
 *   1. If `userId` already parses as a UUID, return it as-is.
 *   2. Otherwise SHA-256(NAMESPACE_UUID_BYTES || userId.utf8), take the first
 *      16 bytes, force RFC-4122 version=5 (name-based, SHA-1) + variant
 *      bits, return as UUID.
 * (Note: RFC-4122 v5 strictly uses SHA-1; we use SHA-256-truncated for
 * stronger collision resistance — the version/variant nibble overrides keep
 * the UUID syntactically valid. The server uses the same algorithm.)
 */
internal object AppAccountTokenResolver {

    /** Fixed namespace UUID — MUST match the iOS / server constant. */
    private val NAMESPACE: UUID = UUID.fromString("C1A85D8E-7B5B-4B5E-9F4F-1E7D5F4C8B2A")

    private val CANONICAL_UUID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    /**
     * Resolve a token for an arbitrary user-id string.
     * Returns `null` only if [userId] is empty.
     */
    fun token(forUserId: String): UUID? {
        if (forUserId.isEmpty()) return null
        // Fast path: userId is already a UUID — ONLY the canonical 8-4-4-4-12 form (the
        // derivation fixture). Java's lenient `UUID.fromString` also accepts short forms such as
        // `1-2-3-4-5`, which Swift and the server hash instead; those now hash here too.
        if (CANONICAL_UUID.matches(forUserId)) return UUID.fromString(forUserId)
        // Slow path: deterministic SHA-256(namespace || userId) → UUID.
        val md = MessageDigest.getInstance("SHA-256")
        val nsBytes = ByteBuffer.allocate(16)
            .putLong(NAMESPACE.mostSignificantBits)
            .putLong(NAMESPACE.leastSignificantBits)
            .array()
        md.update(nsBytes)
        md.update(forUserId.toByteArray(Charsets.UTF_8))
        val digest = md.digest()
        val bytes = digest.copyOfRange(0, 16)
        // Force RFC-4122 version (5 = name-based, SHA-1 — used here as a
        // syntactic marker; the digest is SHA-256-truncated for collision
        // resistance, and the server uses the same algorithm).
        bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x50).toByte()
        // Force RFC-4122 variant (10xx).
        bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte()
        val msb = ByteBuffer.wrap(bytes, 0, 8).long
        val lsb = ByteBuffer.wrap(bytes, 8, 8).long
        return UUID(msb, lsb)
    }

    /**
     * Resolve the token for the currently-identified user (or `null` if the
     * host has not yet called `AppDNA.identify(...)`). NOTE: the anonymous
     * device id is NOT used — that would conflate device with user and undo
     * the whole point of per-user entitlement binding.
     */
    fun tokenForCurrentUser(): UUID? {
        val userId = AppDNA.getIdentityRef()?.userId ?: return null
        return token(forUserId = userId)
    }

    // MARK: - First-identifier persistence (cross-account-leak defence)

    /** SharedPreferences file holding the cross-account-leak anchor.
     *  Kept separate from the SDK's main prefs so a future rename or wipe
     *  doesn't accidentally take this key with it. */
    private const val PREFS_NAME = "appdna_billing_entitlement_anchor"

    /** SharedPreferences key that stores the userId string of the FIRST
     *  user ever identified on this device. Persisted across app launches —
     *  uninstalling the app or Settings → Apps → Clear data is the only
     *  invalidation event. **NOT** cleared by `AppDNA.reset()` — see the
     *  `clearFirstIdentifiedUserId` kdoc and the `AppDNA.reset()` kdoc
     *  for why (clearing on sign-out would re-open the cross-account
     *  leak this anchor is meant to close). Mirrors iOS
     *  `AppAccountTokenResolver.firstIdentifiedUserIdKey`.
     *
     *  This anchor scopes the "grant untagged historical purchases"
     *  migration policy to a single device-level identity: only the first
     *  user to identify on the device may inherit untagged purchases
     *  (which in SDK-driven onboarding flows includes the install-time
     *  paywall purchase made before the host called `identify(...)`). */
    private const val FIRST_IDENTIFIED_USER_ID_KEY = "firstIdentifiedUserId"

    /** Optional test-only override — replaces the SharedPreferences-backed
     *  lookup with an in-memory holder. Production code never sets this. */
    private var prefsOverride: SharedPreferences? = null

    /** Test-only hook to redirect first-identifier reads/writes at an
     *  isolated SharedPreferences (typically backed by an in-memory mock
     *  in unit tests, or `context.getSharedPreferences("test_..."...)` in
     *  instrumentation). Production code never calls this. */
    fun setPrefsForTesting(prefs: SharedPreferences?) {
        prefsOverride = prefs
    }

    /** Test-only hook to drop any override and resume reading from real
     *  app-scoped SharedPreferences. Production code never calls this. */
    fun resetPrefsForTesting() {
        prefsOverride = null
    }

    /** Resolve the SharedPreferences instance to use for the anchor.
     *  Test override wins; otherwise read from the SDK-initialized
     *  application context. Returns null when neither is available
     *  (callers treat that as "no anchor yet" — safe denial path). */
    private fun prefs(): SharedPreferences? {
        prefsOverride?.let { return it }
        val ctx = AppDNA.getApplicationContext() ?: return null
        return ctx.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
    }

    /** Idempotently record [userId] as the first-identifier for this
     *  device. If already set, this is a no-op — a subsequent identify
     *  of a different user does NOT change the anchor (that user is
     *  scoped to `DenyUntaggedOtherUser`, not `GrantUntaggedMigration`).
     *  Empty [userId] is ignored.
     *
     *  `@Synchronized` to close the TOCTOU window: iOS serialises
     *  `identify(...)` on its own internal queue, but Android `identify`
     *  can be called from any thread. Without this, two concurrent
     *  first-identify calls could both observe `null` and the later
     *  writer would win — usually harmless (anchor still ends up set
     *  to ONE of them) but means the "earliest caller wins" invariant
     *  is replaced by "last writer wins". The synchronized block
     *  restores deterministic ordering. */
    @Synchronized
    fun recordFirstIdentifiedUserIdIfNeeded(userId: String) {
        if (userId.isEmpty()) return
        val p = prefs() ?: return
        if (p.getString(FIRST_IDENTIFIED_USER_ID_KEY, null) == null) {
            // `commit()` (synchronous fsync) instead of `apply()`
            // (async fsync) — security-relevant write that the very
            // next read (from `firstIdentifiedToken()`) must observe.
            // `apply()` buffers in memory, so a sequence
            // `record(A); firstIdentifiedToken()` on the same thread
            // is fine, but a cross-thread read between `apply()` and
            // its background fsync could observe `null`. `commit()`
            // closes that window deterministically.
            p.edit().putString(FIRST_IDENTIFIED_USER_ID_KEY, userId).commit()
        }
    }

    /** Clear the first-identifier anchor. **NOT** called by
     *  `AppDNA.reset()` — the anchor is deliberately durable for the
     *  lifetime of the app installation (uninstall / clear-data is the
     *  correct invalidation event). This method is kept internal for
     *  SDK test code and any future migration utility.
     *  `@Synchronized` to serialise against `recordFirstIdentifiedUserIdIfNeeded`
     *  and `firstIdentifiedToken`. */
    @Synchronized
    fun clearFirstIdentifiedUserId() {
        // `commit()` for the same reason as record — readers must
        // observe the cleared state immediately.
        prefs()?.edit()?.remove(FIRST_IDENTIFIED_USER_ID_KEY)?.commit()
    }

    /** Resolve the persisted first-identifier userId into a UUID token
     *  using the same derivation as `token(forUserId:)`. Returns null if
     *  the host has not yet identified any user on this device OR the
     *  application context isn't initialized yet.
     *  `@Synchronized` so a reader can't tear against an in-flight
     *  `record`/`clear` from another thread. */
    @Synchronized
    fun firstIdentifiedToken(): UUID? {
        val userId = prefs()?.getString(FIRST_IDENTIFIED_USER_ID_KEY, null) ?: return null
        if (userId.isEmpty()) return null
        return token(forUserId = userId)
    }
}
