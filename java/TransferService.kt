package com.bankofz.transfer

/**
 * TransferService.kt — Kotlin/Spring Boot equivalent of COBOL XFRFUN.cbl
 *
 * This file implements the fund transfer logic originally contained in the
 * CICS/DB2 COBOL program XFRFUN from Bank-of-Z. Each section is annotated
 * with its COBOL equivalent for traceability.
 *
 * COBOL source: Bank-Of-Z/src/base/cics/cobol/XFRFUN.cbl
 * Copyright IBM Corp. 2023 (original COBOL)
 */

import jakarta.persistence.*
import org.springframework.dao.CannotAcquireLockException
import org.springframework.dao.PessimisticLockingFailureException
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.UUID

// ---------------------------------------------------------------------------
// Domain model
// ---------------------------------------------------------------------------

/**
 * JPA entity for the ACCOUNT DB2 table.
 * Equivalent to COBOL HOST-ACCOUNT-ROW and ACCDB2 SQL include.
 */
@Entity
@Table(name = "ACCOUNT")
data class Account(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "ACCOUNT_EYECATCHER", length = 4)
    val eyecatcher: String = "ACCT",

    @Column(name = "ACCOUNT_CUSTOMER_NUMBER", length = 10)
    val customerNumber: String,

    @Column(name = "ACCOUNT_SORTCODE", length = 6)
    val sortCode: String,

    @Column(name = "ACCOUNT_NUMBER", length = 8)
    val accountNumber: String,

    @Column(name = "ACCOUNT_TYPE", length = 8)
    val accountType: String = "CURRENT",

    @Column(name = "ACCOUNT_INTEREST_RATE", precision = 6, scale = 2)
    val interestRate: BigDecimal = BigDecimal.ZERO,

    @Column(name = "ACCOUNT_OPENED")
    val opened: LocalDate? = null,

    @Column(name = "ACCOUNT_OVERDRAFT_LIMIT")
    val overdraftLimit: Int = 0,

    @Column(name = "ACCOUNT_LAST_STATEMENT")
    val lastStatement: LocalDate? = null,

    @Column(name = "ACCOUNT_NEXT_STATEMENT")
    val nextStatement: LocalDate? = null,

    /** Equivalent to COBOL HV-ACCOUNT-AVAIL-BAL */
    @Column(name = "ACCOUNT_AVAILABLE_BALANCE", precision = 12, scale = 2)
    var availableBalance: BigDecimal,

    /** Equivalent to COBOL HV-ACCOUNT-ACTUAL-BAL */
    @Column(name = "ACCOUNT_ACTUAL_BALANCE", precision = 12, scale = 2)
    var actualBalance: BigDecimal
)

/**
 * JPA entity for the PROCTRAN DB2 table.
 * Equivalent to COBOL HOST-PROCTRAN-ROW and PROCDB2 SQL include.
 */
@Entity
@Table(name = "PROCTRAN")
data class TransactionRecord(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "PROCTRAN_EYECATCHER", length = 4)
    val eyecatcher: String = "PRTR",

    @Column(name = "PROCTRAN_SORTCODE", length = 6)
    val sortCode: String,

    @Column(name = "PROCTRAN_NUMBER", length = 8)
    val accountNumber: String,

    @Column(name = "PROCTRAN_DATE", length = 10)
    val date: LocalDate,

    @Column(name = "PROCTRAN_TIME", length = 6)
    val time: LocalTime,

    /** Equivalent to COBOL HV-PROCTRAN-REF (CICS task number padded to 12) */
    @Column(name = "PROCTRAN_REF", length = 12)
    val reference: String,

    /** "TFR" = transfer. Equivalent to PROC-TY-TRANSFER in PROCTRAN copybook. */
    @Column(name = "PROCTRAN_TYPE", length = 3)
    val type: String = "TFR",

    @Column(name = "PROCTRAN_DESC", length = 40)
    val description: String,

    /** Negative = debit (FROM account), Positive = credit (TO account) */
    @Column(name = "PROCTRAN_AMOUNT", precision = 12, scale = 2)
    val amount: BigDecimal
)

// ---------------------------------------------------------------------------
// Request / response DTOs  (equivalent to XFRFUN COMMAREA)
// ---------------------------------------------------------------------------

/**
 * Equivalent to COBOL DFHCOMMAREA / XFRFUN.cpy input fields.
 */
data class TransferRequest(
    /** COMM-FSCODE */
    val fromSortCode: String,
    /** COMM-FACCNO */
    val fromAccountNumber: String,
    /** COMM-TSCODE */
    val toSortCode: String,
    /** COMM-TACCNO */
    val toAccountNumber: String,
    /** COMM-AMT */
    val amount: BigDecimal
)

/**
 * Equivalent to COBOL DFHCOMMAREA / XFRFUN.cpy output fields.
 */
data class TransferResult(
    /** COMM-SUCCESS */
    val success: Boolean,
    /** COMM-FAIL-CODE: "1"=from not found, "2"=to not found, "3"=DB error, "4"=bad amount */
    val failCode: String? = null,
    /** COMM-FAVBAL */
    val fromAvailableBalance: BigDecimal? = null,
    /** COMM-FACTBAL */
    val fromActualBalance: BigDecimal? = null,
    /** COMM-TAVBAL */
    val toAvailableBalance: BigDecimal? = null,
    /** COMM-TACTBAL */
    val toActualBalance: BigDecimal? = null,
    val transferId: String? = null
)

// ---------------------------------------------------------------------------
// Exceptions (equivalent to COBOL ABEND codes)
// ---------------------------------------------------------------------------

/**
 * Equivalent to COBOL COMM-SUCCESS='N' + COMM-FAIL-CODE set.
 * Recoverable business error — caller receives failCode in response.
 */
class TransferException(message: String, val failCode: String) : RuntimeException(message)

/**
 * Equivalent to COBOL EXEC CICS ABEND (WPCD, WPCT, HROL, RUF2/3).
 * Non-recoverable system error — results in HTTP 500.
 */
class SystemException(message: String) : RuntimeException(message)

// ---------------------------------------------------------------------------
// Repositories
// ---------------------------------------------------------------------------

@Repository
interface AccountRepository : JpaRepository<Account, Long> {

    /**
     * Standard read — used to check existence before locking.
     */
    fun findBySortCodeAndAccountNumber(sortCode: String, accountNumber: String): Account?

    /**
     * Pessimistic write lock — equivalent to DB2 SELECT FOR UPDATE.
     * Replaces COBOL deadlock prevention via account-number ordering.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.sortCode = :sortCode AND a.accountNumber = :accountNumber")
    fun findBySortCodeAndAccountNumberWithLock(sortCode: String, accountNumber: String): Account?
}

@Repository
interface TransactionRepository : JpaRepository<TransactionRecord, Long>

// ---------------------------------------------------------------------------
// Service
// ---------------------------------------------------------------------------

/**
 * Equivalent to COBOL program XFRFUN.cbl — PROCEDURE DIVISION.
 *
 * Orchestrates the fund transfer: validates input, acquires locks in
 * account-number order (deadlock prevention), debits FROM, credits TO,
 * writes two PROCTRAN records, and returns updated balances.
 */
@Service
class TransferService(
    private val accountRepository: AccountRepository,
    private val transactionRepository: TransactionRepository
) {

    companion object {
        /** Equivalent to COBOL LOCAL-STORAGE DB2-DEADLOCK-RETRY < 6 */
        private const val MAX_DEADLOCK_RETRIES = 5
    }

    /**
     * Entry point — equivalent to COBOL PREMIERE section (A010).
     *
     * Validates input, then drives the retry loop around the transactional
     * core. Catches deadlock exceptions and retries up to MAX_DEADLOCK_RETRIES
     * times with a 1-second delay — equivalent to COBOL's GO TO UPDATE-ACCOUNT-DB2
     * with DB2-DEADLOCK-RETRY counter and EXEC CICS DELAY FOR SECONDS(1).
     */
    fun transfer(request: TransferRequest): TransferResult {

        // Equivalent to COBOL: IF COMM-AMT <= ZERO MOVE 'N' TO COMM-SUCCESS
        //                       MOVE '4' TO COMM-FAIL-CODE PERFORM GET-ME-OUT-OF-HERE
        if (request.amount <= BigDecimal.ZERO) {
            return TransferResult(success = false, failCode = "4")
        }

        // Equivalent to COBOL: IF COMM-FACCNO = COMM-TACCNO AND COMM-FSCODE = COMM-TSCODE
        //                       EXEC CICS ABEND ABCODE('SAME')
        if (request.fromAccountNumber == request.toAccountNumber &&
            request.fromSortCode == request.toSortCode
        ) {
            throw TransferException(
                "Cannot transfer to the same account (ABEND SAME equivalent)",
                failCode = "SAME"
            )
        }

        // Deadlock retry loop — equivalent to COBOL DB2-DEADLOCK-RETRY in LOCAL-STORAGE
        var retryCount = 0
        while (true) {
            try {
                return executeTransfer(request)
            } catch (e: CannotAcquireLockException) {
                retryCount++
                if (retryCount >= MAX_DEADLOCK_RETRIES) {
                    // Equivalent to COBOL: EXEC CICS ABEND ABCODE('RUF2') after 5 retries
                    throw SystemException(
                        "Deadlock retry limit ($MAX_DEADLOCK_RETRIES) exceeded — equivalent to ABEND RUF2"
                    )
                }
                // Equivalent to COBOL: EXEC CICS DELAY FOR SECONDS(1)
                Thread.sleep(1000L)
            } catch (e: PessimisticLockingFailureException) {
                retryCount++
                if (retryCount >= MAX_DEADLOCK_RETRIES) {
                    throw SystemException(
                        "Pessimistic lock failure retry limit exceeded — equivalent to ABEND RUF2"
                    )
                }
                Thread.sleep(1000L)
            }
        }
    }

    /**
     * Core transactional transfer — equivalent to COBOL UPDATE-ACCOUNT-DB2 section (UAD010).
     *
     * Acquires DB locks in account-number order (lower first) to prevent deadlocks —
     * directly equivalent to COBOL's IF COMM-FACCNO < COMM-TACCNO ordering logic.
     * Debits FROM, credits TO, writes two PROCTRAN records, commits.
     */
    @Transactional
    fun executeTransfer(request: TransferRequest): TransferResult {
        val transferId = UUID.randomUUID().toString()
        val now = LocalDateTime.now()

        // Determine lock order: lower account number is locked first.
        // Equivalent to COBOL: IF COMM-FACCNO < COMM-TACCNO (UAD010)
        val fromFirst = request.fromAccountNumber < request.toAccountNumber

        val (firstKey, secondKey) = if (fromFirst) {
            Pair(
                Pair(request.fromSortCode, request.fromAccountNumber),
                Pair(request.toSortCode, request.toAccountNumber)
            )
        } else {
            Pair(
                Pair(request.toSortCode, request.toAccountNumber),
                Pair(request.fromSortCode, request.fromAccountNumber)
            )
        }

        // Acquire locks in order — equivalent to COBOL sequential UPDATE calls
        val firstAccount = accountRepository.findBySortCodeAndAccountNumberWithLock(
            firstKey.first, firstKey.second
        ) ?: throw TransferException(
            "Account ${firstKey.second} not found",
            failCode = if (fromFirst) "1" else "2"
        )

        val secondAccount = accountRepository.findBySortCodeAndAccountNumberWithLock(
            secondKey.first, secondKey.second
        ) ?: throw TransferException(
            "Account ${secondKey.second} not found",
            failCode = if (fromFirst) "2" else "1"
        )

        // Resolve which locked account is FROM and which is TO
        val fromAccount = if (fromFirst) firstAccount else secondAccount
        val toAccount   = if (fromFirst) secondAccount else firstAccount

        // Debit FROM — equivalent to COBOL UPDATE-ACCOUNT-DB2-FROM (UADF010)
        // COMPUTE HV-ACCOUNT-AVAIL-BAL = HV-ACCOUNT-AVAIL-BAL - COMM-AMT
        fromAccount.availableBalance -= request.amount
        fromAccount.actualBalance    -= request.amount
        accountRepository.save(fromAccount)

        // Credit TO — equivalent to COBOL UPDATE-ACCOUNT-DB2-TO (UADT010)
        // COMPUTE HV-ACCOUNT-AVAIL-BAL = HV-ACCOUNT-AVAIL-BAL + COMM-AMT
        toAccount.availableBalance += request.amount
        toAccount.actualBalance    += request.amount
        accountRepository.save(toAccount)

        // Write PROCTRAN records — equivalent to COBOL WRITE-TO-PROCTRAN-DB2 (WTPD010)
        writeProctranRecords(request, fromAccount, toAccount, transferId, now)

        return TransferResult(
            success              = true,
            transferId           = transferId,
            fromAvailableBalance = fromAccount.availableBalance,
            fromActualBalance    = fromAccount.actualBalance,
            toAvailableBalance   = toAccount.availableBalance,
            toActualBalance      = toAccount.actualBalance
        )
    }

    /**
     * Writes two PROCTRAN records: one debit (negative) for FROM, one credit (positive) for TO.
     * Equivalent to COBOL WRITE-TO-PROCTRAN-DB2 (WTPD010).
     *
     * COBOL: COMPUTE HV-PROCTRAN-AMOUNT = COMM-AMT * -1  (FROM record)
     * COBOL: MOVE COMM-AMT TO HV-PROCTRAN-AMOUNT          (TO record)
     */
    private fun writeProctranRecords(
        request: TransferRequest,
        fromAccount: Account,
        toAccount: Account,
        transferId: String,
        timestamp: LocalDateTime
    ) {
        val ref = transferId.replace("-", "").take(12)

        // FROM debit record — negative amount. Equivalent to ABEND WPCD on failure.
        try {
            transactionRepository.save(
                TransactionRecord(
                    sortCode      = fromAccount.sortCode,
                    accountNumber = fromAccount.accountNumber,
                    date          = timestamp.toLocalDate(),
                    time          = timestamp.toLocalTime(),
                    reference     = ref,
                    type          = "TFR",
                    description   = "Transfer to   ${toAccount.sortCode}${toAccount.accountNumber}",
                    amount        = request.amount.negate()   // negative = debit
                )
            )
        } catch (e: Exception) {
            // Equivalent to COBOL: EXEC CICS ABEND ABCODE('WPCD')
            // Note: ACCOUNT rows are already updated at this point — same data inconsistency risk as COBOL.
            throw SystemException("Failed to write FROM PROCTRAN record (WPCD equivalent): ${e.message}")
        }

        // TO credit record — positive amount. Equivalent to ABEND WPCT on failure.
        try {
            transactionRepository.save(
                TransactionRecord(
                    sortCode      = toAccount.sortCode,
                    accountNumber = toAccount.accountNumber,
                    date          = timestamp.toLocalDate(),
                    time          = timestamp.toLocalTime(),
                    reference     = ref,
                    type          = "TFR",
                    description   = "Transfer from ${fromAccount.sortCode}${fromAccount.accountNumber}",
                    amount        = request.amount              // positive = credit
                )
            )
        } catch (e: Exception) {
            // Equivalent to COBOL: EXEC CICS ABEND ABCODE('WPCT')
            throw SystemException("Failed to write TO PROCTRAN record (WPCT equivalent): ${e.message}")
        }
    }
}

// ---------------------------------------------------------------------------
// REST Controller
// ---------------------------------------------------------------------------

/**
 * REST API controller — equivalent to CICS COMMAREA interface + z/OS Connect gateway.
 *
 * Maps HTTP verbs and status codes to COBOL COMMAREA outcomes:
 *   COMM-SUCCESS='Y'        → HTTP 200
 *   COMM-FAIL-CODE='4'      → HTTP 400 (bad amount)
 *   COMM-FAIL-CODE='SAME'   → HTTP 409 (same account)
 *   COMM-FAIL-CODE='1'/'2'  → HTTP 404 (account not found)
 *   COMM-FAIL-CODE='3'      → HTTP 500 (DB error)
 *   SystemException         → HTTP 500 (equivalent to CICS abend)
 */
@RestController
@RequestMapping("/v1/transfers")
class TransferController(private val transferService: TransferService) {

    @PostMapping
    fun transfer(@RequestBody request: TransferRequest): ResponseEntity<Any> {
        return try {
            val result = transferService.transfer(request)
            ResponseEntity.ok(result)
        } catch (e: TransferException) {
            when (e.failCode) {
                "4", "SAME" -> ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(mapOf("error" to e.message, "failCode" to e.failCode))
                "1", "2" -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(mapOf("error" to e.message, "failCode" to e.failCode))
                else -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(mapOf("error" to e.message, "failCode" to e.failCode))
            }
        } catch (e: SystemException) {
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(mapOf("error" to e.message))
        }
    }
}
