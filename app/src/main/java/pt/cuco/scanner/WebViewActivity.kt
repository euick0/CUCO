package pt.cuco.scanner

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import pt.cuco.scanner.databinding.ActivityWebviewBinding

class WebViewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWebviewBinding

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWebviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val serial = intent.getStringExtra(EXTRA_SERIAL).orEmpty()
        val ctime = intent.getStringExtra(EXTRA_CTIME).orEmpty()
        val usage = intent.getStringExtra(EXTRA_USAGE).orEmpty()

        val js = FillFormJs.build(serial, ctime, usage)

        binding.webview.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
        }

        binding.webview.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                // Small delay so any page-side JS finishes wiring inputs.
                view.postDelayed({ view.evaluateJavascript(js, null) }, 300)
            }
        }

        binding.webview.loadUrl(CUCO_URL)
    }

    override fun onBackPressed() {
        if (binding.webview.canGoBack()) binding.webview.goBack()
        else super.onBackPressed()
    }

    companion object {
        const val EXTRA_SERIAL = "serial"
        const val EXTRA_CTIME = "ctime"
        const val EXTRA_USAGE = "usage"
        private const val CUCO_URL = "https://cuco.inforlandia.pt/ucode/"
    }
}
