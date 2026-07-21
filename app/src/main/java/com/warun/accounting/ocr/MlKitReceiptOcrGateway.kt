package com.warun.accounting.ocr

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.warun.accounting.camera.ReceiptImageStore
import com.warun.accounting.future.ReceiptOcrDraft
import com.warun.accounting.future.ReceiptOcrGateway
import com.warun.accounting.future.ReceiptOcrRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

@Singleton
class MlKitReceiptOcrGateway @Inject constructor(
    @ApplicationContext private val context: Context
) : ReceiptOcrGateway {
    private val imageStore = ReceiptImageStore(File(context.filesDir, "receipt-images/pending"))
    private val imageLocator = ReceiptOcrImageLocator(imageStore)
    private val recognizer by lazy {
        TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
    }

    override suspend fun readReceipt(request: ReceiptOcrRequest): ReceiptOcrDraft {
        val imageUri = imageLocator.locate(request)
        val inputImage = try {
            InputImage.fromFilePath(context, Uri.parse(imageUri))
        } catch (error: IOException) {
            throw ReceiptOcrImageUnreadableException(error)
        } catch (error: RuntimeException) {
            throw ReceiptOcrImageUnreadableException(error)
        }

        val recognizedText = try {
            suspendCancellableCoroutine<String> { continuation ->
                recognizer.process(inputImage)
                    .addOnSuccessListener { result ->
                        if (continuation.isActive) continuation.resume(result.text)
                    }
                    .addOnFailureListener { error ->
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                    .addOnCanceledListener {
                        continuation.cancel()
                    }
            }
        } catch (error: ReceiptOcrException) {
            throw error
        } catch (error: Exception) {
            throw ReceiptOcrRecognitionException(error)
        }

        return ReceiptOcrDraft(
            imageId = request.imageId,
            engine = request.engine,
            dateCandidates = emptyList(),
            storeNameCandidates = emptyList(),
            totalAmountCandidates = emptyList(),
            taxAmountCandidates = emptyList(),
            registrationNumberCandidates = emptyList(),
            rawText = recognizedText
        )
    }
}
