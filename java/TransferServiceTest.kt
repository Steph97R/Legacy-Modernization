package com.bankofz.transfer

/**
 * TransferServiceTest.kt — JUnit 5 regression tests for TransferService
 *
 * Maps to the regression test matrix in:
 *   Legacy-Modernization/testing/XFRFUN-regression-prompt.md
 *
 * Each test maps to a TC-xxx test case from the matrix, verifying the
 * Kotlin service produces the same outcome as COBOL XFRFUN would.
 */

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.*
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.dao.CannotAcquireLockException
import java.math.BigDecimal
import java.time.LocalDate

@ExtendWith(MockitoExtension::class)
class TransferServiceTest {

    @Mock
    private lateinit var accountRepository: AccountRepository

    @Mock
    private lateinit var transactionRepository: TransactionRepository

    @Captor
    private lateinit var transactionCaptor: ArgumentCaptor<TransactionRecord>

    private lateinit var transferService: TransferService

    // ---------------------------------------------------------------------------
    // Sample accounts used across tests
    // ---------------------------------------------------------------------------

    /** Account with lower account number — used as FROM in TC-001 */
    private lateinit var accountLow: Account

    /** Account with higher account number — used as TO in TC-001 */
    private lateinit var accountHigh: Account

    @BeforeEach
    fun setUp() {
        transferService = TransferService(accountRepository, transactionRepository)

        accountLow = Account(
            id              = 1L,
            customerNumber  = "1234567890",
            sortCode        = "987654",
            accountNumber   = "00000001",
            accountType     = "CURRENT",
            availableBalance = BigDecimal("500.00"),
            actualBalance    = BigDecimal("500.00")
        )

        accountHigh = Account(
            id              = 2L,
            customerNumber  = "9876543210",
            sortCode        = "987654",
            accountNumber   = "00000099",
            accountType     = "SAVINGS",
            availableBalance = BigDecimal("200.00"),
            actualBalance    = BigDecimal("200.00")
        )
    }

    // ---------------------------------------------------------------------------
    // TC-001: Happy path — FROM account number less than TO account number
    // Equivalent COBOL: IF COMM-FACCNO < COMM-TACCNO → UPDATE FROM first then TO
    // ---------------------------------------------------------------------------

    @Test
    fun `TC-001 - happy path - FROM less than TO - debits FROM and credits TO`() {
        val request = TransferRequest(
            fromSortCode        = "987654",
            fromAccountNumber   = "00000001",  // lower
            toSortCode          = "987654",
            toAccountNumber     = "00000099",  // higher
            amount              = BigDecimal("100.00")
        )

        // FROM (low) is locked first, then TO (high) — matching COBOL lock order
        `when`(accountRepository.findBySortCodeAndAccountNumberWithLock("987654", "00000001"))
            .thenReturn(accountLow)
        `when`(accountRepository.findBySortCodeAndAccountNumberWithLock("987654", "00000099"))
            .thenReturn(accountHigh)
        `when`(accountRepository.save(any())).thenAnswer { it.arguments[0] }
        `when`(transactionRepository.save(any())).thenAnswer { it.arguments[0] }

        val result = transferService.transfer(request)

        assertTrue(result.success)
        assertNull(result.failCode)
        // COMM-FAVBAL: 500.00 - 100.00 = 400.00
        assertEquals(BigDecimal("400.00"), result.fromAvailableBalance)
        assertEquals(BigDecimal("400.00"), result.fromActualBalance)
        // COMM-TAVBAL: 200.00 + 100.00 = 300.00
        assertEquals(BigDecimal("300.00"), result.toAvailableBalance)
        assertEquals(BigDecimal("300.00"), result.toActualBalance)
    }

    // ---------------------------------------------------------------------------
    // TC-002: Happy path — FROM account number greater than TO (lock order reversed)
    // Equivalent COBOL: ELSE branch → UPDATE TO first then FROM
    // ---------------------------------------------------------------------------

    @Test
    fun `TC-002 - happy path - FROM greater than TO - lock order reverses internally`() {
        val request = TransferRequest(
            fromSortCode        = "987654",
            fromAccountNumber   = "00000099",  // higher — FROM
            toSortCode          = "987654",
            toAccountNumber     = "00000001",  // lower — TO (locked first)
            amount              = BigDecimal("50.00")
        )

        // TO (low) is locked first — service reverses lock order internally
        `when`(accountRepository.findBySortCodeAndAccountNumberWithLock("987654", "00000001"))
            .thenReturn(accountLow)   // TO account, locked first
        `when`(accountRepository.findBySortCodeAndAccountNumberWithLock("987654", "00000099"))
            .thenReturn(accountHigh)  // FROM account, locked second
        `when`(accountRepository.save(any())).thenAnswer { it.arguments[0] }
        `when`(transactionRepository.save(any())).thenAnswer { it.arguments[0] }

        val result = transferService.transfer(request)

        assertTrue(result.success)
        // FROM (accountHigh): 200.00 - 50.00 = 150.00
        assertEquals(BigDecimal("150.00"), result.fromAvailableBalance)
        // TO (accountLow): 500.00 + 50.00 = 550.00
        assertEquals(BigDecimal("550.00"), result.toAvailableBalance)
    }

    // ---------------------------------------------------------------------------
    // TC-003: Zero amount — rejected before DB2 access
    // Equivalent COBOL: IF COMM-AMT <= ZERO → COMM-FAIL-CODE='4' PERFORM GET-ME-OUT-OF-HERE
    // ---------------------------------------------------------------------------

    @Test
    fun `TC-003 - zero amount - rejected with failCode 4`() {
        val request = TransferRequest("987654", "00000001", "987654", "00000099", BigDecimal.ZERO)

        val result = transferService.transfer(request)

        assertFalse(result.success)
        assertEquals("4", result.failCode)
        verifyNoInteractions(accountRepository)  // No DB2 access, matching COBOL behaviour
    }

    // ---------------------------------------------------------------------------
    // TC-004: Negative amount — rejected before DB2 access
    // Equivalent COBOL: IF COMM-AMT <= ZERO (same branch as TC-003)
    // ---------------------------------------------------------------------------

    @Test
    fun `TC-004 - negative amount - rejected with failCode 4`() {
        val request = TransferRequest("987654", "00000001", "987654", "00000099", BigDecimal("-25.00"))

        val result = transferService.transfer(request)

        assertFalse(result.success)
        assertEquals("4", result.failCode)
        verifyNoInteractions(accountRepository)
    }

    // ---------------------------------------------------------------------------
    // TC-005: Same account — TransferException with failCode SAME
    // Equivalent COBOL: EXEC CICS ABEND ABCODE('SAME')
    // ---------------------------------------------------------------------------

    @Test
    fun `TC-005 - same account - TransferException with failCode SAME`() {
        val request = TransferRequest("987654", "00000001", "987654", "00000001", BigDecimal("100.00"))

        val ex = assertThrows<TransferException> { transferService.transfer(request) }

        assertEquals("SAME", ex.failCode)
        verifyNoInteractions(accountRepository)
    }

    // ---------------------------------------------------------------------------
    // TC-006: FROM account not found — failCode 1
    // Equivalent COBOL: SQLCODE +100 on FROM SELECT → COMM-FAIL-CODE='1'
    // ---------------------------------------------------------------------------

    @Test
    fun `TC-006 - FROM account not found - TransferException failCode 1`() {
        val request = TransferRequest("987654", "99999999", "987654", "00000099", BigDecimal("75.00"))

        // FROM account (lower number) locked first — returns null
        `when`(accountRepository.findBySortCodeAndAccountNumberWithLock("987654", "00000099"))
            .thenReturn(accountHigh)
        `when`(accountRepository.findBySortCodeAndAccountNumberWithLock("987654", "99999999"))
            .thenReturn(null)

        val ex = assertThrows<TransferException> { transferService.transfer(request) }

        assertEquals("1", ex.failCode)
    }

    // ---------------------------------------------------------------------------
    // TC-007: TO account not found — failCode 2
    // Equivalent COBOL: SQLCODE +100 on TO SELECT → COMM-FAIL-CODE='2' + SYNCPOINT ROLLBACK
    // In Spring @Transactional: rollback is automatic on exception
    // ---------------------------------------------------------------------------

    @Test
    fun `TC-007 - TO account not found - TransferException failCode 2`() {
        val request = TransferRequest("987654", "00000001", "987654", "99999999", BigDecimal("50.00"))

        // FROM (low) locked first — found
        `when`(accountRepository.findBySortCodeAndAccountNumberWithLock("987654", "00000001"))
            .thenReturn(accountLow)
        // TO not found — null (99999999 is higher, locked second)
        `when`(accountRepository.findBySortCodeAndAccountNumberWithLock("987654", "99999999"))
            .thenReturn(null)

        val ex = assertThrows<TransferException> { transferService.transfer(request) }

        assertEquals("2", ex.failCode)
        // @Transactional ensures FROM account update is also rolled back — equivalent to SYNCPOINT ROLLBACK
    }

    // ---------------------------------------------------------------------------
    // TC-009: Deadlock retry — succeeds on 3rd attempt
    // Equivalent COBOL: SQLCODE -911 → DB2-DEADLOCK-RETRY counter, retry with DELAY
    // ---------------------------------------------------------------------------

    @Test
    fun `TC-009 - deadlock retry - succeeds on 3rd attempt`() {
        val request = TransferRequest("987654", "00000001", "987654", "00000099", BigDecimal("100.00"))

        `when`(accountRepository.findBySortCodeAndAccountNumberWithLock("987654", "00000001"))
            // First two calls throw CannotAcquireLockException (deadlock), third succeeds
            .thenThrow(CannotAcquireLockException("Deadlock attempt 1"))
            .thenThrow(CannotAcquireLockException("Deadlock attempt 2"))
            .thenReturn(accountLow)
        `when`(accountRepository.findBySortCodeAndAccountNumberWithLock("987654", "00000099"))
            .thenReturn(accountHigh)
        `when`(accountRepository.save(any())).thenAnswer { it.arguments[0] }
        `when`(transactionRepository.save(any())).thenAnswer { it.arguments[0] }

        // Use a subclass that skips Thread.sleep for test speed
        val fastService = object : TransferService(accountRepository, transactionRepository) {}

        // For unit test: directly test retry logic by calling transfer()
        // Note: Thread.sleep(1000) will execute twice in this test — acceptable for integration,
        // use a mock clock / test profile for pure unit speed if needed
        val result = transferService.transfer(request)

        assertTrue(result.success)
    }

    // ---------------------------------------------------------------------------
    // TC-010: Deadlock exhausted — SystemException after MAX_DEADLOCK_RETRIES
    // Equivalent COBOL: DB2-DEADLOCK-RETRY >= 6 → EXEC CICS ABEND ABCODE('RUF2')
    // ---------------------------------------------------------------------------

    @Test
    fun `TC-010 - deadlock exhausted - SystemException thrown after 5 retries`() {
        val request = TransferRequest("987654", "00000001", "987654", "00000099", BigDecimal("100.00"))

        // Always throw deadlock
        `when`(accountRepository.findBySortCodeAndAccountNumberWithLock(any(), any()))
            .thenThrow(CannotAcquireLockException("Persistent deadlock"))

        val ex = assertThrows<SystemException> { transferService.transfer(request) }

        assertTrue(ex.message!!.contains("RUF2") || ex.message!!.contains("retry limit"))
    }

    // ---------------------------------------------------------------------------
    // TC-011: PROCTRAN FROM write failure → SystemException (WPCD equivalent)
    // Equivalent COBOL: EXEC CICS ABEND ABCODE('WPCD')
    // ---------------------------------------------------------------------------

    @Test
    fun `TC-011 - PROCTRAN FROM write failure - SystemException WPCD equivalent`() {
        val request = TransferRequest("987654", "00000001", "987654", "00000099", BigDecimal("200.00"))

        `when`(accountRepository.findBySortCodeAndAccountNumberWithLock("987654", "00000001"))
            .thenReturn(accountLow)
        `when`(accountRepository.findBySortCodeAndAccountNumberWithLock("987654", "00000099"))
            .thenReturn(accountHigh)
        `when`(accountRepository.save(any())).thenAnswer { it.arguments[0] }
        // All transactionRepository.save() calls throw to simulate PROCTRAN failure
        `when`(transactionRepository.save(any()))
            .thenThrow(RuntimeException("DB2 INSERT error on PROCTRAN"))

        val ex = assertThrows<SystemException> { transferService.transfer(request) }

        assertTrue(ex.message!!.contains("WPCD") || ex.message!!.contains("FROM PROCTRAN"))
    }

    // ---------------------------------------------------------------------------
    // TC-012: PROCTRAN TO write failure → SystemException (WPCT equivalent)
    // Equivalent COBOL: EXEC CICS ABEND ABCODE('WPCT')
    // ---------------------------------------------------------------------------

    @Test
    fun `TC-012 - PROCTRAN TO write failure - SystemException WPCT equivalent`() {
        val request = TransferRequest("987654", "00000001", "987654", "00000099", BigDecimal("200.00"))

        `when`(accountRepository.findBySortCodeAndAccountNumberWithLock("987654", "00000001"))
            .thenReturn(accountLow)
        `when`(accountRepository.findBySortCodeAndAccountNumberWithLock("987654", "00000099"))
            .thenReturn(accountHigh)
        `when`(accountRepository.save(any())).thenAnswer { it.arguments[0] }
        // First save (FROM debit record) succeeds; second (TO credit) throws
        `when`(transactionRepository.save(any()))
            .thenAnswer { it.arguments[0] as TransactionRecord }
            .thenThrow(RuntimeException("DB2 INSERT error on TO PROCTRAN"))

        val ex = assertThrows<SystemException> { transferService.transfer(request) }

        assertTrue(ex.message!!.contains("WPCT") || ex.message!!.contains("TO PROCTRAN"))
    }

    // ---------------------------------------------------------------------------
    // TC-014: Debit amount correctness — verify exact BigDecimal arithmetic
    // Equivalent COBOL: COMPUTE HV-ACCOUNT-AVAIL-BAL = HV-ACCOUNT-AVAIL-BAL - COMM-AMT
    // ---------------------------------------------------------------------------

    @Test
    fun `TC-014 - transfer debit amount correct - available and actual balance both decremented`() {
        val request = TransferRequest("987654", "00000001", "987654", "00000099", BigDecimal("123.45"))

        `when`(accountRepository.findBySortCodeAndAccountNumberWithLock("987654", "00000001"))
            .thenReturn(accountLow)   // 500.00 available
        `when`(accountRepository.findBySortCodeAndAccountNumberWithLock("987654", "00000099"))
            .thenReturn(accountHigh)  // 200.00 available
        `when`(accountRepository.save(any())).thenAnswer { it.arguments[0] }
        `when`(transactionRepository.save(any())).thenAnswer { it.arguments[0] }

        val result = transferService.transfer(request)

        // 500.00 - 123.45 = 376.55
        assertEquals(BigDecimal("376.55"), result.fromAvailableBalance)
        assertEquals(BigDecimal("376.55"), result.fromActualBalance)
    }

    // ---------------------------------------------------------------------------
    // TC-015: Credit amount correctness — verify exact BigDecimal arithmetic
    // Equivalent COBOL: COMPUTE HV-ACCOUNT-AVAIL-BAL = HV-ACCOUNT-AVAIL-BAL + COMM-AMT
    // ---------------------------------------------------------------------------

    @Test
    fun `TC-015 - transfer credit amount correct - available and actual balance both incremented`() {
        val request = TransferRequest("987654", "00000001", "987654", "00000099", BigDecimal("123.45"))

        `when`(accountRepository.findBySortCodeAndAccountNumberWithLock("987654", "00000001"))
            .thenReturn(accountLow)   // 500.00
        `when`(accountRepository.findBySortCodeAndAccountNumberWithLock("987654", "00000099"))
            .thenReturn(accountHigh)  // 200.00
        `when`(accountRepository.save(any())).thenAnswer { it.arguments[0] }
        `when`(transactionRepository.save(any())).thenAnswer { it.arguments[0] }

        val result = transferService.transfer(request)

        // 200.00 + 123.45 = 323.45
        assertEquals(BigDecimal("323.45"), result.toAvailableBalance)
        assertEquals(BigDecimal("323.45"), result.toActualBalance)
    }

    // ---------------------------------------------------------------------------
    // TC-016: Transaction records have correct amounts
    // Equivalent COBOL: COMPUTE HV-PROCTRAN-AMOUNT = COMM-AMT * -1  (FROM)
    //                   MOVE COMM-AMT TO HV-PROCTRAN-AMOUNT           (TO)
    // ---------------------------------------------------------------------------

    @Test
    fun `TC-016 - transaction records have correct amounts - FROM negative TO positive`() {
        val request = TransferRequest("987654", "00000001", "987654", "00000099", BigDecimal("75.00"))

        `when`(accountRepository.findBySortCodeAndAccountNumberWithLock("987654", "00000001"))
            .thenReturn(accountLow)
        `when`(accountRepository.findBySortCodeAndAccountNumberWithLock("987654", "00000099"))
            .thenReturn(accountHigh)
        `when`(accountRepository.save(any())).thenAnswer { it.arguments[0] }
        `when`(transactionRepository.save(transactionCaptor.capture()))
            .thenAnswer { it.arguments[0] }

        transferService.transfer(request)

        val savedRecords = transactionCaptor.allValues
        assertEquals(2, savedRecords.size)

        // First record = FROM debit → negative amount
        val fromRecord = savedRecords[0]
        assertEquals(BigDecimal("-75.00"), fromRecord.amount)
        assertEquals("00000001", fromRecord.accountNumber)

        // Second record = TO credit → positive amount
        val toRecord = savedRecords[1]
        assertEquals(BigDecimal("75.00"), toRecord.amount)
        assertEquals("00000099", toRecord.accountNumber)
    }

    // ---------------------------------------------------------------------------
    // TC-017: Transaction record descriptions are cross-referenced correctly
    // Equivalent COBOL:
    //   MOVE COMM-TSCODE TO PROC-TRAN-DESC-XFR-SORTCODE  (FROM record desc)
    //   MOVE COMM-TACCNO TO PROC-TRAN-DESC-XFR-ACCOUNT   (FROM record desc)
    //   MOVE COMM-FSCODE TO PROC-TRAN-DESC-XFR-SORTCODE  (TO record desc)
    //   MOVE COMM-FACCNO TO PROC-TRAN-DESC-XFR-ACCOUNT   (TO record desc)
    // ---------------------------------------------------------------------------

    @Test
    fun `TC-017 - transaction record descriptions reference the other account`() {
        val request = TransferRequest("987654", "00000001", "987654", "00000099", BigDecimal("50.00"))

        `when`(accountRepository.findBySortCodeAndAccountNumberWithLock("987654", "00000001"))
            .thenReturn(accountLow)
        `when`(accountRepository.findBySortCodeAndAccountNumberWithLock("987654", "00000099"))
            .thenReturn(accountHigh)
        `when`(accountRepository.save(any())).thenAnswer { it.arguments[0] }
        `when`(transactionRepository.save(transactionCaptor.capture()))
            .thenAnswer { it.arguments[0] }

        transferService.transfer(request)

        val savedRecords = transactionCaptor.allValues

        // FROM record description should reference TO account
        val fromDesc = savedRecords[0].description
        assertTrue(fromDesc.contains("00000099"), "FROM desc should mention TO account: $fromDesc")

        // TO record description should reference FROM account
        val toDesc = savedRecords[1].description
        assertTrue(toDesc.contains("00000001"), "TO desc should mention FROM account: $toDesc")
    }

    // ---------------------------------------------------------------------------
    // TC-018: Overdraft — succeeds by design (no overdraft check in XFRFUN)
    // Equivalent COBOL comment: "No checking is made on overdraft limits"
    // ---------------------------------------------------------------------------

    @Test
    fun `TC-018 - overdraft transfer - succeeds by design - no overdraft check`() {
        val request = TransferRequest(
            fromSortCode        = "987654",
            fromAccountNumber   = "00000001",
            toSortCode          = "987654",
            toAccountNumber     = "00000099",
            amount              = BigDecimal("1000.00")  // more than the 500.00 balance
        )

        `when`(accountRepository.findBySortCodeAndAccountNumberWithLock("987654", "00000001"))
            .thenReturn(accountLow)   // 500.00 — will go negative
        `when`(accountRepository.findBySortCodeAndAccountNumberWithLock("987654", "00000099"))
            .thenReturn(accountHigh)
        `when`(accountRepository.save(any())).thenAnswer { it.arguments[0] }
        `when`(transactionRepository.save(any())).thenAnswer { it.arguments[0] }

        val result = transferService.transfer(request)

        // Transfer succeeds — balance goes negative (500.00 - 1000.00 = -500.00)
        assertTrue(result.success)
        assertEquals(BigDecimal("-500.00"), result.fromAvailableBalance)
    }
}
