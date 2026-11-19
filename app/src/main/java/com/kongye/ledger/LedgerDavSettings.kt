package com.kongye.ledger

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

internal class LedgerDavSettings(context: Context) {
    private val preferences = context.getSharedPreferences("ledger_webdav", Context.MODE_PRIVATE)

    fun publicConfig(): JSONObject {
        val values = preferences.all
        return JSONObject()
            .put("address", values["address"] as? String ?: "")
            .put("user", values["user"] as? String ?: "")
            .put("hasPassword", values["encryptedPassword"] is String)
    }

    fun account(address: String, user: String, password: String, reusePassword: Boolean): LedgerDavAccount {
        val account = LedgerDavAccount(address, user.trim(), password)
        if (!reusePassword) return account
        val values = preferences.all
        require(values["address"] == account.directory.toString() && values["user"] == account.user) {
            "服务器或用户名已更改，请输入密码"
        }
        val saved = runCatching {
            val encrypted = Base64.decode(requireNotNull(values["encryptedPassword"] as? String), Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                requireNotNull(keyStore().getKey(KEY_ALIAS, null)),
                GCMParameterSpec(128, encrypted, 0, IV_BYTES)
            )
            cipher.updateAAD(account.identity())
            cipher.doFinal(encrypted, IV_BYTES, encrypted.size - IV_BYTES).toString(Charsets.UTF_8)
        }.getOrNull() ?: throw IllegalArgumentException("无法读取已保存的密码，请重新输入并测试连接")
        return LedgerDavAccount(account.directory.toString(), account.user, saved)
    }

    fun save(account: LedgerDavAccount): Boolean = runCatching {
        val editor = preferences.edit()
            .putString("address", account.directory.toString())
            .putString("user", account.user)
        if (account.password.isEmpty()) {
            editor.remove("encryptedPassword")
        } else {
            val key = keyStore().getKey(KEY_ALIAS, null) as? SecretKey ?: generateKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            check(cipher.iv.size == IV_BYTES)
            cipher.updateAAD(account.identity())
            val encrypted = cipher.doFinal(account.password.toByteArray(Charsets.UTF_8))
            editor.putString("encryptedPassword", Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP))
        }
        editor.commit()
    }.getOrDefault(false)

    private fun LedgerDavAccount.identity(): ByteArray =
        JSONArray().put(directory.toString()).put(user).toString().toByteArray(Charsets.UTF_8)

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun generateKey(): SecretKey = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
        init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build())
        generateKey()
    }

    private companion object {
        const val KEY_ALIAS = "kongye-ledger-webdav"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}
