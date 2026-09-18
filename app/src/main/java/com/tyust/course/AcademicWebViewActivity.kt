package com.tyust.course

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceResponse
import android.webkit.WebResourceError
import android.webkit.WebChromeClient
import com.tyust.course.academic.AcademicUrlPolicy
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.tyust.course.ui.system.GlassPageScaffold
import com.tyust.course.ui.system.SystemIconButton
import com.tyust.course.ui.system.SystemPrimaryButton
import com.tyust.course.ui.theme.CourseSelectorTheme
import com.tyust.course.login.WebLoginNavigation
import com.tyust.course.ui.screen.WebLoginAddressBar

/**
 * Interactive login browser for captcha and SSO pages. Navigation may cross
 * domains; cookie export remains bound to the configured academic address.
 * It has no JavaScript bridge and uses a separate WebView storage directory.
 */
class AcademicWebViewActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.tyust.course.manager.AppThemeCoordinator.wrapContext(newBase))
    }
    companion object {
        const val EXTRA_START_URL = "academic_webview_start_url"
        const val EXTRA_ALLOWED_HOSTS = "academic_webview_allowed_hosts"
        const val EXTRA_COOKIE_RESULT = CookieWebViewActivity.EXTRA_COOKIE_RESULT
        const val EXTRA_COOKIE_URL = "academic_cookie_url"
        const val EXTRA_PAGE_URL = "academic_page_url"
        const val EXTRA_SEARCH_KEYWORD = "academic_search_keyword"
        private var suffixConfigured = false
    }

    private var webView: WebView? = null
    private var startUrl = ""
    private var cookieUrl = ""
    private var searchUrl = ""
    private var currentUrl by mutableStateOf("")
    private var allowedHosts: Set<String> = emptySet()
    private var loadingProgress by mutableFloatStateOf(0f)
    private var pageError by mutableStateOf<String?>(null)
    private var hasAutoFinished = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startUrl = intent.getStringExtra(EXTRA_START_URL).orEmpty()
        cookieUrl = intent.getStringExtra(EXTRA_COOKIE_URL).orEmpty().ifBlank { startUrl }
        val keyword = intent.getStringExtra(EXTRA_SEARCH_KEYWORD).orEmpty()
        searchUrl = WebLoginNavigation.searchUrl(keyword.ifBlank { "教务系统 登录" })
        val initialUrl = if (startUrl.isNotBlank()) startUrl else searchUrl
        currentUrl = initialUrl
        allowedHosts = (intent.getStringArrayListExtra(EXTRA_ALLOWED_HOSTS).orEmpty())
            .map { normalizeHost(it) }
            .filter { it.isNotBlank() }
            .toSet()
        if (!WebLoginNavigation.isWebUrl(startUrl) || !isCookieUrlAllowed()) {
            Toast.makeText(this, "教务地址不在允许范围内", Toast.LENGTH_LONG).show()
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && !suffixConfigured) {
            WebView.setDataDirectorySuffix("academic")
            suffixConfigured = true
        }
        val browser = createWebView()
        webView = browser
        setContent {
            CourseSelectorTheme {
                BackHandler { navigateBack() }
                GlassPageScaffold(
                    title = "教务网页登录",
                    subtitle = Uri.parse(currentUrl).host,
                    modifier = Modifier.imePadding(),
                    onBack = ::navigateBack,
                    actions = {
                        SystemIconButton(Icons.Default.School, "教务入口", { browser.loadUrl(startUrl) })
                        SystemIconButton(Icons.Default.Refresh, "刷新网页", { browser.reload() })
                        SystemIconButton(Icons.Default.Delete, "清空重登", {
                            CookieManager.getInstance().removeAllCookies {
                                CookieManager.getInstance().flush()
                                Toast.makeText(this@AcademicWebViewActivity, "已清除免密登录态，请重新输入账号密码", Toast.LENGTH_SHORT).show()
                                browser.loadUrl(startUrl.ifBlank { initialUrl })
                            }
                        })
                    }
                ) { padding ->
                    Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp)) {
                        WebLoginAddressBar(currentUrl, ::navigateToInput, { browser.loadUrl(searchUrl) })
                        Spacer(Modifier.height(8.dp))
                        if (loadingProgress < 1f) {
                            LinearProgressIndicator(progress = { loadingProgress }, modifier = Modifier.fillMaxWidth())
                        }
                        AndroidView(
                            factory = { browser },
                            modifier = Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(20.dp))
                        )
                        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                text = pageError ?: "完成学校验证后，点“完成登录”返回应用",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (pageError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            SystemPrimaryButton("完成登录", ::finishWithCookie, Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
        // 保留 SSO 免密登录凭据（如 CAS TGC / session），实现下次秒级自动换票续期；若需清空可点击右上角“清空重登”
        browser.loadUrl(initialUrl)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView = WebView(this).apply {
        val webViewInstance = this
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        com.tyust.course.manager.AppThemeCoordinator.preserveWebContentColors(settings)
        settings.defaultTextEncodingName = "UTF-8"
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.userAgentString = "Mozilla/5.0 (Linux; Android 10; SM-G981B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.setSupportMultipleWindows(true)
        settings.javaScriptCanOpenWindowsAutomatically = true
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webViewInstance, true)
        }
        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                loadingProgress = newProgress / 100f
            }

            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?
            ): Boolean {
                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                transport.webView = view
                resultMsg.sendToTarget()
                return true
            }
        }
        webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                pageError = null
                currentUrl = url
                loadingProgress = 0f
            }
            override fun onPageFinished(view: WebView, url: String) {
                loadingProgress = 1f
                currentUrl = url
                CookieManager.getInstance().flush()
                checkAutoFinish(url)
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    pageError = "网页暂时无法加载，请点击右上角刷新"
                    loadingProgress = 1f
                }
            }
            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (request.isForMainFrame) pageError = "网页返回 HTTP ${response.statusCode}，可修改网址或搜索学校入口"
            }
            override fun onReceivedSslError(view: WebView, handler: android.webkit.SslErrorHandler, error: android.net.http.SslError) {
                handler.proceed()
            }
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (request.url.scheme in setOf("data", "blob", "about") || WebLoginNavigation.isWebUrl(request.url.toString())) return null
                return null
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                return handleNavigation(request.url.toString(), request.isForMainFrame)
            }

            @Deprecated("API 21 compatibility")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                return handleNavigation(url, true)
            }
        }
    }

    private fun checkAutoFinish(url: String) {
        if (hasAutoFinished) return
        val cookie = getCombinedCookie()
        val isTargetPage = url.contains("kbcx") || url.contains("xskbcx") || url.contains("jwxt") ||
                (cookieUrl.isNotBlank() && Uri.parse(url).host == Uri.parse(cookieUrl).host)
        val isNotLogin = !url.contains("/cas/") && !url.contains("/login")
        val hasJSession = cookie.contains("JSESSIONID=", ignoreCase = true)
        if (isTargetPage && isNotLogin && hasJSession) {
            hasAutoFinished = true
            Toast.makeText(this@AcademicWebViewActivity, "登录成功，正在进入...", Toast.LENGTH_SHORT).show()
            webView?.postDelayed({ finishWithCookie() }, 500)
        } else if (url.contains("portal", ignoreCase = true) && !url.contains("/login", ignoreCase = true) && startUrl.isNotBlank()) {
            // 用户在门户页面（可能已单点登录），自动跳转教务入口
            webView?.postDelayed({
                if (currentUrl.contains("portal", ignoreCase = true)) {
                    Toast.makeText(this@AcademicWebViewActivity, "已登录统一门户，正在跳转教务系统...", Toast.LENGTH_SHORT).show()
                    webView?.loadUrl(startUrl)
                }
            }, 600)
        }
    }

    private fun getCombinedCookie(): String {
        val currentUrl = webView?.url.orEmpty()
        val cookieManager = CookieManager.getInstance()
        val cookieSet = LinkedHashSet<String>()
        val urlsToTry = mutableListOf<String>()
        if (cookieUrl.isNotBlank()) urlsToTry.add(cookieUrl)
        if (currentUrl.isNotBlank()) urlsToTry.add(currentUrl)
        runCatching {
            val uri = Uri.parse(currentUrl)
            uri.host?.let { host ->
                urlsToTry.add("https://$host/")
                urlsToTry.add("http://$host/")
                val parts = host.split(".")
                if (parts.size >= 2) {
                    val rootDomain = parts.takeLast(2).joinToString(".")
                    urlsToTry.add("https://$rootDomain/")
                    urlsToTry.add("https://.$rootDomain/")
                }
            }
        }
        for (u in urlsToTry) {
            cookieManager.getCookie(u)?.split(';')?.forEach { part ->
                val trimmed = part.trim()
                if (trimmed.contains('=')) {
                    cookieSet.add(trimmed)
                }
            }
        }
        return cookieSet.joinToString("; ")
    }

    private fun finishWithCookie() {
        val currentUrl = webView?.url.orEmpty()
        if (currentUrl.contains("portal", ignoreCase = true) && startUrl.isNotBlank()) {
            Toast.makeText(this, "当前在学校门户，正在前往教务系统...", Toast.LENGTH_SHORT).show()
            webView?.loadUrl(startUrl)
            return
        }
        val cookie = getCombinedCookie()
        if (cookie.isBlank()) {
            Toast.makeText(this, "请先登录并进入 ${Uri.parse(cookieUrl).host} 的教务主页", Toast.LENGTH_LONG).show()
            return
        }
        val isStillOnLogin = currentUrl.contains("/cas/") || currentUrl.contains("/login")
        val hasSession = cookie.contains("JSESSIONID=", ignoreCase = true) ||
                cookie.contains("session", ignoreCase = true) ||
                cookie.contains("token", ignoreCase = true) ||
                cookie.contains("ticket", ignoreCase = true)
        if (isStillOnLogin && !hasSession) {
            Toast.makeText(this, "当前仍在统一认证/登录页面，请先在网页中完成登录并等待跳转到教务系统", Toast.LENGTH_LONG).show()
            return
        }
        CookieManager.getInstance().flush()
        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_COOKIE_RESULT, cookie).putExtra(EXTRA_PAGE_URL, currentUrl.ifBlank { webView?.url }))
        finish()
    }

    private fun navigateBack() {
        if (webView?.canGoBack() == true) webView?.goBack()
        else { setResult(Activity.RESULT_CANCELED); finish() }
    }

    private fun isAllowed(uri: Uri): Boolean {
        return AcademicUrlPolicy.isAllowed(uri.toString(), "", allowedHosts)
    }

    private fun isCookieUrlAllowed(): Boolean =
        AcademicUrlPolicy.isAllowed(cookieUrl, Uri.parse(startUrl).scheme.orEmpty(), allowedHosts)

    private fun navigateToInput(input: String) {
        val target = WebLoginNavigation.resolveInput(input)
        if (target == null) Toast.makeText(this, "请输入网址或搜索关键词", Toast.LENGTH_SHORT).show()
        else webView?.loadUrl(target)
    }

    private fun handleNavigation(url: String, mainFrame: Boolean): Boolean {
        if (WebLoginNavigation.isWebUrl(url) || url == "about:blank" || url.startsWith("javascript:", true)) return false
        if (mainFrame) Toast.makeText(this, "请使用网页方式继续登录", Toast.LENGTH_SHORT).show()
        return true
    }

    private fun normalizeHost(raw: String): String {
        val value = raw.trim().removePrefix("http://").removePrefix("https://").substringBefore('/')
        return value.lowercase().removeSuffix(".")
    }

    override fun onDestroy() {
        webView?.apply { stopLoading(); destroy() }
        webView = null
        super.onDestroy()
    }
}
