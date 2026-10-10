package io.translatetube.local.translate

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** API Key 加密：AndroidKeyStore AES-256/GCM，密钥不出安全区 */
object Crypto {
    private const val KS = "AndroidKeyStore"
    private const val ALIAS = "tt_api_keys"
    private val magic = "TTENC1:".toByteArray()

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KS).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KS)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val iv = c.iv
        val data = c.doFinal(plain.toByteArray())
        return "TTENC1:" + java.util.Base64.getEncoder().encodeToString(iv + data)
    }

    fun decrypt(stored: String): String {
        if (!stored.startsWith("TTENC1:")) return stored // 兼容明文
        return try {
            val raw = java.util.Base64.getDecoder().decode(stored.removePrefix("TTENC1:"))
            val iv = raw.copyOfRange(0, 12)
            val data = raw.copyOfRange(12, raw.size)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(c.doFinal(data))
        } catch (e: Exception) {
            ""
        }
    }
}
