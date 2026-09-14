package com.wpe64.wpc

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.wpe64.wpc.bridge.Bridge
import com.wpe64.wpc.service.ProxyService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 界面外壳：一个全屏 WebView，加载 assets/www（共用前端 WPEProxyCap.Web 的 Android 构建）。
 * 对应 Windows 版的 ShellForm（WebView2 宿主）。
 *
 *   · 资源走 WebViewAssetLoader 的 https 虚拟域，不用 file://（file:// 下 CSP、字体、ES 模块都有坑）；
 *   · 桥是 WebMessageListener，只对本地资源来源开放；任何页面跳转一律拦掉（外链由 openExternal 交给浏览器）；
 *   · 边到边（Android 15 起强制）：状态栏 / 刘海 / 手势条 / 软键盘的留白按 WindowInsets 给根布局加内边距，
 *     根布局底色跟着主题走，留白区与页面同色；
 *   · 返回键先问前端（window.__wpcBack：有弹窗就关弹窗），前端没消费就退到后台 —— VPN 在前台服务里继续跑；
 *   · WebView 太老（没有 WebMessageListener，或 Chromium 低于 90）直接给一屏原生的引导更新页。
 */
class MainActivity : ComponentActivity() {

    companion object {
        private const val MIN_WEBVIEW = 90
        private const val START_URL = "https://appassets.androidplatform.net/assets/www/index.html"
    }

    private val svc: ProxyService get() = (application as WpcApp).service

    private var webView: WebView? = null
    private var root: FrameLayout? = null
    private var bridge: Bridge? = null

    private var vpnResult: CompletableDeferred<Boolean>? = null
    private val vpnLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        vpnResult?.complete(r.resultCode == RESULT_OK)
        vpnResult = null
    }

    private val notifLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 拒绝也照常用，只是通知栏看不到连接状态 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val major = svc.webViewMajor()
        if ((major in 1 until MIN_WEBVIEW) || !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            showWebViewTooOld(major)
            return
        }

        setupWebView()
        requestNotificationPermission()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val wv = WebView(this)
        val frame = FrameLayout(this).apply {
            addView(wv, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        setContentView(frame)
        webView = wv
        root = frame

        ViewCompat.setOnApplyWindowInsetsListener(frame) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        applyAppearance(svc.store.config.isDark)

        wv.setBackgroundColor(Color.TRANSPARENT)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportZoom(false)
            builtInZoomControls = false
            // 界面是按 CSS 像素令牌排的：跟随系统字号放大会把定宽的读数与按钮挤爆，所以固定 100%
            textZoom = 100
        }

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)

            // 页面自己不许跳走：外链一律经桥的 openExternal 交给系统浏览器
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
        }

        val b = Bridge(svc, svc.scope)
        bridge = b
        WebViewCompat.addWebMessageListener(wv, Bridge.JS_OBJECT, setOf(Bridge.ORIGIN), b)

        svc.attach(
            push = b::pushEvent,
            vpnConsent = ::askVpnConsent,
            appearance = { dark -> runOnUiThread { applyAppearance(dark) } },
        )

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                wv.evaluateJavascript("(window.__wpcBack && window.__wpcBack()) ? '1' : '0'") { r ->
                    if (r?.contains('1') != true) moveTaskToBack(true)
                }
            }
        })

        wv.loadUrl(START_URL)
    }

    /** 系统的 VPN 授权弹框。ProxyService 在后台线程上 await 结果。 */
    private suspend fun askVpnConsent(intent: Intent): Boolean = withContext(Dispatchers.Main) {
        val d = CompletableDeferred<Boolean>()
        vpnResult?.complete(false)
        vpnResult = d
        try {
            vpnLauncher.launch(intent)
        } catch (_: Exception) {
            vpnResult = null
            return@withContext false
        }
        d.await()
    }

    private fun applyAppearance(isDark: Boolean) {
        val bg = ContextCompat.getColor(this, if (isDark) R.color.wpc_black else R.color.wpc_light)
        root?.setBackgroundColor(bg)
        window.decorView.setBackgroundColor(bg)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !isDark
            isAppearanceLightNavigationBars = !isDark
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        // 从系统设置（电池优化、VPN）回来：权限状态可能变了，让前端重取
        bridge?.pushEvent("resume", null)
    }

    override fun onDestroy() {
        if (bridge != null) svc.detach()
        webView?.let {
            (it.parent as? ViewGroup)?.removeView(it)
            it.destroy()
        }
        webView = null
        bridge = null
        super.onDestroy()
    }

    // ———————————————— WebView 太老 ————————————————

    private fun showWebViewTooOld(major: Int) {
        val pad = (24 * resources.displayMetrics.density).toInt()
        val bg = ContextCompat.getColor(this, R.color.wpc_black)

        val message = TextView(this).apply {
            setTextColor(Color.parseColor("#C9CDD6"))
            textSize = 15f
            setLineSpacing(0f, 1.3f)
            setText(getString(R.string.webview_too_old, major, MIN_WEBVIEW))
        }
        val updateButton = Button(this).apply {
            setText(getString(R.string.webview_update))
            setOnClickListener {
                val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.webview"))
                val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.webview"))
                runCatching { startActivity(market) }.onFailure { runCatching { startActivity(web) } }
            }
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(bg)
            addView(message)
            addView(updateButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = pad })
        }
        setContentView(column)
        ViewCompat.setOnApplyWindowInsetsListener(column) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(pad + bars.left, pad + bars.top, pad + bars.right, pad + bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }
}
