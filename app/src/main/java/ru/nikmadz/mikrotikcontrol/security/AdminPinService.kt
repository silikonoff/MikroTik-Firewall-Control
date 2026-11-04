package ru.nikmadz.mikrotikcontrol.security

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Platform-free credential format. Never put entered PINs in messages or logs. */
object PinCredentials {
    private const val ITERATIONS = 120_000
    fun valid(pin: String): Boolean = pin.length == 4 && pin.all { it in '0'..'9' }

    private fun derive(pin: String, salt: ByteArray): ByteArray {
        val chars = pin.toCharArray()
        val spec = PBEKeySpec(chars, salt, ITERATIONS, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword(); chars.fill('\u0000') }
    }

    fun create(pin: String): String {
        require(valid(pin)) { "PIN должен содержать ровно 4 цифры" }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val encode = Base64.getEncoder()
        return "1:$ITERATIONS:${encode.encodeToString(salt)}:${encode.encodeToString(derive(pin, salt))}"
    }

    fun matches(record: String, pin: String): Boolean {
        require(record.length <= 128) { "Повреждена настройка PIN" }
        val parts = record.split(':')
        require(parts.size == 4 && parts[0] == "1" && parts[1] == ITERATIONS.toString()) { "Повреждена настройка PIN" }
        val salt: ByteArray
        val expected: ByteArray
        try {
            salt = Base64.getDecoder().decode(parts[2])
            expected = Base64.getDecoder().decode(parts[3])
        } catch (_: IllegalArgumentException) { throw IllegalStateException("Повреждена настройка PIN") }
        require(salt.size == 16 && expected.size == 32) { "Повреждена настройка PIN" }
        return valid(pin) && MessageDigest.isEqual(expected, derive(pin, salt))
    }
}

interface PinRecordStore {
    /** Null means never configured, not a read/decode failure. */
    fun read(): String?
    fun write(record: String)
}

enum class PinCheck { ACCEPTED, WRONG, WAIT }

/** One application-scoped service; synchronized to serialize verify/change operations. */
class AdminPinService(private val store: PinRecordStore, private val clock: () -> Long) {
    private var failures = 0
    private var blockedUntil = 0L

    private fun record(): String = store.read() ?: PinCredentials.create("0000").also { store.write(it) }

    @Synchronized fun verify(pin: String): PinCheck {
        if (clock() < blockedUntil) return PinCheck.WAIT
        if (blockedUntil != 0L) { failures = 0; blockedUntil = 0 }
        require(PinCredentials.valid(pin)) { "Введите 4 цифры PIN" }
        if (PinCredentials.matches(record(), pin)) {
            failures = 0
            return PinCheck.ACCEPTED
        }
        failures++
        if (failures >= 5) {
            blockedUntil = clock() + 30_000L
            return PinCheck.WAIT
        }
        return PinCheck.WRONG
    }

    @Synchronized fun change(current: String, newPin: String, confirmation: String): PinCheck {
        require(PinCredentials.valid(newPin)) { "Новый PIN должен содержать ровно 4 цифры" }
        require(newPin == confirmation) { "Новый PIN и повтор не совпадают" }
        val result = verify(current)
        if (result == PinCheck.ACCEPTED) store.write(PinCredentials.create(newPin))
        return result
    }
}

/** Process-only access; no saved-state/Intent grant that can outlive a process. */
class AdminSessionState {
    @Volatile
    var unlocked: Boolean = false
        private set
    private var started = 0
    fun unlock() { unlocked = true }
    fun lock() { unlocked = false }
    fun activityStarted() { started++ }
    fun activityStopped(changingConfigurations: Boolean) {
        if (started > 0) started--
        if (started == 0 && !changingConfigurations) lock()
    }
}
