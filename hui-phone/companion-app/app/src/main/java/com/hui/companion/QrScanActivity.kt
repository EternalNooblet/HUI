package com.hui.companion

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
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
    // BarcodeScanning.getClient(), not getInstance() (that was the
    // bundled 17.3 com.google.mlkit:barcode-scanning artifact).
    private val scanner: BarcodeScanner = BarcodeScanning.getClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pv = PreviewView(this)
        setContentView(pv)

        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(pv.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
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
                .addOnFailureListener { }
                .addOnCompleteListener { imageProxy.close() }
        } else {
            imageProxy.close()
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
