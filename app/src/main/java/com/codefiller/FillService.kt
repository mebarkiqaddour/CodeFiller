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
    var ok = 0
    var running = false
    var status = "جاهز"
}

class FillService : AccessibilityService() {
    private val h = Handler(Looper.getMainLooper())

    private enum class Phase { IDLE, TYPING, WAIT_RESULT, WAIT_FIELD }
    private var phase = Phase.IDLE
    private var baseline: Set<String> = emptySet()
    private var lastAgainClick = 0L
    private var pending = false

    companion object { var instance: FillService? = null }

    override fun onServiceConnected() { instance = this }
    override fun onInterrupt() {}
    override fun onDestroy() { instance = null; super.onDestroy() }

    // أي تغيّر في الشاشة يشغّل الفحص فورًا
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (!State.running || pending) return
        pending = true
        h.post { pending = false; tick() }
    }

    private val heartbeat = object : Runnable {
        override fun run() {
            if (!State.running) return
            tick()
            h.postDelayed(this, 150)
        }
    }

    fun start() {
        if (State.running) return
        State.running = true
        State.ok = 0
        phase = Phase.IDLE
        lastAgainClick = 0L
        linkAttempts = 0
        h.post(heartbeat)
    }

    fun stop() {
        State.running = false
        State.status = "متوقف"
        phase = Phase.IDLE
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

    private fun collectTexts(n: AccessibilityNodeInfo?, out: MutableSet<String>) {
        if (n == null) return
        n.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
        n.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
        for (i in 0 until n.childCount) collectTexts(n.getChild(i), out)
    }

    private fun tapAt(n: AccessibilityNodeInfo) {
        val r = Rect()
        n.getBoundsInScreen(r)
        val path = Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 40))
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

    // يناوب بين النقر عبر العناصر القابلة للنقر (الأب) والنقر بالإحداثيات
    private var linkAttempts = 0
    private fun tapLink(n: AccessibilityNodeInfo) {
        linkAttempts++
        if (linkAttempts % 2 == 1) clickNode(n) else tapAt(n)
    }

    private val loadingWords = listOf("تحقق", "جار", "تحميل", "انتظر", "Loading")
    private fun isFailureText(s: String) =
        loadingWords.none { s.contains(it) } && !s.startsWith("NG-")

    private fun finish() {
        State.running = false
        phase = Phase.IDLE
        State.status = "انتهت القائمة: نجح ${State.ok} من ${State.done}"
    }

    private fun tick() {
        if (!State.running) return
        if (phase == Phase.TYPING) return
        if (State.codes.isEmpty()) { finish(); return }

        val root = rootInActiveWindow ?: return
        if (root.packageName == packageName) {
            State.status = "افتح شاشة المفاتيح في التطبيق"
            return
        }

        // 1) شاشة النجاح: اضغط "أضف رمزا جديدا" فورًا
        val again = find(root) { hasText(it, "أضف رمز") }
        if (again != null) {
            val now = System.currentTimeMillis()
            if (phase == Phase.WAIT_RESULT) {
                val code = State.codes.removeFirst()
                State.done++
                State.ok++
                State.status = "نجح $code (نجح ${State.ok} من ${State.done}/${State.total})"
                phase = Phase.WAIT_FIELD
                lastAgainClick = now
                linkAttempts = 0
                tapLink(again)
            } else if (now - lastAgainClick > 300) {
                phase = Phase.WAIT_FIELD
                lastAgainClick = now
                tapLink(again)
            }
            return
        }

        val field = find(root) { it.isEditable || it.className?.contains("EditText") == true }
            ?: return

        // 2) ننتظر النتيجة بلا مهلة (بطء الشبكة لا يُعتبر فشلًا)
        if (phase == Phase.WAIT_RESULT) {
            val cur = HashSet<String>()
            collectTexts(root, cur)
            val fresh = cur - baseline
            if (fresh.none { isFailureText(it) }) {
                State.status = "بانتظار النتيجة…"
                return
            }
            val code = State.codes.removeFirst()
            State.done++
            State.status = "فشل $code (نجح ${State.ok} من ${State.done}/${State.total})"
            phase = Phase.IDLE
            if (State.codes.isEmpty()) { finish(); return }
        }

        linkAttempts = 0

        // 3) أدخل الكود التالي واضغط تحقق فورًا
        val next = State.codes.first()
        phase = Phase.TYPING
        field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, next)
        }
        field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        State.status = "أدخلت: $next"
        h.postDelayed({ pressVerify(0) }, 60)
    }

    // يضغط "تحقق" بمجرد أن يصبح الزر مفعّلًا
    private fun pressVerify(attempt: Int) {
        if (!State.running) return
        val root = rootInActiveWindow
        val btn = find(root) { hasText(it, "تحقق من الرمز") }
        if (btn != null && (btn.isEnabled || attempt >= 25)) {
            val snap = HashSet<String>()
            collectTexts(root, snap)
            baseline = snap
            clickNode(btn)
            phase = Phase.WAIT_RESULT
            return
        }
        if (attempt >= 60) { phase = Phase.IDLE; return }   // أعد المحاولة من البداية
        h.postDelayed({ pressVerify(attempt + 1) }, 40)
    }
}
