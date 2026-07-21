package com.warun.accounting.ocr

sealed class ReceiptOcrException(message: String, cause: Throwable? = null) : Exception(message, cause)

class ReceiptOcrImageNotFoundException : ReceiptOcrException("撮影画像が見つかりません")

class ReceiptOcrImageUnreadableException(cause: Throwable) :
    ReceiptOcrException("撮影画像を読み込めません", cause)

class ReceiptOcrRecognitionException(cause: Throwable) :
    ReceiptOcrException("文字を認識できませんでした", cause)
