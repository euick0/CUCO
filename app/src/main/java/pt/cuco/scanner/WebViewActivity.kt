package pt.cuco.scanner

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import pt.cuco.scanner.databinding.ActivityWebviewBinding

class WebViewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWebviewBinding
    private lateinit var fillJs: String

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWebviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val serial = intent.getStringExtra(EXTRA_SERIAL).orEmpty()
        val ctime = intent.getStringExtra(EXTRA_CTIME).orEmpty()
        val usage = intent.getStringExtra(EXTRA_USAGE).orEmpty()

        fillJs = FillFormJs.build(serial, ctime, usage)

        binding.webview.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
        }

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

        binding.webview.loadUrl(CUCO_URL)
    }

    private fun evaluate(view: WebView) {
        view.evaluateJavascript(fillJs) { result ->
            Log.d(TAG, "fill result=$result")
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (binding.webview.canGoBack()) binding.webview.goBack()
        else super.onBackPressed()
    }

    companion object {
        private const val TAG = "WebViewActivity"
        const val EXTRA_SERIAL = "serial"
        const val EXTRA_CTIME = "ctime"
        const val EXTRA_USAGE = "usage"
        private const val CUCO_URL = "https://cuco.inforlandia.pt/ucode/"
    }
}
