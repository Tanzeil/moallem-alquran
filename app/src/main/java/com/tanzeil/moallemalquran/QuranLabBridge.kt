package com.tanzeil.moallemalquran

import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebView
import ai.quranlab.tajweedformer.TajweedClient
import ai.quranlab.tajweedformer.TajweedError
import ai.quranlab.tajweedformer.TajweedModel
import ai.quranlab.tajweedformer.TajweedSession
import ai.quranlab.tajweedformer.TajweedSessionOptions
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class QuranLabBridge(context: Context, private val webView: WebView) {
    private val appContext = context.applicationContext
    private val scope = MainScope()
    private val key = BuildConfig.QURANLAB_KEY
    private var client: TajweedClient? = null
    private var model: TajweedModel? = null
    private var session: TajweedSession? = null

    private fun js(name: String, value: String) {
        val safe = value.replace("\\\\", "\\\\\\\\").replace("'", "\\\\'")
        webView.post { webView.evaluateJavascript("window.$name && window.$name('$safe')", null) }
    }

    @JavascriptInterface
    fun startListening() {
        scope.launch {
            try {
                if (key.isBlank()) {
                    js("onQuranLabStatus", "مفتاح Quran Lab غير مضاف إلى عملية البناء.")
                    return@launch
                }
                js("onQuranLabStatus", "جاري تجهيز نموذج التلاوة لأول مرة...")
                val activeClient = client ?: TajweedClient(appContext, key).also { client = it }
                val activeModel = model ?: activeClient.model().also { model = it }
                js("onQuranLabStatus", "جاهز. ابدئي التلاوة.")
                val activeSession = activeModel.listen(TajweedSessionOptions(endOnSilenceMs = 1500))
                session = activeSession
                activeSession.chunks.collect { chunk ->
                    js("onQuranLabHeard", chunk.decoded.text)
                    if (chunk.decoded.isFinal) js("onQuranLabStatus", "انتهى التسجيل. راجعي النص المسموع.")
                }
                session = null
            } catch (e: TajweedError) {
                js("onQuranLabStatus", "${e.code}: ${e.message}. ${e.fix}")
            } catch (e: Exception) {
                js("onQuranLabStatus", "تعذر تشغيل الاستماع: ${e.message ?: "خطأ غير معروف"}")
            }
        }
    }

    @JavascriptInterface
    fun stopListening() {
        scope.launch {
            try { session?.finish() } catch (_: Exception) { }
            session = null
            js("onQuranLabStatus", "تم إيقاف الاستماع.")
        }
    }

    fun close() {
        session?.cancel()
        scope.cancel()
    }
}
