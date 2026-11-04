package ru.nikmadz.mikrotikcontrol

import android.widget.ScrollView
import android.widget.Toast
import ru.nikmadz.mikrotikcontrol.security.AdminAccess
import ru.nikmadz.mikrotikcontrol.security.PinCheck
import ru.nikmadz.mikrotikcontrol.security.PinCredentials

class ChangePinActivity : AdminActivity() {
    override fun onAdminCreate() = with(UiKit) {
        val root = vertical().apply { setBackgroundColor(BG); setPadding(dp(24), dp(20), dp(24), dp(24)) }
        root.addView(brandHeader("Настройки администратора"))
        columnWithMargin(root, label("Изменить PIN-код", 24f, WHITE, true), 24)
        fun field(title: String) = pinInput().also {
            columnWithMargin(root, label(title, 15f, MUTED), 20)
            it.contentDescription = title
            columnWithMargin(root, it, 8)
        }
        val current = field("Текущий PIN")
        val newPin = field("Новый PIN: 4 цифры")
        val repeat = field("Повторите новый PIN")
        val message = label("", 14f, RED)
        columnWithMargin(root, message, 12)
        val save = button("Сохранить новый PIN", BLUE)
        columnWithMargin(root, save, 16)
        val back = button("Отмена", CARD)
        back.setOnClickListener { finish() }
        columnWithMargin(root, back, 12)
        setContentView(ScrollView(this@ChangePinActivity).apply { isFillViewport = true; addView(root) })
        save.setOnClickListener {
            val old = current.text.toString()
            val next = newPin.text.toString()
            val confirmation = repeat.text.toString()
            if (!PinCredentials.valid(old) || !PinCredentials.valid(next)) {
                message.text = "Каждый PIN должен содержать 4 цифры"; return@setOnClickListener
            }
            if (next != confirmation) { message.text = "Новый PIN и повтор не совпадают"; return@setOnClickListener }
            current.text.clear(); newPin.text.clear(); repeat.text.clear()
            save.isEnabled = false
            back.isEnabled = false
            message.text = "Сохраняем PIN…"
            pinWorker.execute {
                val result = runCatching { AdminAccess.pins.change(old, next, confirmation) }
                runOnUiThread {
                    if (isFinishing || isDestroyed || !adminResumed) return@runOnUiThread
                    save.isEnabled = true
                    back.isEnabled = true
                    when (result.getOrNull()) {
                        PinCheck.ACCEPTED -> {
                            Toast.makeText(this@ChangePinActivity, "PIN-код изменён", Toast.LENGTH_LONG).show()
                            finish()
                        }
                        PinCheck.WRONG -> message.text = "Текущий PIN неверен"
                        PinCheck.WAIT -> message.text = "Слишком много попыток. Подождите 30 секунд."
                        null -> message.text = "Не удалось сохранить PIN. Проверьте доступ с введённым новым PIN перед дальнейшей работой."
                    }
                }
            }
        }
    }
}
