package com.antonycollika.ava

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores provider credentials encrypted with a non-exportable Android Keystore key. */
class SecureKeyStore(context: Context) {
    private val prefs = context.getSharedPreferences("ava_secure_secrets", Context.MODE_PRIVATE)
    private val alias = "ava_api_key_aes_gcm_v1"

    fun hasKey(scope: String = "default"): Boolean =
        prefs.contains(ciphertextName(scope)) || (scope == "OPENAI" && prefs.contains("ciphertext"))

    fun save(value: String, scope: String = "default") {
        require(value.isNotBlank()) { "API key cannot be empty" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString(ivName(scope), Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(ciphertextName(scope), Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .apply()
    }

    fun read(scope: String = "default"): String? = runCatching {
        val storedScope = if (scope == "OPENAI" && !prefs.contains(ciphertextName(scope))) "default" else scope
        val legacyIv = if (scope == "OPENAI") prefs.getString("iv", null) else null
        val legacyCiphertext = if (scope == "OPENAI") prefs.getString("ciphertext", null) else null
        val iv = Base64.decode(prefs.getString(ivName(storedScope), legacyIv), Base64.NO_WRAP)
        val encrypted = Base64.decode(prefs.getString(ciphertextName(storedScope), legacyCiphertext), Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }.getOrNull()

    fun clear(scope: String = "default") {
        prefs.edit().remove(ivName(scope)).remove(ciphertextName(scope)).apply()
        if (scope == "OPENAI") prefs.edit().remove("iv").remove("ciphertext").apply()
    }

    fun clearAll() {
        prefs.edit().clear().apply()
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias) }
    }

    private fun ivName(scope: String) = "iv_$scope"
    private fun ciphertextName(scope: String) = "ciphertext_$scope"

    private fun getOrCreateKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}
