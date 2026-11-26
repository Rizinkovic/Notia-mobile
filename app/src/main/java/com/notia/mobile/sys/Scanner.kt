package com.notia.mobile.sys

import android.app.Activity
import android.net.Uri
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.notia.mobile.MainActivity

/**
 * Document scanner through Google Play services (the same kind of scanner as Notes on iPhone: automatic page detection, crop,
 * perspective fix, clean-up filters, several pages). It needs no camera permission; the pictures stay on the phone.
 */
object Scanner {
    const val MAX_PAGES = 30

    /** [onPages] gets the page pictures in order (empty when the user cancelled); [onFail] is called when the scanner cannot start. */
    fun scan(a: MainActivity, onPages: (List<Uri>) -> Unit, onFail: (String) -> Unit) {
        try {
            val opts = GmsDocumentScannerOptions.Builder()
                .setGalleryImportAllowed(true)
                .setPageLimit(MAX_PAGES)
                .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
                .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                .build()
            GmsDocumentScanning.getClient(opts).getStartScanIntent(a)
                .addOnSuccessListener { sender ->
                    a.startSender(sender) { code, data ->
                        if (code != Activity.RESULT_OK) { onPages(emptyList()); return@startSender }
                        val r = GmsDocumentScanningResult.fromActivityResultIntent(data)
                        onPages(r?.pages?.map { it.imageUri } ?: emptyList())
                    }
                }
                .addOnFailureListener { e -> onFail(e.message ?: "error") }
        } catch (e: Exception) { onFail(e.message ?: "error") }
    }
}
