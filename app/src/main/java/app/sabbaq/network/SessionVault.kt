package app.sabbaq.network

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keeps the signed-in session across process death and reboots, so monitoring can resume
 * on its own. Only the refresh token and the bound device id are stored, encrypted with an
 * AES-GCM key that never leaves the Android Keystore; the short-lived access token stays in
 * memory. Backups are disabled for the app, and signing out or deleting the account wipes it.
 */
internal object SessionVault {
    private const val PREFS = "sabbaq_session_vault"
    private const val KEY_ALIAS = "sabbaq_session_v1"
    private const val FIELD = "sealed"
    private const val SEPARATOR = '\n'

    data class Saved(val refreshToken: String, val deviceId: String)

    fun save(context: Context, refreshToken: String, deviceId: String): Boolean = runCatching {
        require(SEPARATOR !in refreshToken && SEPARATOR !in deviceId)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.iv + cipher.doFinal("$refreshToken$SEPARATOR$deviceId".toByteArray(Charsets.UTF_8))
        prefs(context).edit().putString(FIELD, Base64.encodeToString(sealed, Base64.NO_WRAP)).commit()
    }.getOrDefault(false)

    fun load(context: Context): Saved? = runCatching {
        val sealed = Base64.decode(prefs(context).getString(FIELD, null) ?: return null, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, 12))
        }
        val plain = String(cipher.doFinal(sealed, 12, sealed.size - 12), Charsets.UTF_8)
        val parts = plain.split(SEPARATOR)
        if (parts.size != 2 || parts.any { it.isBlank() }) null else Saved(parts[0], parts[1])
    }.getOrElse { error ->
        // A key invalidated by the system (e.g. lock-screen reset) or tampered data can never be
        // opened again: start clean. Any other failure (a Keystore that is still starting right
        // after boot) is transient; wiping then would sign the driver out on every unlucky reboot.
        if (error is javax.crypto.AEADBadTagException || error is android.security.keystore.KeyPermanentlyInvalidatedException ||
            error is java.security.UnrecoverableKeyException || error is IllegalArgumentException) {
            clear(context)
            null
        } else {
            throw VaultUnavailableException(error)
        }
    }

    /** The vault exists but could not be opened right now; the caller should retry, not sign out. */
    class VaultUnavailableException(cause: Throwable) : Exception("session vault temporarily unavailable", cause)

    fun exists(context: Context): Boolean = runCatching { prefs(context).contains(FIELD) }.getOrDefault(false)

    fun clear(context: Context) {
        runCatching { prefs(context).edit().remove(FIELD).commit() }
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }
}
