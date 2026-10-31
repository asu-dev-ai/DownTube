package com.downtube.app

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.downtube.app.databinding.ActivityBrowserBinding

/**
 * Simple browser so any site can be opened, played inline and then downloaded.
 * In "login" mode the cookies of the page are saved for yt-dlp.
 */
class BrowserActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBrowserBinding
    private var cookieMode = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBrowserBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cookieMode = intent.getBooleanExtra(EXTRA_COOKIES, false)
        binding.cookiesButton.visibility = if (cookieMode) View.VISIBLE else View.GONE
        binding.hint.text = getString(if (cookieMode) R.string.browser_login_hint else R.string.browser_hint)

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(binding.webView, true)

        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
        }

        binding.webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                binding.progressBar.progress = newProgress
                binding.progressBar.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
            }
        }

        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                if (!url.isNullOrBlank()) binding.urlBar.setText(url)
                binding.backButton.isEnabled = view?.canGoBack() == true
            }
        }

        binding.webView.setDownloadListener { url, _, _, _, _ -> enqueue(url) }

        binding.goButton.setOnClickListener { load(binding.urlBar.text.toString()) }
        binding.urlBar.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                load(binding.urlBar.text.toString())
                true
            } else {
                false
            }
        }
        binding.backButton.setOnClickListener {
            if (binding.webView.canGoBack()) binding.webView.goBack()
        }
        binding.downloadButton.setOnClickListener {
            enqueue(binding.webView.url ?: binding.urlBar.text.toString())
        }
        binding.cookiesButton.setOnClickListener { saveCookies() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.webView.canGoBack()) {
                    binding.webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        load(intent.getStringExtra(EXTRA_URL) ?: DEFAULT_URL)
    }

    private fun load(input: String) {
        val text = input.trim()
        if (text.isEmpty()) return
        val url = if (text.startsWith("http")) text else "https://$text"
        binding.webView.loadUrl(url)
        binding.urlBar.setText(url)
    }

    private fun enqueue(url: String?) {
        if (url.isNullOrBlank()) return
        if (!url.startsWith("http")) {
            toast("This link cannot be downloaded")
            return
        }
        val item = DownloadItem(
            id = DownloadService.newId(),
            url = url,
            quality = Quality.BEST,
            playlist = false,
        )
        DownloadStore.add(item)
        DownloadService.start(this)
        toast("Added to downloads")
    }

    private fun saveCookies() {
        val url = binding.webView.url ?: binding.urlBar.text.toString()
        val count = Cookies.save(this, url)
        if (count == 0) {
            toast("No cookies here - log in first, then tap again")
        } else {
            toast("Saved $count cookies for future downloads")
            finish()
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_COOKIES = "cookies"
        private const val DEFAULT_URL = "https://www.youtube.com"
    }
}
