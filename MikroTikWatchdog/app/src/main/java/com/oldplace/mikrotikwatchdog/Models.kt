package com.oldplace.mikrotikwatchdog

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import java.util.UUID

data class RouterConfig(val id: String, val host: String, val username: String, val password: String)
data class RouterStatus(
    val id: String, val online: Boolean, val latencyMs: Long?, val lossPercent: Int,
    val sent: Int, val lost: Int, val apiOk: Boolean?, val apiCheckedAt: Long,
    val updatedAt: Long, val consecutiveFailures: Int
)

object SecureConfigStore {
    private const val PREF = "secure_router_configs"
    private const val KEY = "router_list"
    private const val ALIAS = "mikrotik_watchdog_aes_key"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build())
        return gen.generateKey()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val data = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + data, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val all = Base64.decode(value, Base64.NO_WRAP)
        val iv = all.copyOfRange(0, 12)
        val data = all.copyOfRange(12, all.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(data), Charsets.UTF_8)
    }

    @Synchronized fun load(context: Context): MutableList<RouterConfig> {
        val encrypted = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, null) ?: return mutableListOf()
        return try {
            val arr = JSONArray(decrypt(encrypted))
            MutableList(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                RouterConfig(o.getString("id"), o.getString("host"), o.getString("username"), o.getString("password"))
            }
        } catch (_: Exception) { mutableListOf() }
    }

    @Synchronized fun save(context: Context, routers: List<RouterConfig>) {
        val arr = JSONArray()
        routers.forEach { r -> arr.put(JSONObject().put("id", r.id).put("host", r.host).put("username", r.username).put("password", r.password)) }
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY, encrypt(arr.toString())).apply()
    }

    fun add(context: Context, host: String, username: String, password: String): RouterConfig {
        val list = load(context)
        val r = RouterConfig(UUID.randomUUID().toString(), host, username, password)
        list.add(r); save(context, list); return r
    }

    fun remove(context: Context, id: String) { val list = load(context); list.removeAll { it.id == id }; save(context, list) }
}

object StatusStore {
    private const val PREF = "router_status"
    fun put(context: Context, s: RouterStatus) {
        val o = JSONObject().put("online", s.online).put("latency", s.latencyMs ?: -1L)
            .put("loss", s.lossPercent).put("sent", s.sent).put("lost", s.lost)
            .put("apiOk", s.apiOk ?: false).put("apiKnown", s.apiOk != null)
            .put("apiCheckedAt", s.apiCheckedAt).put("updatedAt", s.updatedAt)
            .put("failures", s.consecutiveFailures)
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(s.id, o.toString()).apply()
    }
    fun get(context: Context, id: String): RouterStatus? {
        return try {
            val raw = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(id, null) ?: return null
            val o = JSONObject(raw)
            val latency = o.optLong("latency", -1)
            RouterStatus(id, o.optBoolean("online"), if (latency < 0) null else latency,
                o.optInt("loss"), o.optInt("sent"), o.optInt("lost"),
                if (o.optBoolean("apiKnown")) o.optBoolean("apiOk") else null,
                o.optLong("apiCheckedAt"), o.optLong("updatedAt"), o.optInt("failures"))
        } catch (_: Exception) { null }
    }
    fun clear(context: Context, id: String) { context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().remove(id).apply() }
}
