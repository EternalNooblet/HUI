package com.hui.companion

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Bundle
import android.util.Log
import android.util.Size
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.camera.view.PreviewView
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.util.concurrent.Executors

class QrScanActivity : AppCompatActivity() {
    private var done = false
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val reader = QRCodeReader()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pv = PreviewView(this)
        setContentView(pv)

        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(pv.surfaceProvider) }
            // Dense QRs (70+ modules) need the highest resolution the
            // camera offers — lower analysis resolutions blur the modules
            // together and nothing decodes.
            val analysisBuilder = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            // Highest camera resolution when queryable; CameraX default otherwise.
            highestCameraResolution()?.let { analysisBuilder.setTargetResolution(it) }
            val analysis = analysisBuilder.build()
                .also { a ->
                    a.setAnalyzer(cameraExecutor) { imageProxy ->
                        process(imageProxy)
                    }
                }
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, cameraSelector, preview, analysis)
            } catch (e: Exception) {
                Log.e("QrScan", "Camera bind failed", e)
                finishWithResult(null)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun process(imageProxy: ImageProxy) {
        try {
            if (done) return
            val yPlane = imageProxy.planes[0].buffer
            val yuv = ByteArray(yPlane.remaining())
            yPlane.get(yuv)
            val width = imageProxy.width
            val height = imageProxy.height
            // Try both binarizers — Hybrid first, histogram as fallback.
            val text = decode(yuv, width, height, useHybrid = true)
                ?: decode(yuv, width, height, useHybrid = false)
            if (!text.isNullOrEmpty() && !done) {
                done = true
                Log.i("QrScan", "Decoded QR (${text.length} chars)")
                setResult(Activity.RESULT_OK, Intent().putExtra("qr", text))
                finish()
            }
        } catch (e: Exception) {
            Log.w("QrScan", "Decode attempt failed", e)
        } finally {
            imageProxy.close()
        }
    }

    private fun decode(yuv: ByteArray, width: Int, height: Int, useHybrid: Boolean): String? {
        val source = PlanarYUVLuminanceSource(yuv, width, height, 0, 0, width, height, false)
        val binarizer = if (useHybrid) HybridBinarizer(source) else GlobalHistogramBinarizer(source)
        val bitmap = BinaryBitmap(binarizer)
        return try {
            val hints = mapOf(DecodeHintType.TRY_HARDER to true)
            reader.decode(bitmap, hints).text
        } catch (e: Exception) {
            reader.reset()
            null
        }
    }

    /** Largest YUV output size this camera supports, or null if unknown. */
    private fun highestCameraResolution(): Size? {
        return try {
            val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: manager.cameraIdList.first()
            val map = manager.getCameraCharacteristics(cameraId)
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: return null
            map.getOutputSizes(ImageFormat.YUV_420_888)
                ?.maxByOrNull { it.width * it.height }
        } catch (e: Exception) {
            Log.w("QrScan", "Resolution query failed, using CameraX default", e)
            null
        }
    }

    private fun finishWithResult(raw: String?) {
        if (raw != null) setResult(Activity.RESULT_OK, Intent().putExtra("qr", raw))
        else setResult(Activity.RESULT_CANCELED)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
