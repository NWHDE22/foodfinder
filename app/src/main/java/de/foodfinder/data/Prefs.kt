package de.foodfinder.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("foodfinder", Context.MODE_PRIVATE)
    private val json = Http.json

    private fun <T> getJson(key: String, ser: kotlinx.serialization.KSerializer<T>, def: T): T =
        sp.getString(key, null)?.let { runCatching { json.decodeFromString(ser, it) }.getOrNull() } ?: def

    private fun <T> putJson(key: String, ser: kotlinx.serialization.KSerializer<T>, v: T) =
        sp.edit().putString(key, json.encodeToString(ser, v)).apply()

    var persons: Int
        get() = sp.getInt("persons", 2)
        set(v) = sp.edit().putInt("persons", v).apply()

    var filters: Filters
        get() = getJson("filters", Filters.serializer(), Filters())
        set(v) = putJson("filters", Filters.serializer(), v)

    var membership: MembershipState
        get() = getJson("membership", MembershipState.serializer(), MembershipState())
        set(v) = putJson("membership", MembershipState.serializer(), v)

    var places: List<SavedPlace>
        get() = getJson("places", ListSerializer(SavedPlace.serializer()), emptyList())
        set(v) = putJson("places", ListSerializer(SavedPlace.serializer()), v)

    /** null = GPS */
    var activePlaceId: String?
        get() = sp.getString("activePlace", null)
        set(v) = sp.edit().putString("activePlace", v).apply()

    var lastLat: Double?
        get() = sp.getString("lastLat", null)?.toDoubleOrNull()
        set(v) = sp.edit().putString("lastLat", v?.toString()).apply()
    var lastLon: Double?
        get() = sp.getString("lastLon", null)?.toDoubleOrNull()
        set(v) = sp.edit().putString("lastLon", v?.toString()).apply()
    var lastCity: String?
        get() = sp.getString("lastCity", null)
        set(v) = sp.edit().putString("lastCity", v).apply()
    var lastPlaceLabel: String?
        get() = sp.getString("lastLabel", null)
        set(v) = sp.edit().putString("lastLabel", v).apply()

    var recentSearches: List<String>
        get() = getJson("recent", ListSerializer(String.serializer()), emptyList())
        set(v) = putJson("recent", ListSerializer(String.serializer()), v.take(8))

    var vouchers: List<Voucher>
        get() = getJson("vouchers", ListSerializer(Voucher.serializer()), emptyList())
        set(v) = putJson("vouchers", ListSerializer(Voucher.serializer()), v.sortedByDescending { it.receivedAt }.take(80))

    /** uid -> last notified time */
    var notified: Map<String, Long>
        get() = getJson("notified", MapSerializer(String.serializer(), Long.serializer()), emptyMap())
        set(v) = putJson("notified", MapSerializer(String.serializer(), Long.serializer()), v)

    // notifications
    var notifyEnabled: Boolean
        get() = sp.getBoolean("notify", true)
        set(v) = sp.edit().putBoolean("notify", v).apply()
    var notifyMinSaving: Double
        get() = sp.getFloat("notifyMin", 5f).toDouble()
        set(v) = sp.edit().putFloat("notifyMin", v.toFloat()).apply()
    var notifyIntervalMin: Int
        get() = sp.getInt("interval", 60)
        set(v) = sp.edit().putInt("interval", v).apply()
    var notifyTgtg: Boolean
        get() = sp.getBoolean("notifyTgtg", true)
        set(v) = sp.edit().putBoolean("notifyTgtg", v).apply()

    // IMAP
    var imapHost: String
        get() = sp.getString("imapHost", "") ?: ""
        set(v) = sp.edit().putString("imapHost", v).apply()
    var imapPort: Int
        get() = sp.getInt("imapPort", 993)
        set(v) = sp.edit().putInt("imapPort", v).apply()
    var imapUser: String
        get() = sp.getString("imapUser", "") ?: ""
        set(v) = sp.edit().putString("imapUser", v).apply()
    var imapPassword: String
        get() = sp.getString("imapPwd", null)?.let { Crypto.decrypt(it) } ?: ""
        set(v) = sp.edit().putString("imapPwd", if (v.isEmpty()) null else Crypto.encrypt(v)).apply()
    var imapLastScan: Long
        get() = sp.getLong("imapLast", 0)
        set(v) = sp.edit().putLong("imapLast", v).apply()
    var imapLastResult: String
        get() = sp.getString("imapResult", "") ?: ""
        set(v) = sp.edit().putString("imapResult", v).apply()
    val imapConfigured get() = imapHost.isNotBlank() && imapUser.isNotBlank() && imapPassword.isNotBlank()

    // TGTG
    var tgtgEmail: String?
        get() = sp.getString("tgtgEmail", null)
        set(v) = sp.edit().putString("tgtgEmail", v).apply()
    var tgtgAccess: String?
        get() = sp.getString("tgtgAcc", null)?.let { Crypto.decrypt(it) }
        set(v) = sp.edit().putString("tgtgAcc", v?.let { Crypto.encrypt(it) }).apply()
    var tgtgRefresh: String?
        get() = sp.getString("tgtgRef", null)?.let { Crypto.decrypt(it) }
        set(v) = sp.edit().putString("tgtgRef", v?.let { Crypto.encrypt(it) }).apply()
    var tgtgUserId: String?
        get() = sp.getString("tgtgUid", null)
        set(v) = sp.edit().putString("tgtgUid", v).apply()
    var tgtgAccessAt: Long
        get() = sp.getLong("tgtgAt", 0)
        set(v) = sp.edit().putLong("tgtgAt", v).apply()
    var tgtgAppVersion: String
        get() = sp.getString("tgtgVer", "25.11.0") ?: "25.11.0"
        set(v) = sp.edit().putString("tgtgVer", v).apply()
    var tgtgPollingId: String?
        get() = sp.getString("tgtgPoll", null)
        set(v) = sp.edit().putString("tgtgPoll", v).apply()
}

/** AES-GCM with a key held in the Android Keystore; secrets never leave the device. */
object Crypto {
    private const val ALIAS = "foodfinder_secret"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build())
        return gen.generateKey()
    }

    fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val out = c.iv + c.doFinal(plain.toByteArray())
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    fun decrypt(enc: String): String? = runCatching {
        val all = Base64.decode(enc, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, all.copyOfRange(0, 12)))
        String(c.doFinal(all.copyOfRange(12, all.size)))
    }.getOrNull()
}
