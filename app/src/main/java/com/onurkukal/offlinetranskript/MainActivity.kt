package com.onurkukal.offlinetranskript

import android.content.ContentValues
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.onurkukal.offlinetranskript.databinding.ActivityMainBinding
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var selectedAudioUri: Uri? = null
    private var voskModel: Model? = null

    private val pickAudioLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            selectedAudioUri = uri
            binding.tvSelectedFile.text = "Seçilen dosya: ${queryDisplayName(uri) ?: uri.lastPathSegment ?: "-"}"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        LibVosk.setLogLevel(LogLevel.INFO)
        binding.btnTranscribe.isEnabled = false
        binding.btnSaveTxt.isEnabled = false

        binding.btnPickAudio.setOnClickListener {
            pickAudioLauncher.launch(arrayOf("audio/*"))
        }

        binding.btnTranscribe.setOnClickListener {
            val uri = selectedAudioUri
            if (uri == null) {
                toast("Önce ses dosyası seçin")
                return@setOnClickListener
            }
            transcribeOffline(uri)
        }

        binding.btnSaveTxt.setOnClickListener {
            saveTranscriptToDownloads(binding.etTranscript.text.toString())
        }

        loadModelIfPresent()
    }

    private fun loadModelIfPresent() {
        lifecycleScope.launch {
            val modelDir = File(filesDir, "model-tr")
            if (!modelDir.exists()) {
                binding.tvModelStatus.text = "Model bulunamadı.\n\nKurulum: proje içindeki README dosyasındaki linkten Türkçe Vosk modelini indirip içeriğini telefonunuzda uygulamanın dahili klasörüne veya Android Studio projesinde app/src/main/assets/model-tr klasörüne ekleyin.\n\nNot: Bu paket model dosyasını boyut nedeniyle içermiyor."
                binding.btnTranscribe.isEnabled = false
                return@launch
            }

            try {
                withContext(Dispatchers.IO) {
                    voskModel?.close()
                    voskModel = Model(modelDir.absolutePath)
                }
                binding.tvModelStatus.text = "Model hazır: ${modelDir.absolutePath}"
                binding.btnTranscribe.isEnabled = true
            } catch (e: Exception) {
                binding.tvModelStatus.text = "Model yüklenemedi: ${e.message}"
                binding.btnTranscribe.isEnabled = false
            }
        }
    }

    private fun transcribeOffline(uri: Uri) {
        val model = voskModel
        if (model == null) {
            toast("Önce model klasörünü ekleyin")
            return
        }

        lifecycleScope.launch {
            setBusy(true)
            try {
                val result = withContext(Dispatchers.IO) {
                    val decoded = AudioDecoder.decodeToMono16BitPcm(contentResolver, uri)
                    val recognizer = Recognizer(model, decoded.sampleRate.toFloat())
                    val chunkSize = 4000
                    var offset = 0
                    while (offset < decoded.pcmData.size) {
                        val remaining = decoded.pcmData.size - offset
                        val len = minOf(chunkSize, remaining)
                        recognizer.acceptWaveForm(decoded.pcmData, offset, len)
                        offset += len
                    }
                    val finalJson = recognizer.finalResult
                    recognizer.close()
                    parseTextFromVoskJson(finalJson)
                }
                binding.etTranscript.setText(result.ifBlank { "Metin üretilemedi." })
                binding.btnSaveTxt.isEnabled = result.isNotBlank()
            } catch (e: Exception) {
                binding.etTranscript.setText("Hata: ${e.message}")
            } finally {
                setBusy(false)
            }
        }
    }

    private fun parseTextFromVoskJson(json: String): String {
        return try {
            JSONObject(json).optString("text", "")
        } catch (_: Exception) {
            json
        }
    }

    private fun saveTranscriptToDownloads(text: String) {
        if (text.isBlank()) {
            toast("Kaydedilecek transkript yok")
            return
        }
        val fileName = "transkript_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.txt"
        val resolver = contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = resolver.insert(MediaStore.Files.getContentUri("external"), values)
        if (uri == null) {
            toast("Dosya oluşturulamadı")
            return
        }
        resolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
        toast("TXT kaydedildi: Downloads/$fileName")
    }

    private fun queryDisplayName(uri: Uri): String? {
        contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                if (index >= 0) return cursor.getString(index)
            }
        }
        return null
    }

    private fun setBusy(busy: Boolean) {
        binding.progressBar.visibility = if (busy) android.view.View.VISIBLE else android.view.View.GONE
        binding.btnPickAudio.isEnabled = !busy
        binding.btnTranscribe.isEnabled = !busy && voskModel != null
        binding.btnSaveTxt.isEnabled = !busy && binding.etTranscript.text?.isNotBlank() == true
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}

data class DecodedAudio(val sampleRate: Int, val pcmData: ByteArray)

object AudioDecoder {
    fun decodeToMono16BitPcm(contentResolver: android.content.ContentResolver, uri: Uri): DecodedAudio {
        val extractor = MediaExtractor()
        val afd = contentResolver.openAssetFileDescriptor(uri, "r") ?: throw IOException("Dosya açılamadı")
        extractor.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)

        var audioTrackIndex = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val trackFormat = extractor.getTrackFormat(i)
            val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                audioTrackIndex = i
                format = trackFormat
                break
            }
        }
        if (audioTrackIndex == -1 || format == null) {
            extractor.release()
            throw IOException("Ses track bulunamadı")
        }

        extractor.selectTrack(audioTrackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: throw IOException("MIME yok")
        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        val sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 16000
        val channelCount = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1

        val bufferInfo = MediaCodec.BufferInfo()
        val output = ByteArrayOutputStream()
        var inputDone = false
        var outputDone = false

        while (!outputDone) {
            if (!inputDone) {
                val inputIndex = codec.dequeueInputBuffer(10_000)
                if (inputIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputIndex) ?: throw IOException("Input buffer yok")
                    val sampleSize = extractor.readSampleData(inputBuffer, 0)
                    if (sampleSize < 0) {
                        codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        val presentationTimeUs = extractor.sampleTime
                        codec.queueInputBuffer(inputIndex, 0, sampleSize, presentationTimeUs, 0)
                        extractor.advance()
                    }
                }
            }

            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
            when {
                outputIndex >= 0 -> {
                    val outputBuffer = codec.getOutputBuffer(outputIndex) ?: throw IOException("Output buffer yok")
                    val chunk = ByteArray(bufferInfo.size)
                    outputBuffer.position(bufferInfo.offset)
                    outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                    outputBuffer.get(chunk)

                    val monoChunk = if (channelCount > 1) stereoToMonoPcm16(chunk, channelCount) else chunk
                    output.write(monoChunk)

                    codec.releaseOutputBuffer(outputIndex, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true
                    }
                }
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    // ignore dynamic format change
                }
            }
        }

        codec.stop()
        codec.release()
        extractor.release()
        afd.close()

        return DecodedAudio(sampleRate = sampleRate, pcmData = output.toByteArray())
    }

    private fun stereoToMonoPcm16(input: ByteArray, channelCount: Int): ByteArray {
        if (channelCount <= 1) return input
        val inBuffer = ByteBuffer.wrap(input).order(ByteOrder.LITTLE_ENDIAN)
        val outBuffer = ByteArrayOutputStream()
        while (inBuffer.remaining() >= channelCount * 2) {
            var sum = 0
            for (c in 0 until channelCount) {
                sum += inBuffer.short.toInt()
            }
            val avg = (sum / channelCount).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            outBuffer.write(byteArrayOf((avg.toInt() and 0xFF).toByte(), ((avg.toInt() shr 8) and 0xFF).toByte()))
        }
        return outBuffer.toByteArray()
    }
}
