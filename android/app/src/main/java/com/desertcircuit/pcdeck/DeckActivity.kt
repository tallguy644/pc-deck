package com.desertcircuit.pcdeck

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.button.MaterialButton

class DeckActivity : AppCompatActivity() {

    private lateinit var conn: Connection
    private lateinit var webView: WebView
    private lateinit var errorView: View
    private var mainFrameFailed = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        conn = Connection.load(this) ?: run { backToConnect(); return }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        goImmersive()

        val root = FrameLayout(this).apply { setBackgroundColor(ContextCompat.getColor(context, R.color.bg)) }
        webView = WebView(this).apply {
            setBackgroundColor(ContextCompat.getColor(context, R.color.bg))
            overScrollMode = View.OVER_SCROLL_NEVER
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                cacheMode = WebSettings.LOAD_NO_CACHE
                mediaPlaybackRequiresUserGesture = false
                setSupportZoom(false)
                builtInZoomControls = false
                displayZoomControls = false
                userAgentString = "$userAgentString PCDeckApp/${BuildConfig.VERSION_NAME}"
            }
            addJavascriptInterface(Bridge(), "DeckApp")
            webChromeClient = WebChromeClient() // enables alert/confirm/prompt dialogs
            webViewClient = DeckClient()
        }
        errorView = buildErrorView()
        root.addView(webView, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(errorView, FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = confirmLeave()
        })

        if (savedInstanceState != null) webView.restoreState(savedInstanceState) else load()
    }

    private fun load() {
        mainFrameFailed = false
        errorView.visibility = View.GONE
        webView.loadUrl(conn.deckUrl)
    }

    private fun goImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goImmersive()
    }

    override fun onResume() { super.onResume(); webView.onResume() }
    override fun onPause() { webView.onPause(); super.onPause() }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::webView.isInitialized) webView.saveState(outState)
    }
    override fun onDestroy() {
        if (::webView.isInitialized) webView.destroy()
        super.onDestroy()
    }

    private fun confirmLeave() {
        AlertDialog.Builder(this)
            .setTitle(R.string.leave_title)
            .setPositiveButton(R.string.leave) { _, _ -> backToConnect() }
            .setNegativeButton(R.string.stay, null)
            .show()
    }

    private fun backToConnect() {
        startActivity(ConnectActivity.chooseIntent(this))
        finish()
    }

    private fun buildErrorView(): View {
        val dp = resources.displayMetrics.density
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding((32 * dp).toInt(), 0, (32 * dp).toInt(), 0)
            setBackgroundColor(ContextCompat.getColor(context, R.color.bg))
            visibility = View.GONE
            isClickable = true
            addView(TextView(context).apply {
                setText(R.string.cant_reach)
                setTextColor(ContextCompat.getColor(context, R.color.text))
                textSize = 22f
                gravity = Gravity.CENTER
            })
            addView(TextView(context).apply {
                setText(R.string.cant_reach_detail)
                setTextColor(ContextCompat.getColor(context, R.color.muted))
                textSize = 15f
                gravity = Gravity.CENTER
                setPadding(0, (8 * dp).toInt(), 0, (24 * dp).toInt())
            })
            addView(MaterialButton(context).apply {
                setText(R.string.retry)
                setOnClickListener { load() }
            }, LinearLayout.LayoutParams((220 * dp).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                setText(R.string.change_pc)
                setOnClickListener { backToConnect() }
            }, LinearLayout.LayoutParams((220 * dp).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private inner class DeckClient : WebViewClient() {
        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) {
                mainFrameFailed = true
                errorView.visibility = View.VISIBLE
            }
        }

        override fun onPageFinished(view: WebView, url: String?) {
            if (!mainFrameFailed) errorView.visibility = View.GONE
        }

        // Keep the deck inside the app; anything else opens in the browser.
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url
            if (url.host == conn.host && (url.port == conn.port)) return false
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, url)) }
            return true
        }
    }

    /** Exposed to the web UI as window.DeckApp */
    private inner class Bridge {
        @JavascriptInterface
        fun vibrate(ms: Int) {
            val duration = ms.coerceIn(1, 200).toLong()
            val vib: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
                (getSystemService(VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION") getSystemService(VIBRATOR_SERVICE) as? Vibrator
            }
            if (vib?.hasVibrator() != true) return
            if (Build.VERSION.SDK_INT >= 26) {
                vib.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION") vib.vibrate(duration)
            }
        }

        @JavascriptInterface
        fun changePc() = runOnUiThread { backToConnect() }

        @JavascriptInterface
        fun tokenRejected() = runOnUiThread {
            Toast.makeText(this@DeckActivity, "Token rejected — scan the QR code again", Toast.LENGTH_LONG).show()
            backToConnect()
        }

        @JavascriptInterface
        fun version(): String = BuildConfig.VERSION_NAME
    }

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    }
}
