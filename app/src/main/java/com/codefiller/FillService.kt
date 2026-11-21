package com.codefiller

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

object State {
    var codes = ArrayDeque<String>()
    var total = 0
    var done = 0
    var running = false
    var delayMs = 5000L
    var status = "جاهز"
}

class FillService : AccessibilityService() {
    private val h = Handler(Looper.getMainLooper())

    companion object { var instance: FillService? = null }

    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onDestroy() { instance = null; super.onDestroy() }

    fun start() {
        if (State.running) return
        State.running = true
        h.post(step)
    }

    fun stop() {
        State.running = false
        State.status = "متوقف"
        h.removeCallbacksAndMessages(null)
    }

    private fun find(n: AccessibilityNodeInfo?, p: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (n == null) return null
        if (p(n)) return n
        for (i in 0 until n.childCount) {
            find(n.getChild(i), p)?.let { return it }
        }
        return null
    }

    private fun clickNode(n: AccessibilityNodeInfo): Boolean {
        var cur: AccessibilityNodeInfo? = n
        while (cur != null) {
            if (cur.isClickable && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            cur = cur.parent
        }
        return false
    }

    private val step = object : Runnable {
        override fun run() {
            if (!State.running) return
            val code = State.codes.firstOrNull()
            if (code == null) {
                State.running = false
                State.status = "انتهت القائمة (${State.done}/${State.total})"
                return
            }
            val root = rootInActiveWindow
            if (root == null || root.packageName == packageName) {
                State.status = "افتح شاشة المفاتيح في التطبيق"
                h.postDelayed(this, 2000); return
            }
            val field = find(root) { it.isEditable || it.className?.contains("EditText") == true }
            if (field == null) {
                State.status = "لم أجد حقل الرمز، افتح شاشة المفاتيح"
                h.postDelayed(this, 2000); return
            }
            field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, code)
            }
            field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            State.status = "أدخلت: $code"
            h.postDelayed({
                if (!State.running) return@postDelayed
                val r2 = rootInActiveWindow
                val btn = find(r2) {
                    (it.text?.contains("تحقق من الرمز") == true) ||
                    (it.contentDescription?.contains("تحقق من الرمز") == true)
                }
                if (btn != null && clickNode(btn)) {
                    State.codes.removeFirst()
                    State.done++
                    State.status = "تم ${State.done}/${State.total}"
                    h.postDelayed(this, State.delayMs)
                } else {
                    State.status = "لم أجد زر التحقق، سأعيد المحاولة"
                    h.postDelayed(this, 2000)
                }
            }, 900)
        }
    }
}
