package com.warun.accounting.data.edit

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.warun.accounting.data.local.ExpenseEditOperationRecord
import com.warun.accounting.data.local.ExpenseEditOperationStatus
import com.warun.accounting.data.local.ExpenseRecord
import com.warun.accounting.data.local.ExpenseSourceType
import com.warun.accounting.data.local.WarunDatabase
import com.warun.accounting.util.PaymentMethodCash
import com.warun.accounting.util.PaymentMethodPrepaid
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExpenseEditOperationPersistenceTest {
    private lateinit var database: WarunDatabase

    @Before
    fun createDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, WarunDatabase::class.java).build()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun daoEnforcesInsertAndConditionalCompletionWithoutDeleteApi() = runBlocking {
        val dao = database.expenseEditOperationDao()
        val started = startedRecord("operation-1", "expense-1", "a".repeat(64))
        dao.insert(started)

        assertTrue(dao.exists(started.operationKey))
        assertEquals(started, dao.getByOperationKey(started.operationKey))
        assertEquals(listOf(started), dao.getByExpenseId(started.expenseId))
        assertFailure {
            dao.insert(started.copy(requestFingerprint = "different"))
        }
        assertEquals(
            0,
            dao.markCompleted(
                operationKey = started.operationKey,
                requestFingerprint = "different",
                completedAt = 2L,
                resultPaymentMethod = PaymentMethodCash,
                resultPrepaidAccountId = null,
                resultAmount = 500L
            )
        )
        assertEquals(
            1,
            dao.markCompleted(
                operationKey = started.operationKey,
                requestFingerprint = started.requestFingerprint,
                completedAt = 2L,
                resultPaymentMethod = PaymentMethodCash,
                resultPrepaidAccountId = null,
                resultAmount = 500L
            )
        )
        assertEquals(
            0,
            dao.markCompleted(
                operationKey = started.operationKey,
                requestFingerprint = started.requestFingerprint,
                completedAt = 3L,
                resultPaymentMethod = PaymentMethodCash,
                resultPrepaidAccountId = null,
                resultAmount = 500L
            )
        )
    }

    @Test
    fun executorCompletesOnceAndReturnsPersistedResultForIdenticalRetry() = runBlocking {
        val executor = executor()
        val request = request("operation-once", "expense-once")
        var executions = 0

        val first = executor.execute(request) {
            executions++
            completion()
        }
        val retry = executor.execute(request) {
            executions++
            completion()
        }

        assertEquals(1, executions)
        assertFalse(first.wasAlreadyCompleted)
        assertTrue(retry.wasAlreadyCompleted)
        assertEquals(first.operation, retry.operation)
        assertEquals(ExpenseEditOperationStatus.Completed, retry.operation.status)
        assertEquals(PaymentMethodPrepaid, retry.operation.resultPaymentMethod)
        assertEquals("prepaid-majica", retry.operation.resultPrepaidAccountId)
        assertEquals(500L, retry.operation.resultAmount)
        assertNotNull(retry.operation.completedAt)
    }

    @Test
    fun executorRejectsDifferentRequestOrExpenseForCompletedKey() = runBlocking {
        val executor = executor()
        val request = request("operation-conflict", "expense-1")
        executor.execute(request) { completion() }

        assertFailure(ExpenseEditOperationFailure.OperationConflict) {
            executor.execute(
                request.copy(
                    fingerprintInput = request.fingerprintInput.copy(amount = 501L)
                )
            ) {
                completion(amount = 501L)
            }
        }
        assertFailure(ExpenseEditOperationFailure.OperationConflict) {
            executor.execute(
                request.copy(
                    fingerprintInput = request.fingerprintInput.copy(expenseId = "expense-2")
                )
            ) {
                completion()
            }
        }
    }

    @Test
    fun executorDoesNotAutomaticallyResumePersistedStartedState() = runBlocking {
        val request = request("operation-started", "expense-started")
        val started = startedRecord(
            operationKey = request.operationKey,
            expenseId = request.fingerprintInput.expenseId,
            fingerprint = ExpenseEditRequestFingerprint.create(request.fingerprintInput)
        )
        database.expenseEditOperationDao().insert(started)
        var executed = false

        assertFailure(ExpenseEditOperationFailure.OperationStateCorrupted) {
            executor().execute(request) {
                executed = true
                completion()
            }
        }

        assertFalse(executed)
        assertEquals(
            started,
            database.expenseEditOperationDao().getByOperationKey(request.operationKey)
        )
    }

    @Test
    fun executorRejectsBlankOperationKeyBeforeWriting() = runBlocking {
        val request = request(" ", "expense-invalid-operation")

        assertFailure(ExpenseEditOperationFailure.InvalidOperationKey) {
            executor().execute(request) { completion() }
        }

        assertFalse(database.expenseEditOperationDao().exists(" "))
    }

    @Test
    fun editFailureRollsBackOperationAndBodyChanges() = runBlocking {
        val original = expense("expense-rollback", 500L)
        database.warunDao().insertExpenseRecord(original)
        val request = request("operation-rollback", original.id)

        assertFailure {
            executor().execute(request) {
                database.warunDao().insertExpenseRecord(
                    original.copy(amount = 999L, updatedAt = 2L)
                )
                error("edit failed")
            }
        }

        assertEquals(original, database.warunDao().getExpenseRecord(original.id))
        assertNull(
            database.expenseEditOperationDao().getByOperationKey(request.operationKey)
        )
    }

    @Test
    fun completionRaceOrStateChangeRollsBackWholeTransaction() = runBlocking {
        val original = expense("expense-completion-failure", 500L)
        database.warunDao().insertExpenseRecord(original)
        val request = request("operation-completion-failure", original.id)
        val fingerprint = ExpenseEditRequestFingerprint.create(request.fingerprintInput)

        assertFailure(ExpenseEditOperationFailure.OperationStateCorrupted) {
            executor().execute(request) {
                database.warunDao().insertExpenseRecord(
                    original.copy(amount = 999L, updatedAt = 2L)
                )
                assertEquals(
                    1,
                    database.expenseEditOperationDao().markCompleted(
                        operationKey = request.operationKey,
                        requestFingerprint = fingerprint,
                        completedAt = 2L,
                        resultPaymentMethod = PaymentMethodPrepaid,
                        resultPrepaidAccountId = "prepaid-majica",
                        resultAmount = 500L
                    )
                )
                completion()
            }
        }

        assertEquals(original, database.warunDao().getExpenseRecord(original.id))
        assertNull(
            database.expenseEditOperationDao().getByOperationKey(request.operationKey)
        )
    }

    @Test
    fun concurrentIdenticalCallsExecuteBodyOnlyOnce() = runBlocking {
        val executor = executor()
        val request = request("operation-concurrent", "expense-concurrent")
        val executions = AtomicInteger(0)

        val results = listOf(
            async(Dispatchers.Default) {
                executor.execute(request) {
                    executions.incrementAndGet()
                    delay(50)
                    completion()
                }
            },
            async(Dispatchers.Default) {
                executor.execute(request) {
                    executions.incrementAndGet()
                    delay(50)
                    completion()
                }
            }
        ).awaitAll()

        assertEquals(1, executions.get())
        assertEquals(1, results.count { it.wasAlreadyCompleted })
        assertEquals(1, results.count { !it.wasAlreadyCompleted })
        assertEquals(1, database.expenseEditOperationDao().getByExpenseId("expense-concurrent").size)
    }

    private fun executor() = ExpenseEditOperationExecutor(
        database = database,
        operationDao = database.expenseEditOperationDao()
    )

    private fun request(
        operationKey: String,
        expenseId: String
    ) = ExpenseEditOperationRequest(
        operationKey = operationKey,
        fingerprintInput = ExpenseEditRequestFingerprintInput(
            expenseId = expenseId,
            paymentMethod = PaymentMethodPrepaid,
            prepaidAccountId = "prepaid-majica",
            amount = 500L,
            date = "2026-07-28",
            category = "other_expense",
            supplier = "テスト商店",
            memo = "編集",
            receiptId = null,
            sourceType = ExpenseSourceType.Manual
        ),
        createdAt = 1L
    )

    private fun completion(amount: Long = 500L) = ExpenseEditOperationCompletion(
        paymentMethod = PaymentMethodPrepaid,
        prepaidAccountId = "prepaid-majica",
        amount = amount,
        completedAt = 2L
    )

    private fun startedRecord(
        operationKey: String,
        expenseId: String,
        fingerprint: String
    ) = ExpenseEditOperationRecord(
        operationKey = operationKey,
        expenseId = expenseId,
        requestFingerprint = fingerprint,
        status = ExpenseEditOperationStatus.Started,
        createdAt = 1L,
        updatedAt = 1L,
        completedAt = null,
        resultPaymentMethod = null,
        resultPrepaidAccountId = null,
        resultAmount = null
    )

    private fun expense(id: String, amount: Long) = ExpenseRecord(
        id = id,
        expenseDate = "2026-07-28",
        category = "other_expense",
        supplierName = "テスト商店",
        amount = amount,
        paymentMethod = PaymentMethodPrepaid,
        memo = "編集",
        receiptId = null,
        sourceType = ExpenseSourceType.Manual,
        createdAt = 1L,
        updatedAt = 1L
    )

    private suspend fun assertFailure(block: suspend () -> Unit) {
        assertTrue(runCatching { block() }.isFailure)
    }

    private suspend fun assertFailure(
        expected: ExpenseEditOperationFailure,
        block: suspend () -> Unit
    ) {
        val error = runCatching { block() }.exceptionOrNull()
        assertEquals(expected, (error as? ExpenseEditOperationException)?.failure)
    }
}
