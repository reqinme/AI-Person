package qinme.aiperson

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import java.util.Locale

/** 语音输入：按住说话，实时回填识别结果。 */
class SpeechInput(
    private val context: Context,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit
) {
    private var recognizer: SpeechRecognizer? = null
    private var listening = false

    fun available(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun start() {
        if (!available()) return
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) = Unit
                    override fun onBeginningOfSpeech() = Unit
                    override fun onRmsChanged(rmsdB: Float) = Unit
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEndOfSpeech() = Unit

                    override fun onError(error: Int) {
                        listening = false
                    }

                    override fun onResults(results: Bundle?) {
                        listening = false
                        val text = results
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                        if (!text.isNullOrBlank()) onFinal(text)
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val text = partialResults
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                        if (!text.isNullOrBlank()) onPartial(text)
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) = Unit
                })
            }
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        listening = true
        recognizer?.startListening(intent)
    }

    fun stop() {
        if (listening) {
            recognizer?.stopListening()
            listening = false
        }
    }

    fun destroy() {
        recognizer?.destroy()
        recognizer = null
    }
}

/** 朗读：系统 TTS，中文；音调与语速随人格自动微调。 */
object Speaker {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pitch = 1.0f
    private var rate = 1.0f

    fun init(context: Context) {
        if (tts != null) return
        tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                tts?.language = Locale.CHINA
            }
        }
    }

    /** 活泼的角色语调略高，粘人的语速略慢。 */
    fun tune(liveliness: Int, clinginess: Int) {
        pitch = (1.0f + (liveliness - 50) / 250f).coerceIn(0.8f, 1.25f)
        rate = (1.0f - (clinginess - 30) / 300f).coerceIn(0.85f, 1.15f)
        tts?.setPitch(pitch)
        tts?.setSpeechRate(rate)
    }

    fun speak(text: String) {
        if (!ready || text.isBlank()) return
        tts?.stop()
        tts?.setPitch(pitch)
        tts?.setSpeechRate(rate)
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "aiperson")
    }

    fun stop() {
        tts?.stop()
    }
}
