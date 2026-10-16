package ru.hznik.fakebank

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * «Фейковый банк»: публикует уведомление от имени пакета банка. Готовые тексты вымышленные, в форматах из тестов
 * парсеров BudgetPlanner. Для автоматических проверок текст можно передать при запуске:
 * `adb shell am start -n <пакет>/ru.hznik.fakebank.MainActivity --es text "…"` — уведомление публикуется сразу.
 */
class MainActivity : Activity() {
    private lateinit var input: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.bank_name), NotificationManager.IMPORTANCE_DEFAULT))

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }
        column.addView(TextView(this).apply { text = "${getString(R.string.bank_name)} — фейковые уведомления (только эмулятор)"; textSize = 18f })
        input = EditText(this).apply { hint = "Текст уведомления"; minLines = 3 }
        column.addView(input, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        column.addView(Button(this).apply { text = "Отправить уведомление"; setOnClickListener { post(input.text.toString()) } })
        presets(getString(R.string.bank_id)).forEach { preset ->
            column.addView(Button(this).apply { text = preset; isAllCaps = false; setOnClickListener { input.setText(preset) } })
        }
        setContentView(ScrollView(this).apply { addView(column) })
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent) {
        val text = intent.getStringExtra(EXTRA_TEXT) ?: return
        post(text, intent.getStringExtra(EXTRA_TITLE))
        intent.removeExtra(EXTRA_TEXT)
    }

    private fun post(text: String, title: String? = null) {
        if (text.isBlank()) return
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle(title ?: getString(R.string.bank_name))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .build()
        getSystemService(NotificationManager::class.java).notify(nextId++, notification)
    }

    private fun presets(bank: String): List<String> = when (bank) {
        "sber" -> listOf(
            "Покупка 450 ₽ PYATEROCHKA. Карта *5678. Баланс 12 300 ₽",
            "Перевод 3 000 ₽ на Альфа-Банк. Карта *5678. Баланс 9 300 ₽",
            "Зачисление 9 000 ₽. Карта *5678. Баланс 18 300 ₽",
            "Покупка 120 ₽ Пекарня. Карта *5678",
        )
        "tbank" -> listOf(
            "Покупка, карта *4417. 450 ₽. PYATEROCHKA. Доступно 12 300 ₽",
            "Пополнение, карта *4417. 9 000 ₽. Доступно 21 300 ₽",
        )
        else -> listOf(
            "Покупка в OZON, карта *1234. На сумму 450,00 RUR. Доступно 4 410,00 RUR",
            "Перевод на сумму 3 000.00 RUR из Сбербанк от ИВАН П. по СБП.",
            "Покупка в OZON, карта *1234. На сумму 300,00 RUR",
        )
    }

    private companion object {
        const val CHANNEL = "operations"
        const val EXTRA_TEXT = "text"
        const val EXTRA_TITLE = "title"
        var nextId = 1
    }
}
