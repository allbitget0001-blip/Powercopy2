package com.example.powerlocks

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*

class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var countdown: TextView
    private lateinit var accStatus: TextView
    private lateinit var startBtn: Button
    private lateinit var input: EditText

    private val ticker = object : Runnable {
        override fun run() {
            updateUi()
            handler.postDelayed(this, 1000)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun styledButton(label: String, color: Int): Button = Button(this).apply {
        text = label
        setTextColor(Color.WHITE)
        isAllCaps = false
        textSize = 16f
        background = GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(14).toFloat()
        }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(56)
        ).apply { topMargin = dp(12) }
    }

    private fun isAccessibilityOn(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.contains("$packageName/")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#121212"))
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }

        val title = TextView(this).apply {
            text = "PowerLock"
            textSize = 28f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }

        countdown = TextView(this).apply {
            text = "00:00:00"
            textSize = 52f
            setTextColor(Color.parseColor("#64B5F6"))
            gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, dp(24))
        }

        accStatus = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.parseColor("#BDBDBD"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(16))
        }

        input = EditText(this).apply {
            hint = "কত মিনিট? (সর্বোচ্চ ১৪৪০)"
            setHintTextColor(Color.parseColor("#757575"))
            setTextColor(Color.WHITE)
            inputType = InputType.TYPE_CLASS_NUMBER
            gravity = Gravity.CENTER
        }

        startBtn = styledButton("লক শুরু করো", Color.parseColor("#1E88E5"))
        startBtn.setOnClickListener {
            val mins = input.text.toString().toLongOrNull()
            when {
                mins == null || mins <= 0 ->
                    Toast.makeText(this, "মিনিট লিখুন", Toast.LENGTH_SHORT).show()
                mins > 1440 ->
                    Toast.makeText(this, "সর্বোচ্চ ১৪৪০ মিনিট", Toast.LENGTH_SHORT).show()
                !isAccessibilityOn() ->
                    Toast.makeText(this, "আগে Accessibility চালু করুন", Toast.LENGTH_LONG).show()
                else -> {
                    getSharedPreferences("lock", MODE_PRIVATE).edit()
                        .putLong("end", System.currentTimeMillis() + mins * 60_000L)
                        .apply()
                    try {
                        startForegroundService(Intent(this, KeepAliveService::class.java))
                    } catch (_: Exception) {
                    }
                    input.text.clear()
                    updateUi()
                }
            }
        }

        val accBtn = styledButton("Accessibility সেটিংস খুলুন", Color.parseColor("#424242"))
        accBtn.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        root.addView(title)
        root.addView(countdown)
        root.addView(accStatus)
        root.addView(input)
        root.addView(startBtn)
        root.addView(accBtn)
        setContentView(root)
    }

    private fun updateUi() {
        val end = getSharedPreferences("lock", MODE_PRIVATE).getLong("end", 0)
        val left = end - System.currentTimeMillis()
        if (left > 0) {
            val s = left / 1000
            countdown.text = String.format("%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
            startBtn.isEnabled = false
            startBtn.alpha = 0.4f
        } else {
            countdown.text = "00:00:00"
            startBtn.isEnabled = true
            startBtn.alpha = 1f
        }
        accStatus.text =
            if (isAccessibilityOn()) "Accessibility: চালু ✔" else "Accessibility: বন্ধ ✘"
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }
}
