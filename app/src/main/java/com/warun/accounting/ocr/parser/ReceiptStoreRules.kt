package com.warun.accounting.ocr.parser

data class ReceiptStoreRule(
    val canonicalName: String,
    val aliases: Set<String>
)

object ReceiptStoreRules {
    val defaults: List<ReceiptStoreRule> = listOf(
        ReceiptStoreRule("バロー", setOf("バロー", "valor", "valar")),
        ReceiptStoreRule("トキノ屋", setOf("トキノ屋")),
        ReceiptStoreRule("ピアゴ", setOf("ピアゴ")),
        ReceiptStoreRule("アミカ", setOf("アミカ")),
        ReceiptStoreRule("サカツ", setOf("サカツ")),
        ReceiptStoreRule("まるみや酒店", setOf("まるみや酒店")),
        ReceiptStoreRule("中島酒店", setOf("中島酒店")),
        ReceiptStoreRule("ドラッグアオキ", setOf("ドラッグアオキ", "ドラックアオキ")),
        ReceiptStoreRule("DCMカーマ", setOf("dcmカーマ", "dcm カーマ")),
        ReceiptStoreRule("PROsite", setOf("prosite", "pro site")),
        ReceiptStoreRule("ENEOS", setOf("eneos"))
    )
}
