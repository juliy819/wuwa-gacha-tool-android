package com.wuwa.gachatool

import android.app.Activity
import android.graphics.Color
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView

private const val CLOUD_URL = "https://mc.kurogames.com/cloud/index.html#/"
private const val GACHA_HOST = "aki-gm-resources.aki-game.com"
private const val GACHA_PATH = "/aki/gacha/index.html"

class CloudGachaActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var captured = false
    private lateinit var webView: WebView
    private lateinit var statusView: TextView

    private val helper = """
        (() => {
          if (window.__wuwaHelper || window !== window.top) return;
          window.__wuwaHelper = true;
          const normalized = value => (value || '').replace(/[\s\u00a0]+/g, '');
          const visible = element => {
            if (!(element instanceof HTMLElement)) return false;
            const rect = element.getBoundingClientRect();
            const style = getComputedStyle(element);
            return rect.width > 0 && rect.height > 0 && style.display !== 'none' && style.visibility !== 'hidden';
          };
          let lastStatus = '';
          const status = message => { if (message === lastStatus) return; lastStatus = message; try { AndroidCapture.status(message); } catch (_) {} };
          const click = element => {
            if (!visible(element)) return false;
            element.scrollIntoView({ block: 'center' });
            element.click();
            return true;
          };
          const findExact = label => Array.from(document.querySelectorAll('button,a,[role="button"],span,div,p'))
            .find(element => visible(element) && normalized(element.innerText || element.textContent) === label);
          const findTool = () => {
            const link = Array.from(document.querySelectorAll('a.route-link,a[href]'))
              .find(element => visible(element) && normalized(element.textContent) === '工具');
            return link || findExact('工具')?.closest('a,button,[role="button"]');
          };
          const findRecord = () => {
            const mobileItems = Array.from(document.querySelectorAll('.tools-mobile .tool-item'));
            const mobile = mobileItems.find(element => normalized(element.textContent).includes('唤取记录'));
            if (mobile && visible(mobile)) return mobile;
            const wrappers = Array.from(document.querySelectorAll('.tools-inner .tool-card-wrapper,.tool-card-wrapper'));
            const wrapper = wrappers.find(element => normalized(element.textContent).includes('唤取记录'));
            return wrapper?.querySelector('.tool-card') || wrapper || findExact('唤取记录')?.closest('.tool-item,.tool-card,button,a,[role="button"]');
          };
          let lastRoute = '';
          let lastActionAt = 0;
          let recordAttempts = 0;
          const inspect = () => {
            const loggedIn = /通行证\s*ID\s*[:：]\s*\d{9}/i.test(document.body?.innerText || '');
            if (!loggedIn) {
              status('请先登录云鸣潮');
              return;
            }
            const hash = location.hash;
            if (hash !== lastRoute) {
              lastRoute = hash;
              if (hash === '#/tools') status('工具已打开，正在查找唤取记录');
            }
            const now = Date.now();
            if (hash !== '#/tools') {
              if (now - lastActionAt < 3000) return;
              const tool = findTool();
              if (tool && click(tool)) {
                lastActionAt = now;
                status('正在打开工具');
              } else {
                status('请登录云鸣潮，登录后将自动提取');
              }
              return;
            }
            if (recordAttempts >= 4 || now - lastActionAt < 2500) return;
            const record = findRecord();
            if (!record) return;
            recordAttempts += 1;
            lastActionAt = now;
            if (click(record)) status('正在打开唤取记录并识别链接');
          };
          new MutationObserver(inspect).observe(document.documentElement, { childList: true, subtree: true, attributes: true, attributeFilter: ['class','href','src'] });
          window.setInterval(inspect, 700);
          inspect();
        })();
    """.trimIndent()

    private fun isGachaUrl(uri: Uri): Boolean =
        uri.scheme == "https" &&
            uri.host == GACHA_HOST &&
            uri.path == GACHA_PATH &&
            uri.getQueryParameter("player_id")?.isNotBlank() == true &&
            uri.getQueryParameter("record_id")?.isNotBlank() == true

    private fun capture(url: String) {
        if (captured) return
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return
        if (!isGachaUrl(uri)) return
        captured = true
        android.util.Log.i("CloudGacha", "captured official gacha url")
        runOnUiThread {
            statusView.text = "已识别唤取记录"
            setResult(RESULT_OK, intent.putExtra("url", url))
            finish()
        }
    }

    private fun status(message: String) {
        android.util.Log.i("CloudGacha", message)
        runOnUiThread { if (!captured) statusView.text = message }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WebView.setWebContentsDebuggingEnabled(applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        webView = WebView(this).apply {
            setBackgroundColor(Color.rgb(15, 17, 18))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            addJavascriptInterface(object {
                @android.webkit.JavascriptInterface
                fun status(message: String) = this@CloudGachaActivity.status(message)
            }, "AndroidCapture")
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    capture(request.url.toString())
                    return false
                }

                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    capture(request.url.toString())
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageFinished(view: WebView, url: String) {
                    capture(url)
                    view.evaluateJavascript(helper, null)
                }
            }
        }
        statusView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            setBackgroundColor(Color.argb(210, 24, 24, 24))
            setPadding(18, 10, 18, 10)
            text = "正在打开云鸣潮"
        }
        val root = FrameLayout(this).apply {
            addView(webView, FrameLayout.LayoutParams(-1, -1))
            addView(statusView, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        }
        setContentView(root)
        webView.loadUrl(CLOUD_URL)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        webView.destroy()
        super.onDestroy()
    }
}
