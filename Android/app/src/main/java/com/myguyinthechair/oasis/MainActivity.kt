package com.myguyinthechair.oasis

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

/** Site the app wraps (same URL as the iOS build). */
private const val HOME_URL = "https://oasis.myguyinthechair.com"

/** Hosts that stay inside the app. Anything else opens in the browser / system. */
private val INTERNAL_HOSTS = setOf("oasis.myguyinthechair.com", "myguyinthechair.com")

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var progress: ProgressBar
    private lateinit var offline: View

    // Enabled only while the WebView has history, so the system back gesture
    // (and predictive back animation) exits the app when there is nothing to go back to.
    private lateinit var backCallback: OnBackPressedCallback

    // --- <input type="file"> support -------------------------------------------------------
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            filePathCallback?.onReceiveValue(uris)
            filePathCallback = null
        }

    // --- Camera / microphone for web capture ---------------------------------------------
    private var pendingWebPermission: PermissionRequest? = null
    private val mediaPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            val request = pendingWebPermission ?: return@registerForActivityResult
            pendingWebPermission = null
            val allowed = request.resources.filter { res ->
                when (res) {
                    PermissionRequest.RESOURCE_VIDEO_CAPTURE -> has(Manifest.permission.CAMERA)
                    PermissionRequest.RESOURCE_AUDIO_CAPTURE -> has(Manifest.permission.RECORD_AUDIO)
                    else -> false
                }
            }
            if (allowed.isEmpty()) request.deny() else request.grant(allowed.toTypedArray())
        }

    // --- Location ("add location to a post") ---------------------------------------------
    private var pendingGeoOrigin: String? = null
    private var pendingGeoCallback: GeolocationPermissions.Callback? = null
    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            val granted = has(Manifest.permission.ACCESS_FINE_LOCATION) ||
                has(Manifest.permission.ACCESS_COARSE_LOCATION)
            pendingGeoCallback?.invoke(pendingGeoOrigin, granted, false)
            pendingGeoCallback = null
            pendingGeoOrigin = null
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge() // required on API 36 anyway; also handles status/nav bar icon colors
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val root = findViewById<View>(R.id.root)
        webView = findViewById(R.id.webView)
        swipe = findViewById(R.id.swipe)
        progress = findViewById(R.id.progress)
        offline = findViewById(R.id.offline)
        findViewById<Button>(R.id.retry).setOnClickListener { retry() }

        // Keep content out from under the status bar, nav bar, cutout and keyboard.
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.ime()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        backCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack()
            }
        }
        onBackPressedDispatcher.addCallback(this, backCallback)

        setUpWebView()

        // Pull to refresh (only when the page itself is scrolled to the top)
        swipe.setColorSchemeResources(R.color.accent)
        swipe.setOnChildScrollUpCallback { _, _ -> webView.scrollY > 0 }
        swipe.setOnRefreshListener { retry() }

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            webView.loadUrl(startUrl(intent))
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        webView.loadUrl(startUrl(intent))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onPause() {
        webView.onPause()
        CookieManager.getInstance().flush() // keep users logged in
        super.onPause()
    }

    override fun onDestroy() {
        // Make sure a pending chooser doesn't leave the page hanging
        filePathCallback?.onReceiveValue(null)
        filePathCallback = null
        webView.stopLoading()
        (webView.parent as? android.view.ViewGroup)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }

    // ---------------------------------------------------------------------------------------

    private fun startUrl(intent: Intent?): String {
        val data = intent?.data
        return if (data != null && isInternal(data)) data.toString() else HOME_URL
    }

    private fun isInternal(uri: Uri): Boolean {
        val host = uri.host?.lowercase() ?: return true
        return INTERNAL_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    private fun has(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun retry() {
        showOffline(false)
        if (webView.url.isNullOrEmpty()) webView.loadUrl(HOME_URL) else webView.reload()
    }

    private fun showOffline(show: Boolean) {
        offline.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun openExternal(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, uri.toString(), Toast.LENGTH_SHORT).show()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setUpWebView() {
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true                       // localStorage etc.
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            allowFileAccess = false
            setSupportZoom(false)
            builtInZoomControls = false
            // Lets the site detect the app (same idea as the iOS suffix)
            userAgentString = "$userAgentString OasisSocial-Android/${BuildConfig.VERSION_NAME}"
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            if (!url.startsWith("http")) return@setDownloadListener // blob:/data: can't go to DownloadManager
            try {
                val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
                val request = DownloadManager.Request(Uri.parse(url))
                    .setMimeType(mimeType)
                    .addRequestHeader("User-Agent", userAgent)
                    .addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url) ?: "")
                    .setTitle(name)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
                Toast.makeText(this, name, Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
                openExternal(Uri.parse(url))
            }
        }

        webView.webViewClient = object : WebViewClient() {

            // Keep the site in-app; send other links, mailto:, tel:, etc. to the system.
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                when (uri.scheme?.lowercase()) {
                    "http", "https" -> {
                        // Same rule as iOS: only user-tapped links to other sites leave the app,
                        // so redirects (e.g. login flows) still work.
                        if (!isInternal(uri) && request.isForMainFrame &&
                            request.hasGesture() && !request.isRedirect
                        ) {
                            openExternal(uri)
                            return true
                        }
                        return false
                    }
                    "about", "blob", "data", "javascript" -> return false
                    else -> {
                        openExternal(uri) // mailto:, tel:, sms:, geo:, etc.
                        return true
                    }
                }
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                showOffline(false)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                swipe.isRefreshing = false
                CookieManager.getInstance().flush()
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                backCallback.isEnabled = view.canGoBack()
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (request.isForMainFrame) {
                    swipe.isRefreshing = false
                    showOffline(true)
                }
            }

            // If the renderer dies (memory pressure) recover instead of crashing the app.
            override fun onRenderProcessGone(
                view: WebView,
                detail: android.webkit.RenderProcessGoneDetail
            ): Boolean {
                view.reload()
                return true
            }
        }

        webView.webChromeClient = object : WebChromeClient() {

            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.progress = newProgress
                progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
            }

            // <input type="file"> -> system picker / camera
            override fun onShowFileChooser(
                webView: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback
                return try {
                    fileChooserLauncher.launch(params.createIntent())
                    true
                } catch (_: ActivityNotFoundException) {
                    filePathCallback = null
                    callback.onReceiveValue(null)
                    false
                }
            }

            // getUserMedia (camera / mic) for our own site only
            override fun onPermissionRequest(request: PermissionRequest) {
                if (!isInternal(request.origin)) {
                    request.deny()
                    return
                }
                val needed = mutableListOf<String>()
                request.resources.forEach {
                    if (it == PermissionRequest.RESOURCE_VIDEO_CAPTURE &&
                        !has(Manifest.permission.CAMERA)
                    ) needed += Manifest.permission.CAMERA
                    if (it == PermissionRequest.RESOURCE_AUDIO_CAPTURE &&
                        !has(Manifest.permission.RECORD_AUDIO)
                    ) needed += Manifest.permission.RECORD_AUDIO
                }
                if (needed.isEmpty()) {
                    val ok = request.resources.filter {
                        it == PermissionRequest.RESOURCE_VIDEO_CAPTURE ||
                            it == PermissionRequest.RESOURCE_AUDIO_CAPTURE
                    }
                    if (ok.isEmpty()) request.deny() else request.grant(ok.toTypedArray())
                } else {
                    pendingWebPermission?.deny()
                    pendingWebPermission = request
                    mediaPermissionLauncher.launch(needed.toTypedArray())
                }
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String,
                callback: GeolocationPermissions.Callback
            ) {
                val host = Uri.parse(origin).host?.lowercase()
                val ours = host != null && INTERNAL_HOSTS.any { host == it || host.endsWith(".$it") }
                if (!ours) {
                    callback.invoke(origin, false, false)
                } else if (has(Manifest.permission.ACCESS_FINE_LOCATION) ||
                    has(Manifest.permission.ACCESS_COARSE_LOCATION)
                ) {
                    callback.invoke(origin, true, false)
                } else {
                    pendingGeoOrigin = origin
                    pendingGeoCallback = callback
                    locationPermissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                }
            }
        }
    }
}
