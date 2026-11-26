package com.tanzeil.moallemalquran

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject
import ai.quranlab.tajweedformer.TajweedClient
import ai.quranlab.tajweedformer.TajweedError
import ai.quranlab.tajweedformer.TajweedModel
import ai.quranlab.tajweedformer.TajweedSession
import ai.quranlab.tajweedformer.TajweedSessionOptions
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class QuranLabBridge(context: Context, private val webView: WebView) {
    private val activity = context as Activity
    private val appContext = context.applicationContext
    private val scope = MainScope()
    private val key = BuildConfig.QURANLAB_KEY
    private var client: TajweedClient? = null
    @Volatile private var model: TajweedModel? = null
    @Volatile private var session: TajweedSession? = null
    @Volatile private var loadingModel = false
    @Volatile private var pendingStart = false
    @Volatile private var startInProgress = false

    private fun js(name: String, value: String) {
        val safe = JSONObject.quote(value)
        webView.post { webView.evaluateJavascript("window.$name && window.$name($safe)", null) }
    }

    @JavascriptInterface
    fun setTargetAyah(surah: Int, ayah: Int) {
        // The reader keeps this call for compatibility; live recognition needs no selected ayah.
    }

    @JavascriptInterface
    fun prepareModel() {
        if (model != null || loadingModel) return
        if (key.isBlank()) {
            js("onQuranLabStatus", "مفتاح Quran Lab غير موجود في نسخة التطبيق. يلزم إضافة مفتاح التطبيق إلى إعدادات البناء.")
            return
        }
        loadingModel = true
        scope.launch {
            try {
                js("onQuranLabStatus", "جاري تجهيز نموذج التسميع لأول مرة. أبقي التطبيق مفتوحاً واتصلي بالإنترنت...")
                val activeClient = client ?: TajweedClient(appContext, key).also { client = it }
                activeClient.prefetch { done, total ->
                    val doneMb = done / (1024 * 1024)
                    val totalMb = total / (1024 * 1024)
                    js("onQuranLabStatus", "جاري تنزيل نموذج التسميع: $doneMb من $totalMb ميجابايت...")
                }
                js("onQuranLabStatus", "جاري فتح نموذج التسميع...")
                model = activeClient.model()
                loadingModel = false
                js("onQuranLabStatus", "النموذج جاهز. اضغطي بدء التسميع.")
                if (pendingStart) beginListening()
            } catch (e: TajweedError) {
                loadingModel = false
                pendingStart = false
                startInProgress = false
                js("onQuranLabStatus", "${e.code}: ${e.message}. ${e.fix}")
            } catch (e: Exception) {
                loadingModel = false
                pendingStart = false
                startInProgress = false
                js("onQuranLabStatus", "فشل تجهيز نموذج التسميع: ${e.message ?: "خطأ غير معروف"}")
            }
        }
    }

    @JavascriptInterface
    fun startListening() {
        js("onQuranLabStatus", "وصل الضغط إلى Android. جاري فحص إذن الميكروفون...")
        if (appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            js("onQuranLabStatus", "اسمحي للتطبيق باستخدام الميكروفون، وسيبدأ التسميع بعد الموافقة.")
            (activity as MainActivity).requestAudioPermissionForRecitation()
            return
        }
        if (startInProgress || session != null) {
            js("onQuranLabStatus", "جلسة التسميع تعمل بالفعل. اقرئي بصوت واضح أو اضغطي إيقاف.")
            return
        }
        pendingStart = true
        if (model == null) {
            prepareModel()
            js("onQuranLabStatus", "جاري تجهيز النموذج. سيبدأ الاستماع تلقائياً بعد اكتمال التجهيز.")
            return
        }
        beginListening()
    }

    private fun beginListening() {
        val activeModel = model ?: return
        if (startInProgress || session != null) return
        pendingStart = false
        startInProgress = true
        scope.launch {
            try {
                js("onQuranLabStatus", "جاري فتح الميكروفون الآن...")
                val activeSession = activeModel.listen(TajweedSessionOptions(endOnSilenceMs = 1500))
                session = activeSession
                js("onQuranLabStatus", "التسجيل شغّال الآن 🎙️ اقرئي، وسيظهر النص المسموع هنا.")
                activeSession.chunks.collect { chunk ->
                    val heard = chunk.decoded.text
                    js("onQuranLabHeard", heard.ifBlank { "الميكروفون مفتوح، في انتظار التلاوة..." })
                    if (heard.isNotBlank()) {
                        js("onQuranLabStatus", if (chunk.decoded.isFinal) "انتهى التسجيل تلقائياً بعد الصمت." else "الميكروفون يستقبل التلاوة ويعالجها...")
                    }
                }
                session = null
                startInProgress = false
                js("onQuranLabStatus", "انتهت جلسة التسميع. يمكنك البدء مرة أخرى.")
            } catch (e: TajweedError) {
                session = null
                startInProgress = false
                pendingStart = false
                if (e.code != "cancelled") js("onQuranLabStatus", "${e.code}: ${e.message}. ${e.fix}")
            } catch (e: Exception) {
                session = null
                startInProgress = false
                pendingStart = false
                js("onQuranLabStatus", "تعذر تشغيل الميكروفون: ${e.message ?: "خطأ غير معروف"}")
            }
        }
    }

    @JavascriptInterface
    fun stopListening() {
        pendingStart = false
        startInProgress = false
        scope.launch {
            try { session?.finish() } catch (_: Exception) { }
            session = null
            js("onQuranLabStatus", "تم إيقاف التسميع.")
        }
    }

    fun close() {
        try { session?.cancel() } catch (_: Exception) { }
        scope.cancel()
    }
}
