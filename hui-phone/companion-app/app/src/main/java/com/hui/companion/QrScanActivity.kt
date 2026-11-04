package com.hui.companion

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
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
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.barcode.common.Barcode
import java.util.concurrent.Executors

class QrScanActivity : AppCompatActivity() {
    private var done = false
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    // ML Kit 18.3.1 (play-services-mlkit-barcode-scanning): factory is
    // BarcodeScanning.getClient(). First use downloads the barcode model
    // via Play services; failures are logged so they surface in logcat.
    private val scanner: BarcodeScanner = BarcodeScanning.getClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pv = PreviewView(this)
        setContentView(pv)

        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(pv.surfaceProvider) }
            // Dense QRs need the highest resolution the camera offers —
            // default analysis resolution blurs the modules together.
            val analysisBuilder = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
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
                finishWithResult(null)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun process(imageProxy: ImageProxy) {
        val media = imageProxy.image
        if (media != null && !done) {
            val input = InputImage.fromMediaImage(media, imageProxy.imageInfo.rotationDegrees)
            scanner.process(input)
                .addOnSuccessListener { barcodes ->
                    if (!done) {
                        for (b in barcodes) {
                            if (b.valueType == Barcode.TYPE_URL || !b.rawValue.isNullOrEmpty()) {
                                val raw = b.rawValue
                                if (!raw.isNullOrEmpty()) {
                                    done = true
                                    setResult(Activity.RESULT_OK, Intent().putExtra("qr", raw))
                                    finish()
                                    providerUnbind()
                                    return@addOnSuccessListener
                                }
                            }
                        }
                    }
                }
                .addOnFailureListener { e ->
                    android.util.Log.w("QrScan", "Barcode scan failed", e)
                }
                .addOnCompleteListener { imageProxy.close() }
        } else {
            imageProxy.close()
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

    private fun providerUnbind() {
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        scanner.close()
    }
}
