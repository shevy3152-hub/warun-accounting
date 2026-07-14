package com.warun.accounting.future

/**
 * Contracts for planned features. These interfaces are intentionally not bound
 * in Hilt yet, so no camera, OCR, cloud, export, MCP, or AI behavior is active.
 */

enum class PlannedFeature {
    ReceiptCamera,
    ReceiptOcr,
    ReceiptImageStorage,
    BankbookImageStorage,
    SupabaseSync,
    AccountantInvitation,
    CsvExport,
    PdfExport,
    ZipExport,
    McpIntegration,
    ExternalAiIntegration
}

data class PlannedFeatureDescriptor(
    val feature: PlannedFeature,
    val displayName: String,
    val enabled: Boolean = false
)

object PlannedFeatureRegistry {
    val features: List<PlannedFeatureDescriptor> = listOf(
        PlannedFeatureDescriptor(PlannedFeature.ReceiptCamera, "CameraXによるレシート撮影"),
        PlannedFeatureDescriptor(PlannedFeature.ReceiptOcr, "ML KitによるOCR"),
        PlannedFeatureDescriptor(PlannedFeature.ReceiptImageStorage, "レシート画像保存"),
        PlannedFeatureDescriptor(PlannedFeature.BankbookImageStorage, "通帳画像保存"),
        PlannedFeatureDescriptor(PlannedFeature.SupabaseSync, "Supabase同期"),
        PlannedFeatureDescriptor(PlannedFeature.AccountantInvitation, "税理士ユーザー招待"),
        PlannedFeatureDescriptor(PlannedFeature.CsvExport, "CSV出力"),
        PlannedFeatureDescriptor(PlannedFeature.PdfExport, "PDF出力"),
        PlannedFeatureDescriptor(PlannedFeature.ZipExport, "ZIP出力"),
        PlannedFeatureDescriptor(PlannedFeature.McpIntegration, "MCP連携"),
        PlannedFeatureDescriptor(PlannedFeature.ExternalAiIntegration, "外部AI API連携")
    )
}

enum class BusinessImageKind {
    Receipt,
    Bankbook
}

data class ImageCaptureRequest(
    val reportDate: String,
    val kind: BusinessImageKind,
    val reportId: String? = null
)

data class ImageReference(
    val id: String,
    val kind: BusinessImageKind,
    val localUri: String,
    val reportDate: String,
    val reportId: String? = null,
    val createdAt: Long
)

interface ReceiptCameraGateway {
    suspend fun captureReceipt(request: ImageCaptureRequest): ImageReference
}

interface BusinessImageStorageGateway {
    suspend fun saveImage(reference: ImageReference): ImageReference
    suspend fun deleteImage(imageId: String)
}

data class ReceiptOcrRequest(
    val imageId: String,
    val localUri: String,
    val engine: ReceiptOcrEngine = ReceiptOcrEngine.MlKitTextRecognition
)

enum class ReceiptOcrEngine {
    MlKitTextRecognition
}

data class ReceiptOcrDraft(
    val imageId: String,
    val engine: ReceiptOcrEngine,
    val dateCandidates: List<String>,
    val storeNameCandidates: List<String>,
    val totalAmountCandidates: List<Long>,
    val taxAmountCandidates: List<Long>,
    val registrationNumberCandidates: List<String>,
    val rawText: String,
    val requiresUserConfirmation: Boolean = true
)

interface ReceiptOcrGateway {
    suspend fun readReceipt(request: ReceiptOcrRequest): ReceiptOcrDraft
}

data class ReceiptConfirmationInput(
    val imageId: String,
    val reportDate: String?,
    val storeName: String?,
    val totalAmount: Long?,
    val taxAmount: Long?,
    val registrationNumber: String?,
    val expenseCategory: String?,
    val confirmedBy: String?,
    val confirmedAt: Long
)

data class ConfirmedReceiptReference(
    val id: String,
    val imageId: String,
    val reportDate: String?,
    val totalAmount: Long?,
    val createdAt: Long
)

interface ReceiptReviewGateway {
    suspend fun saveAfterUserConfirmation(input: ReceiptConfirmationInput): ConfirmedReceiptReference
}

data class SyncRequest(
    val sinceUpdatedAt: Long? = null,
    val includeImages: Boolean = false
)

data class SyncResult(
    val syncedReports: Int,
    val syncedSettings: Boolean,
    val syncedImages: Int,
    val completedAt: Long
)

interface CloudSyncGateway {
    suspend fun pushLocalChanges(request: SyncRequest): SyncResult
    suspend fun pullRemoteChanges(request: SyncRequest): SyncResult
}

data class AccountantInvitationRequest(
    val storeName: String,
    val accountantEmail: String,
    val message: String? = null
)

data class AccountantInvitationResult(
    val invitationId: String,
    val sentAt: Long
)

interface AccountantInvitationGateway {
    suspend fun inviteAccountant(request: AccountantInvitationRequest): AccountantInvitationResult
}

enum class ExportFormat {
    Csv,
    Pdf,
    Zip
}

data class ReportExportRequest(
    val fromDate: String,
    val toDate: String,
    val formats: Set<ExportFormat>,
    val includeReceiptImages: Boolean = false,
    val includeBankbookImages: Boolean = false
)

data class ExportArtifactReference(
    val id: String,
    val format: ExportFormat,
    val localUri: String,
    val createdAt: Long
)

interface ReportExportGateway {
    suspend fun exportReports(request: ReportExportRequest): List<ExportArtifactReference>
}

data class McpRequest(
    val action: String,
    val payloadJson: String
)

data class McpResult(
    val resultJson: String,
    val completedAt: Long
)

interface McpIntegrationGateway {
    suspend fun execute(request: McpRequest): McpResult
}

data class ExternalAiRequest(
    val purpose: String,
    val inputJson: String
)

data class ExternalAiResult(
    val outputJson: String,
    val completedAt: Long
)

/**
 * Reserved for later workflows. The first receipt OCR implementation should use
 * on-device ML Kit Text Recognition and must not call this gateway.
 */
interface ExternalAiGateway {
    suspend fun request(request: ExternalAiRequest): ExternalAiResult
}
