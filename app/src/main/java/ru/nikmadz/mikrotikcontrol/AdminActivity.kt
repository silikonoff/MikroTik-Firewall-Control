package ru.nikmadz.mikrotikcontrol

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ScrollView
import ru.nikmadz.mikrotikcontrol.security.AdminAccess
import ru.nikmadz.mikrotikcontrol.security.PinCheck
import ru.nikmadz.mikrotikcontrol.security.PinCredentials
import java.util.concurrent.Executors

/** All administrative Activities must gate their content, including restored back-stack entries. */
abstract class AdminActivity : Activity() {
    protected val pinWorker = Executors.newSingleThreadExecutor()
    @Volatile protected var adminResumed = false
        private set
    private var prepared = false
    private var gateGeneration = 0
    private val adminDialogs = mutableListOf<AlertDialog>()

    final override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (AdminAccess.session.unlocked) prepare() else renderGate()
    }

    override fun onResume() {
        super.onResume()
        adminResumed = true
        if (!AdminAccess.session.unlocked) renderGate()
        else { if (!prepared) prepare(); onAdminResume() }
    }

    override fun onPause() {
        adminResumed = false
        adminDialogs.forEach { it.dismiss() }
        adminDialogs.clear()
        super.onPause()
    }

    override fun onDestroy() {
        pinWorker.shutdownNow()
        super.onDestroy()
    }

    private fun prepare() {
        prepared = true
        onAdminCreate()
    }

    protected abstract fun onAdminCreate()
    protected open fun onAdminResume() = Unit

    protected fun showAdminDialog(builder: AlertDialog.Builder): AlertDialog = builder.show().also { adminDialogs.add(it) }

    protected fun pinInput(): EditText = with(UiKit) {
        input("4 цифры").apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(4))
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
    }

    private fun renderGate() = with(UiKit) {
        prepared = false
        val generation = ++gateGeneration
        val root = vertical().apply {
            setBackgroundColor(BG); setPadding(dp(24), dp(16), dp(24), dp(24))
        }
        root.addView(brandHeader("Вход администратора"))
        columnWithMargin(root, label("Введите PIN-код", 25f, WHITE, true), 28)
        columnWithMargin(root, label("Настройки и выключение правил доступны администратору. Оператор может включать правила без PIN.", 16f, MUTED), 10)
        val pin = pinInput().apply { contentDescription = "PIN администратора, 4 цифры" }
        columnWithMargin(root, pin, 24)
        val message = label("", 14f, RED).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        columnWithMargin(root, message, 10)
        val enter = button("Войти", BLUE)
        columnWithMargin(root, enter, 16)
        val back = button("← Рабочий экран", CARD)
        back.setOnClickListener { leaveAdmin() }
        columnWithMargin(root, back, 12)
        setContentView(ScrollView(this@AdminActivity).apply { isFillViewport = true; addView(root) })
        enter.setOnClickListener {
            val entered = pin.text.toString()
            if (!PinCredentials.valid(entered)) { message.text = "Введите ровно 4 цифры"; return@setOnClickListener }
            pin.text.clear()
            enter.isEnabled = false
            message.text = "Проверяем PIN…"
            pinWorker.execute {
                val result = runCatching { AdminAccess.pins.verify(entered) }
                runOnUiThread {
                    if (isFinishing || isDestroyed || !adminResumed || generation != gateGeneration) return@runOnUiThread
                    enter.isEnabled = true
                    when (result.getOrNull()) {
                        PinCheck.ACCEPTED -> {
                            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(pin.windowToken, 0)
                            AdminAccess.session.unlock()
                            prepare()
                            onAdminResume()
                        }
                        PinCheck.WRONG -> message.text = "Неверный PIN-код"
                        PinCheck.WAIT -> message.text = "Слишком много попыток. Подождите 30 секунд."
                        null -> message.text = "Не удалось проверить сохранённый PIN. Доступ закрыт; настройки не сбрасывались."
                    }
                }
            }
        }
    }

    protected fun leaveAdmin() {
        AdminAccess.session.lock()
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }

    @Deprecated("Platform back callback for the existing Activity architecture")
    override fun onBackPressed() {
        if (!AdminAccess.session.unlocked) leaveAdmin() else super.onBackPressed()
    }
}
