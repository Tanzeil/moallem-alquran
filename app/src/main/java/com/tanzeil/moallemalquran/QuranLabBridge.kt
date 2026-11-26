package com.tanzeil.moallemalquran

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.util.Base64
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

/**
 * Single Android-to-WebView entry point for the recitation feature.
 * UI status is always reported to the page; no tap is treated as proof
 * that audio capture started until model.listen() successfully returns.
 */
class QuranLabBridge(context: Context, private val webView: WebView) {
    private val activity = context as Activity
    private val appContext = context.applicationContext
    private val scope = MainScope()
    private val key: String = try {
        String(Base64.decode(BuildConfig.QURANLAB_KEY_B64, Base64.DEFAULT), Charsets.UTF_8)
    } catch (_: Exception) {
        ""
    }

    private var client: TajweedClient? = null
    @Volatile private var model: TajweedModel? = null
    @Volatile private var session: TajweedSession? = null
    @Volatile private var loadingModel = false
    @Volatile private var pendingStart = false
    @Volatile private var startInProgress = false
    @Volatile private var closed = false

    private fun js(callback: String, message: String) {
        val safe = JSONObject.quote(message)
        webView.post {
            if (!closed) webView.evaluateJavascript("window.$callback && window.$callback($safe)", null)
        }
    }

    @JavascriptInterface
    fun setTargetAyah(surah: Int, ayah: Int) {
        // The selected verse is kept by the page; live recognition itself is continuous.
    }

    @JavascriptInterface
    fun prepareModel() {
        if (closed || model != null || loadingModel) return
        if (key.isBlank()) {
            js("onQuranLabStatus", "مفتاح خدمة التسميع غير مضاف إلى إعدادات البناء. لا يمكن تشغيل التعرف الصوتي بهذه النسخة.")
            return
        }

        loadingModel = true
        scope.launch {
            try {
                js("onQuranLabStatus", "جاري تجهيز نموذج التسميع لأول مرة. يلزم اتصال بالإنترنت، أبقي التطبيق مفتوحاً...")
                val activeClient = client ?: TajweedClient(appContext, key).also { client = it }
                activeClient.prefetch { done, total ->
                    val doneMb = done / (1024 * 1024)
                    val totalMb = total / (1024 * 1024)
                    js("onQuranLabStatus", "جاري تنزيل النموذج: $doneMb / $totalMb ميجابايت")
                }
                js("onQuranLabStatus", "تم تنزيل النموذج. جاري تهيئته...")
                model = activeClient.model()
                loadingModel = false
                js("onQuranLabStatus", "النموذج جاهز. اضغطي بدء التسميع.")
                if (pendingStart) beginListening()
            } catch (e: TajweedError) {
                loadingModel = false
                pendingStart = false
                startInProgress = false
                js("onQuranLabStatus", "خطأ النموذج [${e.code}]: ${e.message}. ${e.fix}")
            } catch (e: Exception) {
                loadingModel = false
                pendingStart = false
                startInProgress = false
                js("onQuranLabStatus", "فشل تجهيز النموذج: ${e.message ?: "خطأ غير معروف"}")
            }
        }
    }

    @JavascriptInterface
    fun startListening() {
        if (closed) return
        js("onQuranLabStatus", "وصل طلب بدء التسميع إلى Android. جاري التحقق من الميكروفون...")
        if (appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingStart = true
            js("onQuranLabStatus", "يلزم السماح للتطبيق باستخدام الميكروفون. سيبدأ بعد الموافقة.")
            activity.runOnUiThread { (activity as MainActivity).requestAudioPermissionForRecitation() }
            return
        }
        if (session != null || startInProgress) {
            js("onQuranLabStatus", "جلسة التسميع تعمل بالفعل. اقرئي أو اضغطي إيقاف.")
            return
        }
        pendingStart = true
        if (model == null) {
            prepareModel()
            if (loadingModel) js("onQuranLabStatus", "جاري تجهيز النموذج؛ سيبدأ الاستماع تلقائياً عند اكتماله.")
            return
        }
        beginListening()
    }

    private fun beginListening() {
        val activeModel = model ?: return
        if (closed || startInProgress || session != null || !pendingStart) return
        pendingStart = false
        startInProgress = true

        scope.launch {
            try {
                js("onQuranLabStatus", "جاري فتح الميكروفون...")
                val activeSession = activeModel.listen(
                    TajweedSessionOptions(endOnSilenceMs = 2500)
                )
                session = activeSession
                js("onQuranLabStatus", "التسجيل شغّال الآن 🎙️ اقرئي بصوت واضح. سيظهر النص هنا.")
                activeSession.chunks.collect { chunk ->
                    val text = chunk.decoded.text
                    if (text.isNotBlank()) {
                        js("onQuranLabHeard", text)
                        js("onQuranLabStatus", if (chunk.decoded.isFinal) {
                            "تم التعرف على مقطع من التلاوة. يمكنك مواصلة القراءة أو إيقاف التسميع."
                        } else {
                            "الميكروفون يستقبل الصوت ويحلله..."
                        })
                    } else {
                        js("onQuranLabHeard", "الميكروفون مفتوح؛ في انتظار التلاوة...")
                    }
                }
                js("onQuranLabStatus", "انتهت جلسة التسميع. اضغطي بدء للقراءة مرة أخرى.")
            } catch (e: TajweedError) {
                if (e.code != "cancelled") {
                    js("onQuranLabStatus", "خطأ في التسميع [${e.code}]: ${e.message}. ${e.fix}")
                }
            } catch (e: Exception) {
                js("onQuranLabStatus", "تعذر بدء/استمرار التسجيل: ${e.message ?: "خطأ غير معروف"}. تأكدي من إذن الميكروفون.")
            } finally {
                session = null
                startInProgress = false
                pendingStart = false
            }
        }
    }

    @JavascriptInterface
    fun stopListening() {
        pendingStart = false
        val activeSession = session
        if (activeSession == null) {
            js("onQuranLabStatus", if (loadingModel) "تم إلغاء بدء التسميع أثناء تجهيز النموذج." else "لا توجد جلسة تسجيل نشطة.")
            return
        }
        scope.launch {
            try {
                activeSession.finish()
            } catch (e: Exception) {
                js("onQuranLabStatus", "تم طلب إيقاف التسميع.")
            }
        }
    }

    fun close() {
        closed = true
        try {
            session?.cancel()
        } catch (_: Exception) {
        }
        scope.cancel()
    }
}
