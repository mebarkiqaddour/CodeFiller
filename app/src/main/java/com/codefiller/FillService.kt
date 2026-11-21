package com.codefiller

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
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
    var delayMs = 4000L
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

    private fun hasText(n: AccessibilityNodeInfo, s: String) =
        (n.text?.contains(s) == true) || (n.contentDescription?.contains(s) == true)

    private fun tapAt(n: AccessibilityNodeInfo) {
        val r = Rect()
        n.getBoundsInScreen(r)
        val path = Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()
        dispatchGesture(g, null, null)
    }

    private fun clickNode(n: AccessibilityNodeInfo) {
        var cur: AccessibilityNodeInfo? = n
        while (cur != null) {
            if (cur.isClickable && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            cur = cur.parent
        }
        tapAt(n)
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

            // شاشة النجاح: اضغط "أضف رمزا جديدا"
            val again = find(root) { hasText(it, "أضف رمز") }
            if (again != null) {
                clickNode(again)
                State.status = "فتحت حقلًا جديدًا"
                h.postDelayed(this, 1800); return
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
                val btn = find(rootInActiveWindow) { hasText(it, "تحقق من الرمز") }
                if (btn != null) {
                    clickNode(btn)
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
