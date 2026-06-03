package pt.cuco.scanner

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import pt.cuco.scanner.databinding.ActivityWebviewBinding

class WebViewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWebviewBinding

    private var currentSerial = ""
    private var currentCtime = ""
    private var currentUsage = ""

    private var currentHistoryId = -1L
    private var currentHistorySubmitted = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWebviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        currentSerial = intent.getStringExtra(EXTRA_SERIAL).orEmpty()
        currentCtime = intent.getStringExtra(EXTRA_CTIME).orEmpty()
        currentUsage = intent.getStringExtra(EXTRA_USAGE).orEmpty()

        recordHistory()

        binding.webview.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
        }

        binding.webview.addJavascriptInterface(CucoInterface(), "CUCO")

        binding.webview.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                Log.d(TAG, "onPageStarted url=$url")
            }

            override fun onPageFinished(view: WebView, url: String?) {
                Log.d(TAG, "onPageFinished url=$url")
                evaluate(view)
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                Log.d(TAG, "doUpdateVisitedHistory url=$url reload=$isReload")
                evaluate(view)
            }
        }

        binding.btnBack.setOnClickListener { navigateToMain() }
        binding.btnHistory.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }

        binding.webview.loadUrl(CUCO_URL)
    }

    private fun evaluate(view: WebView) {
        val js = FillFormJs.build(currentSerial, currentCtime, currentUsage)
        view.evaluateJavascript(js) { result ->
            Log.d(TAG, "fill result=$result")
        }
    }

    private fun recordHistory() {
        val now = System.currentTimeMillis()
        HistoryRepository.addEntry(
            this,
            HistoryEntry(
                id = now,
                serial = currentSerial,
                ctime = currentCtime,
                usage = currentUsage,
                timestamp = now,
                status = HistoryEntry.STATUS_PENDING,
            ),
        )
        currentHistoryId = now
        currentHistorySubmitted = false
    }

    private fun navigateToMain() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        finish()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (binding.webview.canGoBack()) binding.webview.goBack()
        else navigateToMain()
    }

    inner class CucoInterface {
        @JavascriptInterface
        fun onFieldChanged(field: String, value: String) {
            Log.d(TAG, "onFieldChanged field=$field value=$value")
            runOnUiThread {
                val changed = when (field) {
                    "serial" -> (value != currentSerial).also { currentSerial = value }
                    "ctime" -> (value != currentCtime).also { currentCtime = value }
                    "usage" -> (value != currentUsage).also { currentUsage = value }
                    else -> false
                }
                if (changed) {
                    recordHistory()
                }
            }
        }

        @JavascriptInterface
        fun onSubmissionSuccess() {
            Log.d(TAG, "onSubmissionSuccess")
            runOnUiThread {
                if (currentHistorySubmitted) return@runOnUiThread
                HistoryRepository.updateStatus(
                    this@WebViewActivity,
                    currentHistoryId,
                    HistoryEntry.STATUS_SUBMITTED,
                )
                currentHistorySubmitted = true
            }
        }
    }

    companion object {
        private const val TAG = "WebViewActivity"
        const val EXTRA_SERIAL = "serial"
        const val EXTRA_CTIME = "ctime"
        const val EXTRA_USAGE = "usage"
        private const val CUCO_URL = "https://cuco.inforlandia.pt/ucode/"
    }
}
