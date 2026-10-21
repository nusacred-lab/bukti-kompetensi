package id.nusacred.wallet

import android.content.Context
import android.os.SystemClock
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Frames remain on device and are discarded immediately after decoding. */
@Composable
fun QrScanner(context: Context, owner: LifecycleOwner, modifier: Modifier, onCode: (String) -> Unit, onError: () -> Unit) {
    val view = remember { PreviewView(context) }
    AndroidView(factory = { view }, modifier = modifier)
    DisposableEffect(view) {
        val worker = Executors.newSingleThreadExecutor()
        val closed = AtomicBoolean(false)
        val delivered = AtomicBoolean(false)
        val future = ProcessCameraProvider.getInstance(context)
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
        val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
        var last = 0L
        val reader = MultiFormatReader().apply { setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))) }
        analysis.setAnalyzer(worker) { frame ->
            try {
                val now = SystemClock.elapsedRealtime()
                if (!closed.get() && !delivered.get() && now - last >= 250) {
                    last = now
                    val plane = frame.planes[0]
                    val bytes = ByteArray(frame.width * frame.height)
                    val buffer = plane.buffer.duplicate()
                    val start = buffer.position()
                    for (y in 0 until frame.height) for (x in 0 until frame.width) {
                        bytes[y * frame.width + x] = buffer.get(start + y * plane.rowStride + x * plane.pixelStride)
                    }
                    val source = PlanarYUVLuminanceSource(bytes, frame.width, frame.height, 0, 0,
                        frame.width, frame.height, false)
                    val text = reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
                    if (text.length <= 8192 && delivered.compareAndSet(false, true)) {
                        ContextCompat.getMainExecutor(context).execute { if (!closed.get()) onCode(text) }
                    }
                }
            } catch (_: NotFoundException) { /* Keep scanning. */ }
            catch (_: Exception) { /* Malformed frames/codes do not leave the device. */ }
            finally { reader.reset(); frame.close() }
        }
        future.addListener({
            if (!closed.get()) try {
                future.get().bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (_: Exception) { onError() }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            closed.set(true)
            analysis.clearAnalyzer()
            if (future.isDone && !future.isCancelled) try { future.get().unbind(preview, analysis) } catch (_: Exception) { }
            worker.shutdownNow()
        }
    }
}
