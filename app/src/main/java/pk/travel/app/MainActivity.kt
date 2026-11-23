package pk.travel.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Message
import android.provider.MediaStore
import android.util.Base64
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    companion object {
        private const val HOME_URL = "https://travel.pk/"
        private const val FLIGHTS_URL = "https://travel.pk/air/search/"
        private const val STAYS_URL = "https://travel.pk/hotels/"
        private const val UMRAH_URL = "https://travel.pk/umrah/"
        private const val TRIPS_URL = "https://travel.pk/login?redirect=/dashboard/user"
        private const val WHATSAPP_URL = "https://wa.me/924235692952"

        /** Hosts that must open in their own apps (or the browser), never inside the app. */
        private val EXTERNAL_HOSTS = listOf(
            "wa.me", "whatsapp.com", "whatsapp.net",
            "facebook.com", "fb.com", "fb.me", "messenger.com", "m.me",
            "instagram.com", "instagr.am",
            "youtube.com", "youtu.be",
            "maps.google.com", "maps.app.goo.gl", "goo.gl"
        )

        /**
         * The website has its own mobile bottom bar. Inside the app we already show native tabs,
         * so hide the website's bar and move its floating buttons/popups down (and the assistant
         * button to the left, so it doesn't sit under the WhatsApp button).
         */
        private const val APP_CSS = """
            .app-bottom-nav{display:none!important}
            html .app-above-bottom-nav{bottom:var(--app-safe-bottom,0px)!important}
            html .app-floating-launcher{bottom:calc(var(--app-safe-bottom,0px) + 1rem)!important;left:1rem!important;right:auto!important}
            html .app-comparison-panel{bottom:calc(var(--app-safe-bottom,0px) + 1rem)!important}
            html .app-mobile-popover,html .app-date-popover{bottom:calc(var(--app-safe-bottom,0px) + .75rem)!important}
            html .app-concierge-panel{bottom:calc(var(--app-safe-bottom,0px) + 4.75rem)!important}
        """

        private val INJECT_CSS_JS = """
            (function(){
              if (document.getElementById('tpk-app-css')) return;
              var s = document.createElement('style');
              s.id = 'tpk-app-css';
              s.textContent = ${org.json.JSONObject.quote(APP_CSS)};
              (document.head || document.documentElement).appendChild(s);
            })();
            (function(){
              // Tell the app whether the page (or the element being scrolled) is at the top,
              // so pull-to-refresh only starts when the user is really at the top.
              if (window.__tpkScroll) return;
              window.__tpkScroll = true;
              var last = null;
              function report(e){
                var t = e && e.target;
                var y = (t && t.nodeType === 1) ? t.scrollTop : 0;
                var win = window.scrollY || document.documentElement.scrollTop || 0;
                var top = (y <= 0) && (win <= 0);
                if (top !== last) { last = top; try { TravelPkAndroid.setAtTop(top); } catch (x) {} }
              }
              document.addEventListener('scroll', report, {capture: true, passive: true});
            })();
        """.trimIndent()

        /** URI schemes that always belong to another app. */
        private val EXTERNAL_SCHEMES = setOf(
            "tel", "mailto", "sms", "smsto", "whatsapp", "geo", "market", "fb", "instagram", "youtube", "comgooglemaps"
        )
    }

    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var progressBar: LinearProgressIndicator
    private lateinit var offlineView: View
    private lateinit var bottomNav: BottomNavigationView
    private lateinit var whatsappButton: FloatingActionButton

    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var cameraImageUri: Uri? = null
    private var lastBackPress = 0L
    private var failedUrl: String? = null
    private var updatingTabFromPage = false
    private var pendingDownload: (() -> Unit)? = null
    @Volatile private var pageAtTop = true

    private lateinit var connectivityManager: ConnectivityManager
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            runOnUiThread {
                // Internet came back: reload automatically if the offline screen is showing.
                if (offlineView.isVisible) reloadAfterOffline()
            }
        }
    }

    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = fileCallback ?: return@registerForActivityResult
            fileCallback = null
            var uris: Array<Uri>? = null
            if (result.resultCode == RESULT_OK) {
                val data = result.data
                val clip = data?.clipData
                uris = when {
                    clip != null && clip.itemCount > 0 -> Array(clip.itemCount) { clip.getItemAt(it).uri }
                    data?.data != null -> arrayOf(data.data!!)
                    cameraImageUri != null && cameraFileHasData() -> arrayOf(cameraImageUri!!)
                    else -> null
                }
            }
            cameraImageUri = null
            callback.onReceiveValue(uris)
        }

    private val storagePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val action = pendingDownload
            pendingDownload = null
            if (granted) action?.invoke()
            else toast(getString(R.string.download_failed))
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        progressBar = findViewById(R.id.progressBar)
        offlineView = findViewById(R.id.offlineView)
        bottomNav = findViewById(R.id.bottomNav)
        whatsappButton = findViewById(R.id.whatsappButton)

        setupInsets()
        setupWebView()
        setupBottomNav()
        setupBackHandling()

        swipeRefresh.setColorSchemeColors(ContextCompat.getColor(this, R.color.brand_blue))
        swipeRefresh.setOnChildScrollUpCallback { _, _ ->
            webView.scrollY > 0 || webView.canScrollVertically(-1) || !pageAtTop
        }
        swipeRefresh.setDistanceToTriggerSync((120 * resources.displayMetrics.density).toInt())
        swipeRefresh.setOnRefreshListener {
            if (offlineView.isVisible) reloadAfterOffline() else webView.reload()
        }

        whatsappButton.setOnClickListener { openExternal(Uri.parse(WHATSAPP_URL)) }
        findViewById<MaterialButton>(R.id.retryButton).setOnClickListener {
            if (isOnline()) reloadAfterOffline() else toast(getString(R.string.still_offline))
        }

        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivityManager.registerDefaultNetworkCallback(networkCallback)

        val startUrl = intent?.data?.toString()?.takeIf { isOwnSite(Uri.parse(it)) }
        when {
            savedInstanceState != null && webView.restoreState(savedInstanceState) != null -> Unit
            !isOnline() -> {
                failedUrl = startUrl ?: HOME_URL
                showOffline(true)
            }
            else -> webView.loadUrl(startUrl ?: HOME_URL)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.let { if (isOwnSite(it)) webView.loadUrl(it.toString()) }
    }

    // ---------------------------------------------------------------- layout / insets

    private fun setupInsets() {
        val root = findViewById<View>(R.id.root)
        WindowCompat.getInsetsController(window, root).isAppearanceLightStatusBars = false
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            v.updatePadding(left = bars.left, top = bars.top, right = bars.right,
                bottom = if (imeVisible) ime.bottom else 0)
            // Hide tabs + WhatsApp button while typing so forms have room.
            bottomNav.isVisible = !imeVisible
            whatsappButton.isVisible = !imeVisible
            bottomNav.updatePadding(bottom = bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }

    // ---------------------------------------------------------------- WebView

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(webView, true)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            @Suppress("DEPRECATION")
            databaseEnabled = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = false
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            allowFileAccess = false
            allowContentAccess = true
            userAgentString = "$userAgentString TravelPkApp/1.0"
        }
        webView.isVerticalScrollBarEnabled = false
        webView.overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        webView.addJavascriptInterface(DownloadBridge(), "TravelPkAndroid")
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(
                webView, INJECT_CSS_JS, setOf("https://travel.pk", "https://www.travel.pk")
            )
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                handleUrl(request.url)

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                pageAtTop = true
                progressBar.isVisible = true
                syncTabWithUrl(url)
            }

            override fun onPageCommitVisible(view: WebView, url: String) {
                if (isOwnSite(Uri.parse(url))) view.evaluateJavascript(INJECT_CSS_JS, null)
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (isOwnSite(Uri.parse(url))) view.evaluateJavascript(INJECT_CSS_JS, null)
                progressBar.isVisible = false
                swipeRefresh.isRefreshing = false
                CookieManager.getInstance().flush()
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                syncTabWithUrl(url)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (!request.isForMainFrame) return
                val networkErrors = setOf(
                    ERROR_HOST_LOOKUP, ERROR_CONNECT, ERROR_TIMEOUT, ERROR_IO, ERROR_PROXY_AUTHENTICATION
                )
                if (!isOnline() || error.errorCode in networkErrors) {
                    failedUrl = request.url.toString()
                    showOffline(true)
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progressBar.setProgressCompat(newProgress, true)
                progressBar.isVisible = newProgress < 100
                if (newProgress >= 100) swipeRefresh.isRefreshing = false
            }

            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                return openFileChooser(params)
            }

            // Pages that open a new window (target=_blank, payment popups) load in the same WebView.
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                val popup = WebView(view.context)
                popup.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                        if (!handleUrl(request.url)) webView.loadUrl(request.url.toString())
                        v.destroy()
                        return true
                    }
                }
                (resultMsg.obj as WebView.WebViewTransport).webView = popup
                resultMsg.sendToTarget()
                return true
            }
        }

        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            startDownload(url, userAgent, contentDisposition, mimeType)
        }
    }

    /** Returns true when the URL was handled outside the WebView. */
    private fun handleUrl(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme == "intent") {
            try {
                val intent = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
                intent.addCategory(Intent.CATEGORY_BROWSABLE)
                intent.component = null
                intent.selector = null
                startActivity(intent)
            } catch (e: Exception) {
                val fallback = Regex("S\\.browser_fallback_url=([^;]+)").find(uri.toString())
                    ?.groupValues?.get(1)?.let { Uri.decode(it) }
                if (fallback != null) webView.loadUrl(fallback) else toast(getString(R.string.no_app))
            }
            return true
        }
        if (scheme in EXTERNAL_SCHEMES) {
            openExternal(uri)
            return true
        }
        if (scheme == "http" || scheme == "https") {
            if (isExternalWebLink(uri)) {
                openExternal(uri)
                return true
            }
            return false // travel.pk, payment gateways, booking pages: stay inside the app
        }
        openExternal(uri)
        return true
    }

    private fun isExternalWebLink(uri: Uri): Boolean {
        val host = uri.host?.lowercase()?.removePrefix("www.")?.removePrefix("m.") ?: return false
        val path = uri.path.orEmpty()
        if ((host == "google.com" || host.startsWith("google.")) && path.startsWith("/maps")) return true
        if (host == "goo.gl" && !path.startsWith("/maps")) return false
        return EXTERNAL_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    private fun isOwnSite(uri: Uri): Boolean {
        val host = uri.host?.lowercase() ?: return false
        return host == "travel.pk" || host == "www.travel.pk"
    }

    private fun openExternal(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            toast(getString(R.string.no_app))
        } catch (e: Exception) {
            toast(getString(R.string.no_app))
        }
    }

    // ---------------------------------------------------------------- tabs

    private fun setupBottomNav() {
        bottomNav.setOnItemSelectedListener { item ->
            if (!updatingTabFromPage) urlForTab(item.itemId)?.let { loadTab(it) }
            true
        }
        bottomNav.setOnItemReselectedListener { item ->
            urlForTab(item.itemId)?.let { loadTab(it) }
        }
    }

    private fun loadTab(url: String) {
        if (offlineView.isVisible && !isOnline()) {
            failedUrl = url
            return
        }
        showOffline(false)
        webView.loadUrl(url)
    }

    private fun urlForTab(id: Int): String? = when (id) {
        R.id.tab_home -> HOME_URL
        R.id.tab_flights -> FLIGHTS_URL
        R.id.tab_stays -> STAYS_URL
        R.id.tab_umrah -> UMRAH_URL
        R.id.tab_trips -> TRIPS_URL
        else -> null
    }

    private fun tabForUrl(url: String): Int? {
        val uri = Uri.parse(url)
        if (!isOwnSite(uri)) return null
        val path = uri.path.orEmpty().lowercase()
        return when {
            path.startsWith("/air") || path.contains("flight") -> R.id.tab_flights
            path.startsWith("/hotel") || path.contains("stay") -> R.id.tab_stays
            path.startsWith("/umrah") -> R.id.tab_umrah
            path.startsWith("/login") || path.startsWith("/dashboard") ||
                path.startsWith("/register") || path.startsWith("/signup") ||
                path.contains("booking") || path.contains("trip") -> R.id.tab_trips
            path.isEmpty() || path == "/" -> R.id.tab_home
            else -> null
        }
    }

    private fun syncTabWithUrl(url: String) {
        val tab = tabForUrl(url) ?: return
        if (bottomNav.selectedItemId != tab) {
            updatingTabFromPage = true
            bottomNav.selectedItemId = tab
            updatingTabFromPage = false
        }
    }

    private fun isHomePage(): Boolean {
        val uri = Uri.parse(webView.url ?: return true)
        val path = uri.path.orEmpty()
        return isOwnSite(uri) && (path.isEmpty() || path == "/")
    }

    // ---------------------------------------------------------------- back button

    private fun setupBackHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    offlineView.isVisible -> exitOnDoubleBack()
                    isHomePage() -> exitOnDoubleBack()
                    webView.canGoBack() -> webView.goBack()
                    else -> loadTab(HOME_URL)
                }
            }
        })
    }

    private fun exitOnDoubleBack() {
        val now = System.currentTimeMillis()
        if (now - lastBackPress < 2000) {
            finish()
        } else {
            lastBackPress = now
            toast(getString(R.string.press_back_again))
        }
    }

    // ---------------------------------------------------------------- offline

    private fun isOnline(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun showOffline(show: Boolean) {
        offlineView.isVisible = show
        swipeRefresh.isRefreshing = false
        progressBar.isVisible = false
        if (show) webView.stopLoading()
    }

    private fun reloadAfterOffline() {
        val url = failedUrl ?: webView.url ?: HOME_URL
        failedUrl = null
        showOffline(false)
        webView.loadUrl(url)
    }

    // ---------------------------------------------------------------- file upload

    private fun openFileChooser(params: WebChromeClient.FileChooserParams): Boolean {
        val accept = params.acceptTypes.filter { it.isNotBlank() }
        val getContent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            val mimes = accept.mapNotNull { a ->
                when {
                    a.contains("/") -> a
                    a.startsWith(".") -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(a.substring(1))
                    else -> null
                }
            }.distinct()
            type = if (mimes.size == 1) mimes[0] else "*/*"
            if (mimes.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, mimes.toTypedArray())
            if (params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
        }

        val extras = mutableListOf<Intent>()
        val wantsImages = accept.isEmpty() || accept.any { it.startsWith("image") || it == "*/*" }
        if (wantsImages) {
            try {
                val dir = File(cacheDir, "camera").apply { mkdirs() }
                val photo = File.createTempFile("photo_", ".jpg", dir)
                val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", photo)
                cameraImageUri = uri
                extras += Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                    putExtra(MediaStore.EXTRA_OUTPUT, uri)
                    addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            } catch (_: Exception) {
                cameraImageUri = null
            }
        }

        val chooser = Intent.createChooser(getContent, getString(R.string.choose_file))
        if (extras.isNotEmpty()) chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, extras.toTypedArray())
        return try {
            fileChooserLauncher.launch(chooser)
            true
        } catch (e: Exception) {
            fileCallback?.onReceiveValue(null)
            fileCallback = null
            false
        }
    }

    private fun cameraFileHasData(): Boolean = try {
        contentResolver.openFileDescriptor(cameraImageUri!!, "r")?.use { it.statSize > 0 } ?: false
    } catch (_: Exception) {
        false
    }

    // ---------------------------------------------------------------- downloads (PDF tickets etc.)

    private fun startDownload(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?) {
        when {
            url.startsWith("blob:") -> {
                // Blob files (generated in the page) are read via JS and handed to Android.
                val js = """
                    (function(){
                      var x = new XMLHttpRequest();
                      x.open('GET', '$url', true);
                      x.responseType = 'blob';
                      x.onload = function(){
                        var r = new FileReader();
                        r.onloadend = function(){
                          TravelPkAndroid.saveBase64(r.result, x.response.type || '${mimeType ?: ""}', '');
                        };
                        r.readAsDataURL(x.response);
                      };
                      x.send();
                    })();
                """.trimIndent()
                webView.evaluateJavascript(js, null)
            }
            url.startsWith("data:") -> DownloadBridge().saveBase64(url, mimeType ?: "", "")
            else -> withStoragePermission {
                try {
                    val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
                    val request = DownloadManager.Request(Uri.parse(url)).apply {
                        setMimeType(mimeType)
                        CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
                        userAgent?.let { addRequestHeader("User-Agent", it) }
                        addRequestHeader("Referer", webView.url ?: HOME_URL)
                        setTitle(fileName)
                        setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                    }
                    (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
                    toast(getString(R.string.downloading) + " " + fileName)
                } catch (e: Exception) {
                    toast(getString(R.string.download_failed))
                }
            }
        }
    }

    private fun withStoragePermission(action: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            action()
        } else {
            pendingDownload = action
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    inner class DownloadBridge {
        @JavascriptInterface
        fun setAtTop(atTop: Boolean) {
            pageAtTop = atTop
        }

        @JavascriptInterface
        fun saveBase64(dataUrl: String, mime: String, name: String) {
            runOnUiThread {
                withStoragePermission { saveDataUrl(dataUrl, mime, name) }
            }
        }
    }

    private fun saveDataUrl(dataUrl: String, mimeIn: String, nameIn: String) {
        try {
            val comma = dataUrl.indexOf(',')
            val header = dataUrl.substring(0, comma)
            val mime = mimeIn.ifBlank { header.removePrefix("data:").substringBefore(';').ifBlank { "application/pdf" } }
            val bytes = if (header.contains(";base64")) Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT)
            else Uri.decode(dataUrl.substring(comma + 1)).toByteArray()
            val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "bin"
            val name = nameIn.ifBlank { "TravelPk_${System.currentTimeMillis()}.$ext" }

            val savedUri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
                contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
                uri
            } else {
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                dir.mkdirs()
                val file = File(dir, name)
                FileOutputStream(file).use { it.write(bytes) }
                FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            }
            toast(getString(R.string.download_saved, name))
            try {
                startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(savedUri, mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            } catch (_: Exception) {
            }
        } catch (e: Exception) {
            toast(getString(R.string.download_failed))
        }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onPause() {
        CookieManager.getInstance().flush() // keeps the user logged in after the app is closed
        webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {
        }
        webView.destroy()
        super.onDestroy()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
