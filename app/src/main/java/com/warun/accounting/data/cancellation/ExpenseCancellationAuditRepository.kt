package com.warun.accounting.data.cancellation

import com.warun.accounting.data.AccountingRepository
import com.warun.accounting.data.local.ExpenseCancellationDao
import com.warun.accounting.data.local.ExpenseCancellationRecord
import com.warun.accounting.data.local.ExpenseEvidenceRecord
import com.warun.accounting.data.local.ExpensePrepaidLinkRecord
import com.warun.accounting.data.local.ExpenseRecord
import com.warun.accounting.data.local.PrepaidAccountRecord
import com.warun.accounting.data.local.PrepaidTransactionRecord
import com.warun.accounting.data.local.PrepaidTransactionType
import com.warun.accounting.data.prepaid.PrepaidRepository
import com.warun.accounting.util.PaymentMethodPrepaid
import com.warun.accounting.util.normalizePaymentMethod
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

data class ExpenseCancellationAuditItem(
    val expense: ExpenseRecord,
    val cancellation: ExpenseCancellationRecord,
    val prepaidLink: ExpensePrepaidLinkRecord?,
    val originalPurchase: PrepaidTransactionRecord?,
    val reversal: PrepaidTransactionRecord?,
    val prepaidAccount: PrepaidAccountRecord?,
    val evidence: List<ExpenseEvidenceRecord>,
    val isPrepaidCancellation: Boolean,
    val hasCompleteLedgerRelation: Boolean
)

private data class CancellationAuditCore(
    val expenses: List<ExpenseRecord>,
    val cancellations: List<ExpenseCancellationRecord>,
    val links: List<ExpensePrepaidLinkRecord>
)

private data class CancellationAuditReferences(
    val transactions: List<PrepaidTransactionRecord>,
    val accounts: List<PrepaidAccountRecord>,
    val evidence: List<ExpenseEvidenceRecord>
)

class ExpenseCancellationAuditRepository @Inject constructor(
    private val accountingRepository: AccountingRepository,
    private val prepaidRepository: PrepaidRepository,
    private val cancellationDao: ExpenseCancellationDao
) {
    fun observeByExpenseDate(expenseDate: String): Flow<List<ExpenseCancellationAuditItem>> {
        val core = combine(
            accountingRepository.observeCancelledExpenseRecordsForAuditByDate(expenseDate),
            cancellationDao.observeByExpenseDate(expenseDate),
            prepaidRepository.observeAllExpenseLinks()
        ) { expenses, cancellations, links ->
            CancellationAuditCore(expenses, cancellations, links)
        }
        val references = combine(
            prepaidRepository.observeAllTransactions(),
            prepaidRepository.observeAllAccounts(),
            accountingRepository.observeStoredExpenseEvidence()
        ) { transactions, accounts, evidence ->
            CancellationAuditReferences(transactions, accounts, evidence)
        }
        return combine(core, references) { auditCore, auditReferences ->
            buildExpenseCancellationAuditItems(
                expenses = auditCore.expenses,
                cancellations = auditCore.cancellations,
                links = auditCore.links,
                transactions = auditReferences.transactions,
                accounts = auditReferences.accounts,
                evidence = auditReferences.evidence
            )
        }
    }
}

internal fun buildExpenseCancellationAuditItems(
    expenses: List<ExpenseRecord>,
    cancellations: List<ExpenseCancellationRecord>,
    links: List<ExpensePrepaidLinkRecord>,
    transactions: List<PrepaidTransactionRecord>,
    accounts: List<PrepaidAccountRecord>,
    evidence: List<ExpenseEvidenceRecord>
): List<ExpenseCancellationAuditItem> {
    val cancellationByExpense = cancellations.associateBy { it.expenseId }
    val linkByExpense = links.associateBy { it.expenseId }
    val transactionById = transactions.associateBy { it.id }
    val accountById = accounts.associateBy { it.id }
    val evidenceByExpense = evidence.groupBy { it.expenseId }

    return expenses.mapNotNull { expense ->
        val cancellation = cancellationByExpense[expense.id] ?: return@mapNotNull null
        val link = linkByExpense[expense.id]
        val purchase = cancellation.originalPurchaseTransactionId?.let(transactionById::get)
        val reversal = cancellation.reversalTransactionId?.let(transactionById::get)
        val account = purchase?.accountId?.let(accountById::get)
        val isPrepaid = normalizePaymentMethod(expense.paymentMethod) == PaymentMethodPrepaid
        val complete = if (isPrepaid) {
            link?.purchaseTransactionId == purchase?.id &&
                expense.amount > 0L &&
                purchase?.transactionType == PrepaidTransactionType.Purchase &&
                purchase.expenseId == expense.id &&
                purchase.reversalOfTransactionId == null &&
                purchase.balanceDelta == -expense.amount &&
                purchase.transactionDate == expense.expenseDate &&
                reversal?.transactionType == PrepaidTransactionType.Reversal &&
                reversal.reversalOfTransactionId == purchase.id &&
                reversal.expenseId == expense.id &&
                reversal.accountId == purchase.accountId &&
                reversal.balanceDelta == expense.amount &&
                reversal.transactionDate == cancellation.cancellationDate &&
                reversal.createdAt == cancellation.cancelledAt &&
                reversal.chargeSource == null &&
                reversal.operationKey == "${cancellation.operationKey}:reversal" &&
                reversal.memo == cancellation.reason.orEmpty() &&
                account != null
        } else {
            link == null &&
                cancellation.originalPurchaseTransactionId == null &&
                cancellation.reversalTransactionId == null &&
                purchase == null &&
                reversal == null &&
                account == null
        }
        ExpenseCancellationAuditItem(
            expense = expense,
            cancellation = cancellation,
            prepaidLink = link,
            originalPurchase = purchase,
            reversal = reversal,
            prepaidAccount = account,
            evidence = evidenceByExpense[expense.id].orEmpty(),
            isPrepaidCancellation = isPrepaid,
            hasCompleteLedgerRelation = complete
        )
    }
}
