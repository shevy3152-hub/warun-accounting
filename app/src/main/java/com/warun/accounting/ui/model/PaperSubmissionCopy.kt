package com.warun.accounting.ui.model

internal object PaperSubmissionCopy {
    const val ElectronicSendNotice =
        "この操作では電子ファイルの生成、メール送信、税理士への電子送信は行いません。"
    const val RecordTimingNotice =
        "実際に紙資料を税理士へ渡した後だけ、紙提出済みとして記録してください。"

    fun statusLabel(recorded: Boolean): String =
        if (recorded) "紙提出済み（記録）" else "紙提出未記録"

    fun confirmationMessage(targetMonth: String): String =
        "$targetMonth の紙資料を税理士へ渡したことを、この端末内だけに記録します。" +
            "電子ファイルの生成や送信は行われません。"
}
