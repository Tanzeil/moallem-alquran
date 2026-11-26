package com.tanzeil.moallemalquran

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.Manifest
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
    private var model: TajweedModel? = null
    private var session: TajweedSession? = null
    @Volatile private var targetSurah: Int? = null
    @Volatile private var targetAyah: Int? = null
    @Volatile private var startInProgress = false

    private fun js(name: String, value: String) {
        // JSON quoting safely escapes Arabic text, quotes, backslashes and newlines.
        val safe = JSONObject.quote(value)
        webView.post {
            webView.evaluateJavascript("window.$name && window.$name($safe)", null)
        }
    }

    @JavascriptInterface
    fun setTargetAyah(surah: Int, ayah: Int) {
        if (surah in 1..114 && ayah in 1..286) {
            targetSurah = surah
            targetAyah = ayah
        }
    }

    private fun editDistance(expected: IntArray, heard: List<Int>): Int {
        var previous = IntArray(heard.size + 1) { it }
        for (i in expected.indices) {
            val current = IntArray(heard.size + 1)
            current[0] = i + 1
            for (j in heard.indices) {
                val cost = if (expected[i] == heard[j]) 0 else 1
                current[j + 1] = minOf(
                    current[j] + 1,
                    previous[j + 1] + 1,
                    previous[j] + cost
                )
            }
            previous = current
        }
        return previous[heard.size]
    }

    @JavascriptInterface
    fun startListening() {
        js("onQuranLabStatus", "تم الضغط على زر التسميع. جاري فحص الميكروفون...")
        if (targetSurah == null || targetAyah == null) {
            targetSurah = 1
            targetAyah = 1
        }
        if (appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            js("onQuranLabStatus", "نحتاج إذن الميكروفون حتى يبدأ التسميع. سيظهر طلب السماح الآن.")
            (activity as MainActivity).requestAudioPermissionForRecitation()
            return
        }
        if (startInProgress || session != null) {
            js("onQuranLabStatus", "التسجيل شغّال بالفعل.")
            return
        }
        startInProgress = true
        scope.launch {
            try {
                if (key.isBlank()) {
                    js("onQuranLabStatus", "مفتاح Quran Lab غير مضاف إلى عملية البناء.")
                    return@launch
                }
                js("onQuranLabStatus", "جاري تجهيز نموذج التلاوة لأول مرة...")
                val activeClient = client ?: TajweedClient(appContext, key).also { client = it }
                val activeModel = model ?: activeClient.model().also { model = it }
                val surah = targetSurah
                val ayah = targetAyah
                if (surah == null || ayah == null) {
                    js("onQuranLabStatus", "اختاري آية من المصحف أولاً.")
                    return@launch
                }
                // Begin listening before fetching reference data for comparison.
                js("onQuranLabStatus", "بدأ التسجيل الآن 🎙️ اقرئي الآية بصوت واضح.")
                val activeSession = activeModel.listen(TajweedSessionOptions(endOnSilenceMs = 1500))
                session = activeSession
                val reference = activeModel.reference
                if (reference == null) {
                    try { activeSession.finish() } catch (_: Exception) { }
                    session = null
                    js("onQuranLabStatus", "بدأ التسجيل، لكن بيانات الآية المرجعية غير متاحة للمقارنة.")
                    return@launch
                }
                reference.fetch()
                val expected = reference.units(surah, ayah)
                var compared = false
                activeSession.chunks.collect { chunk ->
                    js("onQuranLabHeard", chunk.decoded.text)
                    if (chunk.decoded.isFinal && !compared) {
                        compared = true
                        val heardIds = chunk.decoded.tokens.map { it.id }
                        val differences = editDistance(expected, heardIds)
                        js("onQuranLabStatus", "انتهى التسميع.")
                        js(
                            "onQuranLabCompare",
                            "المقارنة الأولية: تم رصد $differences اختلافاً صوتياً محتملاً في الآية المحددة. هذه ليست حكماً نهائياً على الحفظ أو التجويد."
                        )
                    }
                }
                session = null
                startInProgress = false
            } catch (e: TajweedError) {
                try { session?.finish() } catch (_: Exception) { }
                session = null
                startInProgress = false
                js("onQuranLabStatus", "${e.code}: ${e.message}. ${e.fix}")
            } catch (e: Exception) {
                try { session?.finish() } catch (_: Exception) { }
                session = null
                startInProgress = false
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
