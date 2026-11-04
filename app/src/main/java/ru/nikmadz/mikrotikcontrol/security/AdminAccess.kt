package ru.nikmadz.mikrotikcontrol.security

import android.content.Context
import android.os.SystemClock
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException

object AdminAccess {
    val session = AdminSessionState()
    lateinit var pins: AdminPinService
        private set

    fun initialize(context: Context) {
        val file = AtomicFile(File(context.filesDir, "admin_pin_v1"))
        pins = AdminPinService(object : PinRecordStore {
            override fun read(): String? {
                try {
                    return file.openRead().use { input ->
                        val bytes = ByteArray(129)
                        var count = 0
                        while (count < bytes.size) {
                            val n = input.read(bytes, count, bytes.size - count)
                            if (n < 0) break
                            count += n
                        }
                        check(count in 1..128) { "Повреждена настройка PIN" }
                        String(bytes, 0, count, Charsets.UTF_8)
                    }
                } catch (e: FileNotFoundException) {
                    // Do not turn inaccessible/corrupt/partially written credentials into the default.
                    if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists() ||
                        File(file.baseFile.path + ".new").exists()) throw e
                    return null
                }
            }

            override fun write(record: String) {
                val output = file.startWrite()
                try {
                    output.write(record.toByteArray(Charsets.UTF_8))
                    file.finishWrite(output)
                } catch (e: Exception) {
                    file.failWrite(output)
                    throw e
                }
                check(read() == record) { "Не удалось сохранить PIN" }
            }
        }, SystemClock::elapsedRealtime)
    }
}
