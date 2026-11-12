package com.personal.circlesearch

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

/**
 * Built-in floating Google Lens window (draggable header, resizable corner, back navigation).
 * Works on every device because it is our own overlay window, not a system freeform window.
 */
class FloatingLensWindow(
    private val context: Context,
    private val onClosed: () -> Unit
) {
    private val wm: WindowManager = context.getSystemService(WindowManager::class.java)
    private var root: RootLayout? = null
    private var web: WebView? = null
    private var progress: ProgressBar? = null
    private var lp: WindowManager.LayoutParams? = null
    private var finishedLoads = 0

    val isShown: Boolean get() = root != null

    fun showWithImage(jpegBase64: String) {
        if (root == null && !build()) {
            Toast.makeText(context, "Could not open the Lens window", Toast.LENGTH_LONG).show()
            onClosed()
            return
        }
        finishedLoads = 0
        web?.loadDataWithBaseURL(
            "https://lens.google.com/",
            buildHtml(jpegBase64),
            "text/html",
            "utf-8",
            null
        )
    }

    fun dismiss() {
        val r = root ?: return
        root = null
        try { web?.stopLoading() } catch (_: Throwable) {}
        try { wm.removeViewImmediate(r) } catch (_: Throwable) {}
        web?.let {
            (it.parent as? ViewGroup)?.removeView(it)
            try { it.destroy() } catch (_: Throwable) {}
        }
        web = null
        progress = null
        lp = null
        onClosed()
    }

    private inner class RootLayout(ctx: Context) : FrameLayout(ctx) {
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) {
                    val w = web
                    if (w != null && w.canGoBack()) w.goBack() else dismiss()
                }
                return true
            }
            return super.dispatchKeyEvent(event)
        }
    }

    private inner class GripView(ctx: Context) : View(ctx) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(160, 255, 255, 255)
            strokeWidth = ctx.dp(2f)
            strokeCap = Paint.Cap.ROUND
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val m = context.dp(8f)
            canvas.drawLine(w - m, h - m - context.dp(14f), w - m - context.dp(14f), h - m, paint)
            canvas.drawLine(w - m, h - m - context.dp(7f), w - m - context.dp(7f), h - m, paint)
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun build(): Boolean {
        return try {
            val b = wm.screenBounds()
            val ins = wm.systemBarInsets()
            val w = (b.width() * 0.94f).toInt()
            val h = (b.height() * 0.62f).toInt()
            val radius = context.dp(22f)

            val container = RootLayout(context).apply {
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#202124"))
                    cornerRadius = radius
                }
                clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, radius)
                    }
                }
                isFocusable = true
                isFocusableInTouchMode = true
            }

            val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            container.addView(
                column,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )

            // Header (drag handle)
            val header = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(context.dpInt(16), 0, context.dpInt(4), 0)
            }
            val title = TextView(context).apply {
                text = "Google Lens"
                setTextColor(Color.WHITE)
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER_VERTICAL
            }
            header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))

            fun headerButton(label: String, action: () -> Unit): TextView = TextView(context).apply {
                text = label
                setTextColor(Color.WHITE)
                textSize = 18f
                gravity = Gravity.CENTER
                setOnClickListener { action() }
            }
            val btnSize = context.dpInt(44)
            header.addView(headerButton("↻") { web?.reload() }, LinearLayout.LayoutParams(btnSize, btnSize))
            header.addView(headerButton("✕") { dismiss() }, LinearLayout.LayoutParams(btnSize, btnSize))
            column.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dpInt(44)))

            // Web area
            val webFrame = FrameLayout(context)
            column.addView(webFrame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

            val wv = WebView(context)
            wv.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                javaScriptCanOpenWindowsAutomatically = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                userAgentString = userAgentString.replace("; wv", "")
            }
            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(wv, true)
            }
            val bar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                visibility = View.GONE
            }
            wv.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val scheme = request.url.scheme
                    return !(scheme == "http" || scheme == "https")
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    finishedLoads++
                    // The first load is our auto-submitting form page; drop it from history so Back
                    // never re-submits the search.
                    if (finishedLoads == 2) view.clearHistory()
                    bar.visibility = View.GONE
                }
            }
            wv.webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    bar.progress = newProgress
                    bar.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
                }
            }
            webFrame.addView(wv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            webFrame.addView(
                bar,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dpInt(3), Gravity.TOP)
            )

            // Resize grip
            val grip = GripView(context)
            container.addView(
                grip,
                FrameLayout.LayoutParams(context.dpInt(40), context.dpInt(40), Gravity.END or Gravity.BOTTOM)
            )

            val params = WindowManager.LayoutParams(
                w, h,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = (b.width() - w) / 2
                y = b.height() - h - ins.bottom - context.dpInt(12)
                softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }

            // Drag by header
            header.setOnTouchListener(object : View.OnTouchListener {
                private var sx = 0f
                private var sy = 0f
                private var ox = 0
                private var oy = 0
                override fun onTouch(v: View, e: MotionEvent): Boolean {
                    val p = lp ?: return false
                    when (e.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            sx = e.rawX; sy = e.rawY; ox = p.x; oy = p.y
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val sb = wm.screenBounds()
                            p.x = (ox + e.rawX - sx).toInt().coerceIn(0, (sb.width() - p.width).coerceAtLeast(0))
                            p.y = (oy + e.rawY - sy).toInt().coerceIn(0, (sb.height() - p.height).coerceAtLeast(0))
                            root?.let { try { wm.updateViewLayout(it, p) } catch (_: Throwable) {} }
                        }
                    }
                    return true
                }
            })

            // Resize by grip
            grip.setOnTouchListener(object : View.OnTouchListener {
                private var sx = 0f
                private var sy = 0f
                private var ow = 0
                private var oh = 0
                override fun onTouch(v: View, e: MotionEvent): Boolean {
                    val p = lp ?: return false
                    when (e.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            sx = e.rawX; sy = e.rawY; ow = p.width; oh = p.height
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val sb = wm.screenBounds()
                            val minW = context.dpInt(240)
                            val minH = context.dpInt(280)
                            p.width = (ow + e.rawX - sx).toInt().coerceIn(minW, (sb.width() - p.x).coerceAtLeast(minW))
                            p.height = (oh + e.rawY - sy).toInt().coerceIn(minH, (sb.height() - p.y).coerceAtLeast(minH))
                            root?.let { try { wm.updateViewLayout(it, p) } catch (_: Throwable) {} }
                        }
                    }
                    return true
                }
            })

            wm.addView(container, params)
            root = container
            web = wv
            progress = bar
            lp = params
            container.requestFocus()
            true
        } catch (_: Throwable) {
            root = null
            web = null
            progress = null
            lp = null
            false
        }
    }

    private fun buildHtml(b64: String): String {
        val lang = Locale.getDefault().language.ifBlank { "en" }
        val ts = System.currentTimeMillis()
        return """<!doctype html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<style>html,body{height:100%;margin:0;background:#202124;color:#e8eaed;font-family:sans-serif}
body{display:flex;align-items:center;justify-content:center;font-size:15px}</style></head>
<body>Searching with Google Lens&hellip;
<form id="f" method="POST" enctype="multipart/form-data" action="https://lens.google.com/v3/upload?hl=$lang&amp;re=df&amp;st=$ts&amp;ep=gisbubb">
<input type="file" id="i" name="encoded_image" style="display:none">
</form>
<script>
(function(){
 try{
  var b=atob("$b64"),n=b.length,u=new Uint8Array(n);
  for(var k=0;k<n;k++){u[k]=b.charCodeAt(k);}
  var file=new File([u],"image.jpg",{type:"image/jpeg"});
  var dt=new DataTransfer();dt.items.add(file);
  document.getElementById("i").files=dt.files;
  document.getElementById("f").submit();
 }catch(e){document.body.textContent="Could not start Lens search: "+e;}
})();
</script></body></html>"""
    }
}
