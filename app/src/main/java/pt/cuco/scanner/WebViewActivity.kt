package pt.cuco.scanner

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
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

    // The serial currently reflected in the loaded URL (`l=`). The CUCo page
    // reads the serial from the URL, not the form, so a serial correction only
    // takes effect after we rebuild + reload the URL.
    private var urlSerial = ""

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWebviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        currentSerial = intent.getStringExtra(EXTRA_SERIAL).orEmpty()
        currentCtime = intent.getStringExtra(EXTRA_CTIME).orEmpty()
        currentUsage = intent.getStringExtra(EXTRA_USAGE).orEmpty()
        // -1 = fresh scan/import (create a new history row). >=0 = opened from
        // history (reuse that row so we don't create duplicates).
        currentHistoryId = intent.getLongExtra(EXTRA_HISTORY_ID, -1L)

        if (savedInstanceState != null) {
            currentSerial = savedInstanceState.getString(STATE_SERIAL, currentSerial)
            currentCtime = savedInstanceState.getString(STATE_CTIME, currentCtime)
            currentUsage = savedInstanceState.getString(STATE_USAGE, currentUsage)
            currentHistoryId = savedInstanceState.getLong(STATE_HISTORY_ID, currentHistoryId)
            currentHistorySubmitted = savedInstanceState.getBoolean(STATE_SUBMITTED, false)
        }

        if (currentHistoryId < 0) {
            recordHistory()
        }

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

        loadSerialUrl(currentSerial)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_SERIAL, currentSerial)
        outState.putString(STATE_CTIME, currentCtime)
        outState.putString(STATE_USAGE, currentUsage)
        outState.putLong(STATE_HISTORY_ID, currentHistoryId)
        outState.putBoolean(STATE_SUBMITTED, currentHistorySubmitted)
    }

    private fun loadSerialUrl(serial: String) {
        urlSerial = serial
        binding.webview.loadUrl(buildCucoUrl(serial))
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

    /** Persists the latest edited values into the current history row. */
    private fun persistFields() {
        if (currentHistoryId < 0) {
            recordHistory()
            return
        }
        HistoryRepository.updateFields(
            this,
            currentHistoryId,
            currentSerial,
            currentCtime,
            currentUsage,
        )
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

    /** Builds the CUCo URL from the user-editable settings, keeping the `l=` serial. */
    private fun buildCucoUrl(serial: String): String {
        val builder = Uri.parse(SettingsRepository.baseUrl(this)).buildUpon()
            .appendQueryParameter("client", SettingsRepository.client(this))
            .appendQueryParameter("lang", SettingsRepository.lang(this))
        if (serial.isNotEmpty()) {
            builder.appendQueryParameter("l", serial)
        }
        return builder.build().toString()
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
                if (!changed) return@runOnUiThread

                // Remember the edit so it survives a reload / activity recreation.
                persistFields()

                // The serial lives in the URL, so a serial correction needs a
                // reload to actually take effect on resubmit.
                if (field == "serial" && value != urlSerial && isLikelySerial(value)) {
                    loadSerialUrl(value)
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
        const val EXTRA_HISTORY_ID = "history_id"

        private const val STATE_SERIAL = "state_serial"
        private const val STATE_CTIME = "state_ctime"
        private const val STATE_USAGE = "state_usage"
        private const val STATE_HISTORY_ID = "state_history_id"
        private const val STATE_SUBMITTED = "state_submitted"

        private val serialRegex = Regex("""^[0-9A-Fa-f]{16,64}$""")

        private fun isLikelySerial(value: String): Boolean = serialRegex.matches(value)
    }
}
