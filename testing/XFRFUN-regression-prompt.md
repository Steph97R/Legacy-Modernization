# XFRFUN — Regression Testing

> **Program:** XFRFUN (Transfer Funds)  
> **Source:** `Bank-Of-Z/src/base/cics/cobol/XFRFUN.cbl`  
> **Environment:** CICS/DB2 z/OS  

---

## Section 1 — AI Regression Test Generation Prompt

Paste this prompt into an AI assistant to generate a complete regression test suite for XFRFUN.

---

```
You are a mainframe testing expert. Generate a comprehensive regression test suite for the
COBOL CICS/DB2 program XFRFUN, which transfers funds between two bank accounts.

## Program Description

XFRFUN is a CICS program that performs bank fund transfers. It is called by passing a
COMMAREA structure containing the transfer request. It updates two DB2 ACCOUNT rows and
writes two PROCTRAN (transaction ledger) rows, all within a single atomic DB2 unit of work.

## COMMAREA Interface (Input/Output Contract)

Input fields:
- COMM-FACCNO  (8-digit numeric)  : FROM account number
- COMM-FSCODE  (6-digit numeric)  : FROM sort code
- COMM-TACCNO  (8-digit numeric)  : TO account number
- COMM-TSCODE  (6-digit numeric)  : TO sort code
- COMM-AMT     (signed decimal, 2dp) : Transfer amount

Output fields:
- COMM-FAVBAL  (signed decimal) : FROM account available balance after transfer
- COMM-FACTBAL (signed decimal) : FROM account actual balance after transfer
- COMM-TAVBAL  (signed decimal) : TO account available balance after transfer
- COMM-TACTBAL (signed decimal) : TO account actual balance after transfer
- COMM-SUCCESS (1 char)         : 'Y' = success, 'N' = failure
- COMM-FAIL-CODE (1 char)       : '1'=FROM not found, '2'=TO not found, '3'=DB2 error, '4'=bad amount

## Business Rules

1. COMM-AMT must be > 0. If zero or negative, set COMM-SUCCESS='N', COMM-FAIL-CODE='4', return.
2. FROM and TO must not be the same account (same sort code + account number). Violation causes
   CICS abend with code 'SAME'. No COMMAREA output is set.
3. DB2 ACCOUNT rows are always updated in ascending account-number order (lower account number
   first), regardless of direction. This prevents deadlocks.
4. If the FROM account SELECT returns SQLCODE +100 (not found): COMM-SUCCESS='N', COMM-FAIL-CODE='1',
   no DB2 update, no rollback needed.
5. If the TO account SELECT returns SQLCODE +100 (not found): COMM-SUCCESS='N', COMM-FAIL-CODE='2',
   SYNCPOINT ROLLBACK is issued (may have already updated FROM account).
6. Any non-zero, non-+100 SQLCODE: COMM-SUCCESS='N', COMM-FAIL-CODE='3'.
7. DB2 deadlock (SQLCODE -911): retry the entire transfer up to 5 times with a 1-second delay
   between retries. On the 6th failure, abend with code 'RUF2'.
8. On success: two PROCTRAN rows are inserted — FROM record with negative amount (debit),
   TO record with positive amount (credit).
9. PROCTRAN insert failure causes CICS abend (WPCD for FROM record, WPCT for TO record). By this
   point the ACCOUNT rows are already updated — this is a known data inconsistency risk.
10. SYNCPOINT ROLLBACK failure causes CICS abend 'HROL'.
11. No overdraft check is performed. A transfer that makes the FROM balance negative will succeed.

## Test Requirements

Generate the following for each test case:
1. Test ID and descriptive name
2. Pre-condition: initial state of DB2 ACCOUNT table (account balances)
3. COMMAREA input values
4. Expected COMMAREA output values (COMM-SUCCESS, COMM-FAIL-CODE, balances if applicable)
5. Expected DB2 state after execution (ACCOUNT balances, PROCTRAN records)
6. Expected CICS abend code (if any)
7. Pass/fail assertion criteria

Generate test cases for ALL of the following scenarios:
- TC-001: Successful transfer where FROM account number is less than TO account number
- TC-002: Successful transfer where FROM account number is greater than TO account number (lock order reverses)
- TC-003: Zero amount
- TC-004: Negative amount
- TC-005: Same account (FROM == TO on both sort code and account number)
- TC-006: FROM account does not exist in DB2
- TC-007: TO account does not exist in DB2 (FROM account exists and may have been updated before failure)
- TC-008: DB2 error on FROM account SELECT (non-+100 SQLCODE)
- TC-009: DB2 deadlock on retry — succeeds on 3rd attempt
- TC-010: DB2 deadlock exhausted — fails after 5 retries
- TC-011: PROCTRAN FROM record insert fails
- TC-012: PROCTRAN TO record insert fails
- TC-013: SYNCPOINT ROLLBACK fails
- TC-014: Large transfer (boundary values — max balance field size)
- TC-015: Transfer that results in negative FROM balance (overdraft — should succeed per design)

Additionally, generate a CICS test harness stub (pseudo-code) showing how to:
- Set up the COMMAREA before calling XFRFUN
- Invoke XFRFUN via EXEC CICS LINK
- Read COMMAREA after return
- Assert expected output values
- Check CICS EIBRESP for abend detection

Use COBOL-style pseudo-code for the test harness. Format each test case as a structured block
with clearly labelled sections: GIVEN / WHEN / THEN.
```

---

## Section 2 — Test Case Matrix

| Test ID | Test Name | Description | FROM Sort/Account | TO Sort/Account | Amount | Pre-condition (DB State) | Expected COMM-SUCCESS | Expected COMM-FAIL-CODE | Expected PROCTRAN Records | Expected Abend / Error |
|---|---|---|---|---|---|---|---|---|---|---|
| TC-001 | Happy path — FROM < TO | Normal transfer, FROM account number less than TO | `987654` / `00000001` | `987654` / `00000099` | `100.00` | FROM: avail=500.00, actual=500.00; TO: avail=200.00, actual=200.00 | `Y` | _(blank)_ | FROM debit: -100.00; TO credit: +100.00 | None |
| TC-002 | Happy path — FROM > TO | Normal transfer, FROM account number greater than TO (lock order reversed internally) | `987654` / `00000099` | `987654` / `00000001` | `50.00` | FROM: avail=300.00, actual=300.00; TO: avail=100.00, actual=100.00 | `Y` | _(blank)_ | FROM debit: -50.00; TO credit: +50.00 | None |
| TC-003 | Zero amount | Transfer amount is exactly zero | `987654` / `00000001` | `987654` / `00000002` | `0.00` | Any valid accounts | `N` | `4` | None | None (returns before DB2 access) |
| TC-004 | Negative amount | Transfer amount is negative | `987654` / `00000001` | `987654` / `00000002` | `-25.00` | Any valid accounts | `N` | `4` | None | None (returns before DB2 access) |
| TC-005 | Same account | FROM and TO are identical (same sort code AND account number) | `987654` / `00000001` | `987654` / `00000001` | `100.00` | Account exists with balance 500.00 | _(not set)_ | _(not set)_ | None | `SAME` (CICS abend) |
| TC-006 | FROM account not found | FROM account does not exist in ACCOUNT table | `987654` / `99999999` | `987654` / `00000002` | `75.00` | TO account exists; FROM does not | `N` | `1` | None | None |
| TC-007 | TO account not found | TO account does not exist; FROM account may be updated then rolled back | `987654` / `00000001` | `987654` / `99999999` | `50.00` | FROM account exists; TO does not | `N` | `2` | None (rolled back) | None; SYNCPOINT ROLLBACK issued |
| TC-008 | DB2 error on FROM SELECT | Non-+100 SQLCODE on FROM account SELECT (e.g., -803) | `987654` / `00000001` | `987654` / `00000002` | `50.00` | Simulate DB2 error on SELECT | `N` | `3` | None | None (exits gracefully) |
| TC-009 | Deadlock — retry succeeds | DB2 deadlock on 1st and 2nd attempts; succeeds on 3rd | `987654` / `00000001` | `987654` / `00000002` | `100.00` | Both accounts exist; simulate SQLCODE -911 twice then success | `Y` | _(blank)_ | FROM debit: -100.00; TO credit: +100.00 | None; DB2-DEADLOCK-RETRY=2 after success |
| TC-010 | Deadlock — retries exhausted | DB2 deadlock on all 6 attempts | `987654` / `00000001` | `987654` / `00000002` | `100.00` | Both accounts exist; simulate SQLCODE -911 on all attempts | _(not set)_ | _(not set)_ | None (rolled back) | `RUF2` (CICS abend) |
| TC-011 | PROCTRAN FROM write fail | Both ACCOUNT rows updated successfully; INSERT to PROCTRAN for FROM record fails | `987654` / `00000001` | `987654` / `00000002` | `200.00` | Both accounts exist; simulate INSERT error for 1st PROCTRAN | _(not set)_ | _(not set)_ | None (abend before TO record) | `WPCD` — data inconsistency: ACCOUNT rows updated, no audit trail |
| TC-012 | PROCTRAN TO write fail | FROM debit record written; INSERT to PROCTRAN for TO record fails | `987654` / `00000001` | `987654` / `00000002` | `200.00` | Both accounts exist; simulate INSERT error for 2nd PROCTRAN | _(not set)_ | _(not set)_ | FROM debit written; TO credit not written | `WPCT` — data inconsistency: 1 of 2 audit records missing |
| TC-013 | SYNCPOINT ROLLBACK fails | TO account not found; SYNCPOINT ROLLBACK itself returns non-NORMAL RESP | `987654` / `00000001` | `987654` / `99999999` | `50.00` | FROM exists; TO does not; simulate ROLLBACK failure | _(not set)_ | _(not set)_ | None | `HROL` (CICS abend) |
| TC-014 | Large transfer — boundary values | Transfer near maximum field width (COMM-AMT = S9(10)V99) | `987654` / `00000001` | `987654` / `00000002` | `9999999999.99` | FROM: avail=9999999999.99, actual=9999999999.99; TO: avail=0.00 | `Y` | _(blank)_ | FROM debit: -9999999999.99; TO credit: +9999999999.99 | None; verify no numeric overflow |
| TC-015 | Overdraft — succeeds by design | Transfer causes FROM balance to go negative (no overdraft check in XFRFUN) | `987654` / `00000001` | `987654` / `00000002` | `1000.00` | FROM: avail=100.00, actual=100.00; TO: avail=50.00 | `Y` | _(blank)_ | FROM debit: -1000.00 (balance → -900.00); TO credit: +1000.00 | None — this is expected behaviour per design |

---

### Notes for Test Execution

- **TC-007, TC-011, TC-012, TC-013** require fault injection or mocking of DB2 responses. On a live z/OS system, use a test stub program or CICS LINK intercept.
- **TC-009, TC-010** require the ability to simulate `SQLCODE -911` with `SQLERRD(3) = 13172872`. This can be achieved via a DB2 test stub or by inducing a real deadlock with a concurrent transaction holding the row lock.
- **TC-013** is extremely difficult to test in a real CICS/DB2 environment as SYNCPOINT ROLLBACK rarely fails. Consider unit testing this path via a CICS stub framework (e.g., CICS TDQ intercept or z/OS Unit Testing Framework).
- **PROCTRAN records** should be verified by querying the DB2 PROCTRAN table after each successful test, matching on `PROCTRAN_SORTCODE`, `PROCTRAN_NUMBER`, and `PROCTRAN_REF` (CICS task number).
- **Balance assertions** should check both `ACCOUNT_AVAILABLE_BALANCE` and `ACCOUNT_ACTUAL_BALANCE` as XFRFUN updates both fields identically.

---

*Generated by Bob — IBM Z Modernization Assistant*  
*Source: Bank-Of-Z `XFRFUN.cbl` (IBM Corp. 2023)*
