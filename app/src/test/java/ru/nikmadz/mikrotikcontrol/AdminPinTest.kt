package ru.nikmadz.mikrotikcontrol

import org.junit.Assert.*
import org.junit.Test
import ru.nikmadz.mikrotikcontrol.security.*

class AdminPinTest {
    private class MemoryStore(var value: String? = null) : PinRecordStore {
        var writes = 0
        var failRead = false
        var failWrite = false
        override fun read(): String? {
            check(!failRead) { "read failed" }
            return value
        }
        override fun write(record: String) {
            check(!failWrite) { "write failed" }
            value = record
            writes++
        }
    }

    @Test fun initialDefaultIsPersistedAsCredentialAndSurvivesServiceRecreation() {
        val store = MemoryStore()
        assertEquals(PinCheck.ACCEPTED, AdminPinService(store) { 0L }.verify("0000"))
        assertNotEquals("0000", store.value)
        assertEquals(1, store.writes)
        assertEquals(PinCheck.ACCEPTED, AdminPinService(store) { 0L }.verify("0000"))
        assertEquals(1, store.writes)
    }

    @Test fun changedPinSurvivesRecreationAndDefaultStopsWorking() {
        val store = MemoryStore()
        assertEquals(PinCheck.ACCEPTED, AdminPinService(store) { 0L }.change("0000", "5821", "5821"))
        val recreated = AdminPinService(store) { 0L }
        assertEquals(PinCheck.WRONG, recreated.verify("0000"))
        assertEquals(PinCheck.ACCEPTED, recreated.verify("5821"))
    }

    @Test fun wrongCurrentPinDoesNotReplaceCredential() {
        val record = PinCredentials.create("5821")
        val store = MemoryStore(record)
        assertEquals(PinCheck.WRONG, AdminPinService(store) { 0L }.change("0000", "7143", "7143"))
        assertEquals(record, store.value)
        assertEquals(0, store.writes)
    }

    @Test fun mismatchedConfirmationDoesNotWriteOrInitializeDefault() {
        val store = MemoryStore()
        assertThrows(IllegalArgumentException::class.java) {
            AdminPinService(store) { 0L }.change("0000", "5821", "5822")
        }
        assertNull(store.value)
        assertEquals(0, store.writes)
    }

    @Test fun onlyExactlyFourAsciiDigitsAreAccepted() {
        for (pin in listOf("", "0", "12345", "12a4", "１２３４", " 123", "12\n3")) {
            assertFalse(PinCredentials.valid(pin))
            assertThrows(IllegalArgumentException::class.java) { PinCredentials.create(pin) }
        }
        assertTrue(PinCredentials.valid("0000"))
        assertTrue(PinCredentials.valid("0123"))
    }

    @Test fun randomSaltChangesRecordButNotVerification() {
        val first = PinCredentials.create("0123")
        val second = PinCredentials.create("0123")
        assertNotEquals(first, second)
        assertTrue(PinCredentials.matches(first, "0123"))
        assertTrue(PinCredentials.matches(second, "0123"))
        assertFalse(PinCredentials.matches(first, "1230"))
    }

    @Test fun malformedRecordFailsClosedWithoutDefaultReset() {
        for (record in listOf("", "corrupt", "2:120000:a:b", "1:999999999:a:b", "1:120000:?:?", "1:120000:AA==:AA==", "x".repeat(129))) {
            val store = MemoryStore(record)
            assertThrows(Exception::class.java) { AdminPinService(store) { 0L }.verify("0000") }
            assertEquals(record, store.value)
            assertEquals(0, store.writes)
        }
    }

    @Test fun failedReadDoesNotCreateDefault() {
        val store = MemoryStore().apply { failRead = true }
        assertThrows(IllegalStateException::class.java) { AdminPinService(store) { 0L }.verify("0000") }
        assertEquals(0, store.writes)
    }

    @Test fun failedInitialWriteDoesNotGrantAccess() {
        val store = MemoryStore().apply { failWrite = true }
        assertThrows(IllegalStateException::class.java) { AdminPinService(store) { 0L }.verify("0000") }
        assertNull(store.value)
    }

    @Test fun failedChangeIsNotReportedAsAccepted() {
        val record = PinCredentials.create("5821")
        val store = MemoryStore(record).apply { failWrite = true }
        assertThrows(IllegalStateException::class.java) { AdminPinService(store) { 0L }.change("5821", "7143", "7143") }
        assertEquals(record, store.value)
    }

    @Test fun fiveWrongPinsBlockEvenCorrectPinForThirtySeconds() {
        var now = 100L
        val service = AdminPinService(MemoryStore()) { now }
        repeat(4) { assertEquals(PinCheck.WRONG, service.verify("9999")) }
        assertEquals(PinCheck.WAIT, service.verify("9999"))
        assertEquals(PinCheck.WAIT, service.verify("0000"))
        now += 29_999
        assertEquals(PinCheck.WAIT, service.verify("0000"))
        now++
        assertEquals(PinCheck.ACCEPTED, service.verify("0000"))
    }

    @Test fun successfulLoginResetsFailedAttemptCounter() {
        val service = AdminPinService(MemoryStore()) { 0L }
        repeat(4) { assertEquals(PinCheck.WRONG, service.verify("9999")) }
        assertEquals(PinCheck.ACCEPTED, service.verify("0000"))
        repeat(4) { assertEquals(PinCheck.WRONG, service.verify("9999")) }
    }

    @Test fun activeChildActivityKeepsSessionButBackgroundLocksIt() {
        val session = AdminSessionState()
        assertFalse(session.unlocked)
        session.activityStarted()
        session.unlock()
        session.activityStarted()
        session.activityStopped(false)
        assertTrue(session.unlocked)
        session.activityStopped(false)
        assertFalse(session.unlocked)
    }

    @Test fun rotationKeepsSessionThenNormalStopLocksIt() {
        val session = AdminSessionState()
        session.activityStarted(); session.unlock()
        session.activityStopped(true)
        session.activityStarted()
        assertTrue(session.unlocked)
        session.activityStopped(false)
        assertFalse(session.unlocked)
    }

    @Test fun explicitReturnToOperatorAndNewProcessHaveNoGrant() {
        val session = AdminSessionState()
        session.unlock(); session.lock()
        assertFalse(session.unlocked)
        session.unlock()
        assertFalse(AdminSessionState().unlocked)
    }
}
