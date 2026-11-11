package br.com.nexotools.domain

import org.junit.Assert.*
import org.junit.Test

class UninstallBatchTest {
    private val first = "org.example.fixture.first"
    private val second = "org.example.fixture.second"

    @Test fun selectionIsDistinctOrderedAndDetachedFromTheOriginalList() {
        val selected = mutableListOf(first, "", second, first, "  ", second, "\t")
        val batch = UninstallBatch.start(selected)
        selected.clear()
        assertEquals(listOf(first, second), batch.remaining)
        assertEquals(2, batch.total)
        assertEquals(0, batch.removed)
        assertTrue(batch.running)
        assertNull(batch.inFlight)
        assertFalse(batch.resultReturned)
    }

    @Test fun emptySelectionNeverStartsOrAcceptsLateEvents() {
        val batch = UninstallBatch.start(listOf("", "  ", "\t"))
        assertFalse(batch.running)
        assertEquals(0, batch.total)
        assertSame(batch, batch.launched(first))
        assertSame(batch, batch.returned())
        assertSame(batch, batch.resolved(first, stillInstalled = false))
    }

    @Test fun onlyQueueHeadCanLaunchAndAnInFlightStepCannotBeReplaced() {
        val initial = UninstallBatch.start(listOf(first, second))
        assertSame(initial, initial.launched(second))
        assertSame(initial, initial.returned())
        val launched = initial.launched(first)
        assertEquals(first, launched.inFlight)
        assertSame(launched, launched.launched(second))
        assertSame(launched, launched.launched(first))
        assertEquals(listOf(first, second), launched.remaining)
    }

    @Test fun seeingRemovalBeforeActivityReturnDoesNotAdvanceTheQueue() {
        val launched = UninstallBatch.start(listOf(first, second)).launched(first)
        assertSame(launched, launched.resolved(first, stillInstalled = false))
        assertEquals(0, launched.removed)
        assertEquals(listOf(first, second), launched.remaining)
    }

    @Test fun activityReturnAloneDoesNotProveRemovalOrLaunchTheNextApp() {
        val returned = UninstallBatch.start(listOf(first, second)).launched(first).returned()
        assertTrue(returned.resultReturned)
        assertEquals(first, returned.inFlight)
        assertEquals(0, returned.removed)
        assertEquals(listOf(first, second), returned.remaining)
        assertSame(returned, returned.launched(second))
    }

    @Test fun confirmedRemovalsAdvanceExactlyOnceAndResetTheReturnGate() {
        val afterFirst = UninstallBatch.start(listOf(first, second)).launched(first).returned()
            .resolved(first, stillInstalled = false)
        assertEquals(listOf(second), afterFirst.remaining)
        assertEquals(1, afterFirst.removed)
        assertEquals(2, afterFirst.total)
        assertNull(afterFirst.inFlight)
        assertFalse(afterFirst.resultReturned)
        assertTrue(afterFirst.running)
        assertSame(afterFirst, afterFirst.resolved(second, stillInstalled = false))

        val secondLaunched = afterFirst.launched(second)
        assertSame(secondLaunched, secondLaunched.resolved(second, stillInstalled = false))
        val finished = secondLaunched.returned().resolved(second, stillInstalled = false)
        assertFalse(finished.running)
        assertTrue(finished.remaining.isEmpty())
        assertEquals(2, finished.removed)
        assertEquals(2, finished.total)
        assertNull(finished.inFlight)
        assertFalse(finished.resultReturned)
    }

    @Test fun cancellationOrFailedRemovalStopsInsteadOfSkippingToAnotherApp() {
        val afterFirst = UninstallBatch.start(listOf(first, second)).launched(first).returned()
            .resolved(first, stillInstalled = false)
        val stopped = afterFirst.launched(second).returned().resolved(second, stillInstalled = true)
        assertFalse(stopped.running)
        assertTrue(stopped.remaining.isEmpty())
        assertEquals(1, stopped.removed)
        assertEquals(2, stopped.total)
        assertNull(stopped.inFlight)
        assertFalse(stopped.resultReturned)
        assertSame(stopped, stopped.launched(second))
    }

    @Test fun userStopWaitsForPendingConfirmationWithoutLaunchingAnotherApp() {
        val stopped = UninstallBatch.start(listOf(first, second)).launched(first).stop()
        assertTrue(stopped.running)
        assertTrue(stopped.remaining.isEmpty())
        assertEquals(first, stopped.inFlight)
        assertEquals(0, stopped.removed)
        assertEquals(2, stopped.total)
        assertSame(stopped, stopped.resolved(first, stillInstalled = false))
        assertSame(stopped, stopped.launched(second))
        val finished = stopped.returned().resolved(first, stillInstalled = false)
        assertFalse(finished.running)
        assertNull(finished.inFlight)
        assertEquals(1, finished.removed)
        assertSame(finished, finished.launched(second))
        assertSame(finished, finished.returned())
        val canceled = stopped.returned().resolved(first, stillInstalled = true)
        assertFalse(canceled.running)
        assertEquals(0, canceled.removed)
    }

    @Test fun interruptionBetweenStepsPreservesOnlyAlreadyConfirmedRemovals() {
        val stopped = UninstallBatch.start(listOf(first, second)).launched(first).returned()
            .resolved(first, stillInstalled = false).stop()
        assertFalse(stopped.running)
        assertEquals(1, stopped.removed)
        assertEquals(2, stopped.total)
        assertSame(stopped, stopped.returned())
        assertSame(stopped, stopped.resolved(second, stillInstalled = false))
    }

    @Test fun duplicateReturnBeforeResolutionAndOldResolvedCallbacksCannotAdvanceAnotherStep() {
        val onceReturned = UninstallBatch.start(listOf(first, second)).launched(first).returned()
        assertEquals(onceReturned, onceReturned.returned())
        assertSame(onceReturned, onceReturned.resolved(second, stillInstalled = false))

        val afterFirst = onceReturned.resolved(first, stillInstalled = false)
        assertSame(afterFirst, afterFirst.resolved(first, stillInstalled = false))
        val secondLaunched = afterFirst.launched(second)
        assertSame(secondLaunched, secondLaunched.resolved(first, stillInstalled = false))
        val secondReturned = secondLaunched.returned()
        assertSame(secondReturned, secondReturned.resolved(first, stillInstalled = false))
        assertEquals(listOf(second), secondReturned.remaining)
        assertEquals(1, secondReturned.removed)
        assertEquals(second, secondReturned.inFlight)
        val finished = secondReturned.resolved(second, stillInstalled = false)
        assertEquals(2, finished.removed)
        assertSame(finished, finished.resolved(second, stillInstalled = false))
    }
}
