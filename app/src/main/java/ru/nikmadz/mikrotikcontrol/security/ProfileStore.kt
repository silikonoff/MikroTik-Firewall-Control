package ru.nikmadz.mikrotikcontrol.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import ru.nikmadz.mikrotikcontrol.core.RouterProfile
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** No passwords in plaintext SharedPreferences or Android backup. */
class ProfileStore(context: Context) {
    private val prefs = context.getSharedPreferences("profiles_secured_v1", Context.MODE_PRIVATE)
    private val alias = "mikrotik_firewall_profiles_v1"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(alias, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val params = KeyGenParameterSpec.Builder(
            alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        generator.init(params)
        return generator.generateKey()
    }

    private fun encrypt(text: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(blob: String): String {
        val data = Base64.decode(blob, Base64.NO_WRAP)
        require(data.size >= 12 + 16) { "Corrupted encrypted preferences" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
        return cipher.doFinal(data.copyOfRange(12, data.size)).toString(Charsets.UTF_8)
    }

    @Synchronized fun load(): List<RouterProfile> {
        val saved = prefs.getString("encrypted", null) ?: return emptyList()
        return ProfileJson.decode(decrypt(saved))
    }

    @Synchronized fun upsert(profile: RouterProfile) {
        val profiles = load().toMutableList()
        profiles.removeAll { it.id == profile.id }
        profiles.add(profile)
        save(profiles)
    }

    @Synchronized fun remove(id: String) = save(load().filterNot { it.id == id })

    @Synchronized fun selectedProfileId(): String? {
        val profiles = load()
        val selected = prefs.getString("selected_profile", null)
        return profiles.firstOrNull { it.id == selected }?.id ?: profiles.firstOrNull()?.id
    }

    @Synchronized fun selectProfile(id: String) {
        require(load().any { it.id == id }) { "Подключение не найдено" }
        check(prefs.edit().putString("selected_profile", id).commit()) { "Не удалось выбрать подключение" }
    }

    @Synchronized private fun save(profiles: List<RouterProfile>) {
        check(prefs.edit().putString("encrypted", encrypt(ProfileJson.encode(profiles))).commit()) {
            "Unable to persist encrypted settings"
        }
    }
}
