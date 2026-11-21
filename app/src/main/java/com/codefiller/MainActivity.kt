package com.codefiller

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.widget.*

class MainActivity : Activity() {
    private val h = Handler(Looper.getMainLooper())

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val input = EditText(this).apply {
            hint = "الصق التعليقات أو الأكواد هنا (يُستخرج NG-xxxxxxxx تلقائيًا)"
            minLines = 8
            gravity = Gravity.TOP
        }
        val status = TextView(this).apply { textSize = 16f }

        fun btn(t: String, f: () -> Unit) = Button(this).apply {
            text = t
            setOnClickListener { f() }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 64, 32, 32)
            addView(btn("1) تفعيل خدمة الوصول (مرة واحدة)") {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            })
            addView(input, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(btn("2) ابدأ الإدخال") {
                val codes = Regex("NG-[A-Za-z0-9]{8}").findAll(input.text).map { it.value }.distinct().toList()
                val svc = FillService.instance
                when {
                    codes.isEmpty() -> Toast.makeText(this@MainActivity, "لا توجد أكواد", Toast.LENGTH_SHORT).show()
                    svc == null -> Toast.makeText(this@MainActivity, "فعّل الخدمة أولًا", Toast.LENGTH_LONG).show()
                    else -> {
                        State.codes = ArrayDeque(codes)
                        State.total = codes.size
                        State.done = 0
                        svc.start()
                        moveTaskToBack(true)
                    }
                }
            })
            addView(btn("إيقاف") { FillService.instance?.stop() })
            addView(status)
        }
        setContentView(root)

        h.post(object : Runnable {
            override fun run() {
                status.text = State.status
                h.postDelayed(this, 1000)
            }
        })
    }
}
