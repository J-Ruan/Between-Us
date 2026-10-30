package cn.shiyan.betweenus

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object DbKey {
    @Synchronized
    fun load(context: Context): ByteArray {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val alias = "shiyan-database"
        val key =
            (store.getKey(alias, null) as? SecretKey)
                ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                    .run {
                        init(
                            KeyGenParameterSpec.Builder(
                                    alias,
                                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                                )
                                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                                .build()
                        )
                        generateKey()
                    }
        val file = File(context.noBackupFilesDir, "database-key")
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        if (file.exists()) {
            val data = file.readBytes()
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, data.copyOfRange(0, 12)))
            return c.doFinal(data.copyOfRange(12, data.size))
        }
        val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }
        c.init(Cipher.ENCRYPT_MODE, key)
        file.writeBytes(c.iv + c.doFinal(raw))
        return raw
    }
}
