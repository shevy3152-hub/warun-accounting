package com.warun.accounting.ui.viewmodel

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.warun.accounting.data.AccountingRepository
import com.warun.accounting.data.local.ElectronicSubmissionRecord
import com.warun.accounting.data.local.ElectronicSubmissionStatus
import com.warun.accounting.data.local.MonthlySubmission
import com.warun.accounting.data.submission.CompletedElectronicSubmissionGeneration
import com.warun.accounting.data.submission.ElectronicSubmissionGenerationCoordinator
import com.warun.accounting.data.submission.ElectronicSubmissionGenerationResult
import com.warun.accounting.data.submission.ElectronicSubmissionRepository
import com.warun.accounting.export.MonthlyExportArtifacts
import com.warun.accounting.export.ExportCacheCleanupResult
import com.warun.accounting.export.ExportCacheContract
import com.warun.accounting.export.MonthlyExportSafGateway
import com.warun.accounting.export.ReceiptPdfArtifactResult
import com.warun.accounting.export.SafExportCopyResult
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import java.time.YearMonth
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SubmissionArtifact(
    val fileName: String,
    val mimeType: String,
    val file: File
)

data class ActiveElectronicSubmissionGeneration(
    val recordId: String,
    val targetMonth: String,
    val artifacts: MonthlyExportArtifacts,
    val files: List<SubmissionArtifact>,
    val hasReceiptPdf: Boolean
)

data class ElectronicSubmissionUiState(
    val selectedMonth: YearMonth = YearMonth.now(),
    val storeName: String? = null,
    val isPreparing: Boolean = true,
    val isGenerating: Boolean = false,
    val activeGeneration: ActiveElectronicSubmissionGeneration? = null,
    val electronicHistory: List<ElectronicSubmissionRecord> = emptyList(),
    val paperHistory: List<MonthlySubmission> = emptyList(),
    val noteDrafts: Map<String, String> = emptyMap(),
    val noteSavingIds: Set<String> = emptySet(),
    val submittingIds: Set<String> = emptySet(),
    val savingFileName: String? = null,
    val message: String? = null,
    val isError: Boolean = false
) {
    val canGenerate: Boolean get() = !isPreparing && !isGenerating && savingFileName == null
}

sealed interface ElectronicSubmissionUiEffect {
    data class ChooseSaveDestination(val artifact: SubmissionArtifact) :
        ElectronicSubmissionUiEffect

    data class ShareFiles(
        val files: List<File>,
        val targetMonth: String? = null,
        val storeName: String? = null
    ) : ElectronicSubmissionUiEffect
}

internal suspend fun <T> runElectronicSubmissionIo(
    dispatcher: CoroutineDispatcher,
    block: () -> T
): T = withContext(dispatcher) { block() }

@HiltViewModel
class ElectronicSubmissionViewModel internal constructor(
    private val savedStateHandle: SavedStateHandle,
    private val repository: ElectronicSubmissionRepository,
    private val observeStoreName: () -> Flow<String?> = { flowOf(null) },
    private val prepareExports: suspend () -> ExportCacheCleanupResult,
    private val generateSubmission: suspend (YearMonth) -> ElectronicSubmissionGenerationResult,
    private val copyToSaf: (File, Uri) -> SafExportCopyResult,
    private val now: () -> Long,
    private val ioDispatcher: CoroutineDispatcher
) : ViewModel() {
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        repository: ElectronicSubmissionRepository,
        accountingRepository: AccountingRepository,
        coordinator: ElectronicSubmissionGenerationCoordinator,
        safGateway: MonthlyExportSafGateway
    ) : this(
        savedStateHandle = savedStateHandle,
        repository = repository,
        observeStoreName = {
            accountingRepository.observeAppSettings().map { settings ->
                settings?.storeName?.trim()?.takeIf { it.isNotEmpty() }
            }
        },
        prepareExports = coordinator::prepare,
        generateSubmission = coordinator::generate,
        copyToSaf = safGateway::copy,
        now = System::currentTimeMillis,
        ioDispatcher = Dispatchers.IO
    )

    private val initialMonth = savedStateHandle.get<String>(SelectedMonthKey)
        ?.let { runCatching { YearMonth.parse(it) }.getOrNull() }
        ?: YearMonth.now()
    private val _state = MutableStateFlow(ElectronicSubmissionUiState(selectedMonth = initialMonth))
    val state: StateFlow<ElectronicSubmissionUiState> = _state.asStateFlow()

    private val effectChannel = Channel<ElectronicSubmissionUiEffect>(Channel.BUFFERED)
    val effects: Flow<ElectronicSubmissionUiEffect> = effectChannel.receiveAsFlow()

    init {
        viewModelScope.launch {
            val cleanup = try {
                prepareExports()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                ExportCacheCleanupResult(failedEntries = listOf("submission-exports"))
            }
            _state.update {
                it.copy(
                    isPreparing = false,
                    message = if (cleanup.succeeded) it.message else
                        "期限切れの提出一時ファイルを一部清掃できませんでした。",
                    isError = !cleanup.succeeded || it.isError
                )
            }
        }
        viewModelScope.launch {
            combine(repository.observeRecords(), repository.observePaperRecords()) { electronic, paper ->
                electronic to paper
            }.collect { (electronic, paper) ->
                _state.update { current ->
                    val drafts = electronic.associate { record ->
                        record.id to (current.noteDrafts[record.id] ?: record.note.orEmpty())
                    }
                    current.copy(
                        electronicHistory = electronic,
                        paperHistory = paper,
                        noteDrafts = drafts
                    )
                }
            }
        }
        viewModelScope.launch {
            observeStoreName().collect { storeName ->
                _state.update { it.copy(storeName = storeName) }
            }
        }
    }

    fun selectMonth(month: YearMonth) {
        if (_state.value.isGenerating || _state.value.savingFileName != null ||
            month == _state.value.selectedMonth
        ) return
        savedStateHandle[SelectedMonthKey] = month.toString()
        _state.update {
            it.copy(selectedMonth = month, activeGeneration = null, message = null, isError = false)
        }
    }

    fun generate() {
        val selectedMonth = _state.value.selectedMonth
        if (!_state.value.canGenerate) return
        _state.update { it.copy(isGenerating = true, message = null, isError = false) }
        viewModelScope.launch {
            when (val result = generateSubmission(selectedMonth)) {
                is ElectronicSubmissionGenerationResult.Success -> {
                    val active = result.generation.toActiveGeneration()
                    val noEvidence = result.generation.receiptPdf is ReceiptPdfArtifactResult.NoEvidence
                    _state.update {
                        it.copy(
                            isGenerating = false,
                            activeGeneration = active,
                            message = if (noEvidence) {
                                "日報Excelと支出明細Excelを作成しました。" +
                                    "対象月に保存済みEvidenceがないため、レシートPDFは作成していません。"
                            } else {
                                "提出ファイルを作成しました。内容を確認して保存または共有してください。"
                            },
                            isError = false
                        )
                    }
                }
                is ElectronicSubmissionGenerationResult.SnapshotFailure -> _state.update {
                    it.copy(
                        isGenerating = false,
                        message = "月次データの整合性を確認できないため、ファイルを作成しませんでした。",
                        isError = true
                    )
                }
                is ElectronicSubmissionGenerationResult.ArtifactFailure -> _state.update {
                    it.copy(
                        isGenerating = false,
                        message = "提出ファイルを安全に作成できませんでした。元データは変更していません。",
                        isError = true
                    )
                }
                is ElectronicSubmissionGenerationResult.HistoryFailure -> _state.update {
                    it.copy(
                        isGenerating = false,
                        message = if (result.cleanupSucceeded) {
                            "提出履歴を保存できなかったため、今回の一時ファイルを破棄しました。"
                        } else {
                            "提出履歴を保存できませんでした。一時ファイルの清掃も完了していません。"
                        },
                        isError = true
                    )
                }
            }
        }
    }

    fun requestSave(fileName: String) {
        if (_state.value.savingFileName != null) return
        val artifact = _state.value.activeGeneration?.files?.singleOrNull {
            it.fileName == fileName
        } ?: return
        effectChannel.trySend(ElectronicSubmissionUiEffect.ChooseSaveDestination(artifact))
    }

    fun save(artifact: SubmissionArtifact, destination: Uri) {
        val current = _state.value.activeGeneration?.files?.singleOrNull {
            it.file == artifact.file && it.fileName == artifact.fileName
        } ?: return
        if (_state.value.savingFileName != null) return
        _state.update { it.copy(savingFileName = current.fileName, message = null, isError = false) }
        viewModelScope.launch {
            when (runElectronicSubmissionIo(ioDispatcher) {
                copyToSaf(current.file, destination)
            }) {
                is SafExportCopyResult.Success -> _state.update {
                    it.copy(
                        savingFileName = null,
                        message = "${current.fileName}を選択した保存先へ保存しました。",
                        isError = false
                    )
                }
                is SafExportCopyResult.Failure -> _state.update {
                    it.copy(
                        savingFileName = null,
                        message = "${current.fileName}を保存できませんでした。元データは変更していません。",
                        isError = true
                    )
                }
            }
        }
    }

    fun requestShare() {
        if (_state.value.savingFileName != null) return
        val generation = _state.value.activeGeneration
        val files = generation?.files?.map { it.file }.orEmpty()
        if (generation != null && generation.artifacts.totalBytes > ExportCacheContract.MyKomonHardTotalBytesLimit) {
            _state.update {
                it.copy(
                    message = "提出ファイルの合計サイズがMyKomonの100MB上限を超えています。" +
                        "不要なファイルを含めず、税理士へ別の受け渡し方法を確認してください。",
                    isError = true
                )
            }
            return
        }
        if (files.isNotEmpty() && generation != null) {
            effectChannel.trySend(
                ElectronicSubmissionUiEffect.ShareFiles(
                    files = files,
                    targetMonth = generation.targetMonth,
                    storeName = _state.value.storeName
                )
            )
        }
    }

    fun updateNoteDraft(id: String, input: String) {
        if (id !in _state.value.electronicHistory.map { it.id }) return
        _state.update {
            it.copy(noteDrafts = it.noteDrafts + (id to input.take(NoteMaxLength)))
        }
    }

    fun saveNote(id: String) {
        if (id in _state.value.noteSavingIds) return
        val record = _state.value.electronicHistory.singleOrNull { it.id == id } ?: return
        val normalized = normalizeNote(_state.value.noteDrafts[id].orEmpty())
        if (normalized == record.note) return
        _state.update { it.copy(noteSavingIds = it.noteSavingIds + id) }
        viewModelScope.launch {
            val success = runCatching {
                repository.updateNote(id, normalized, now())
            }.getOrDefault(false)
            _state.update {
                it.copy(
                    noteSavingIds = it.noteSavingIds - id,
                    message = if (success) "備考を保存しました。" else "備考を保存できませんでした。",
                    isError = !success
                )
            }
        }
    }

    fun markSubmitted(id: String) {
        val record = _state.value.electronicHistory.singleOrNull { it.id == id } ?: return
        if (record.status != ElectronicSubmissionStatus.NotSubmitted ||
            id in _state.value.submittingIds
        ) return
        _state.update { it.copy(submittingIds = it.submittingIds + id) }
        viewModelScope.launch {
            val submittedAt = now()
            val success = runCatching {
                repository.markSubmitted(id, submittedAt, submittedAt)
            }.getOrDefault(false)
            _state.update {
                it.copy(
                    submittingIds = it.submittingIds - id,
                    message = if (success) {
                        "MyKomonへの提出完了をローカル記録しました。"
                    } else {
                        "手動提出記録を更新できませんでした。すでに記録済みか確認してください。"
                    },
                    isError = !success
                )
            }
        }
    }

    fun dismissMessage() {
        _state.update { it.copy(message = null, isError = false) }
    }

    private fun CompletedElectronicSubmissionGeneration.toActiveGeneration():
        ActiveElectronicSubmissionGeneration {
        val generatedFiles = listOfNotNull(
            SubmissionArtifact(
                artifacts.dailyReportXlsx.name,
                XlsxMimeType,
                artifacts.dailyReportXlsx
            ),
            SubmissionArtifact(
                artifacts.expenseDetailXlsx.name,
                XlsxMimeType,
                artifacts.expenseDetailXlsx
            ),
            artifacts.receiptPdf?.let { SubmissionArtifact(it.name, PdfMimeType, it) }
        )
        return ActiveElectronicSubmissionGeneration(
            recordId = record.id,
            targetMonth = record.targetMonth,
            artifacts = artifacts,
            files = generatedFiles,
            hasReceiptPdf = artifacts.receiptPdf != null
        )
    }

    companion object {
        const val NoteMaxLength = 500
        const val XlsxMimeType =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        const val PdfMimeType = "application/pdf"
        private const val SelectedMonthKey = "electronic_submission_selected_month"

        internal fun normalizeNote(input: String): String? = input
            .trim()
            .replace(Regex("\\s+"), " ")
            .take(NoteMaxLength)
            .ifEmpty { null }
    }
}
