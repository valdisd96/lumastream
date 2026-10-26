package dev.lumastream.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RollingPieceWindowTest {
    @Test fun sequentialReadsDisjointSeeksAndBackseeksStayWithinHardBound() {
        val policy = RollingPieceWindow(1000, 1024, 13L * 1024, 4L * 1024, 3L * 1024)
        val admitted = mutableSetOf<Int>()
        var rollovers = 0
        val demands = (0..250).toList() + List(2000) { (it * 379) % 1000 } + (250 downTo 0).toList()
        for (demand in demands) {
            val end = policy.windowEnd(demand, 999)
            val incoming = (demand..end).toSet()
            val exceeds = (admitted + incoming).size > 13
            assertEquals(exceeds, policy.needsEviction(demand, end))
            if (exceeds) {
                val startRetained = policy.retainedStart(demand, 0)
                val endRetained = policy.retainedEnd(demand, 999)
                policy.reset(startRetained, endRetained)
                admitted.retainAll((startRetained..endRetained).toSet())
                assertEquals(admitted.size * 1024L, policy.reservedBytes)
                assertFalse(policy.needsEviction(demand, end))
                rollovers++
            }
            // No completion signal is ever supplied: cancelled partial pieces still reserve space.
            policy.admit(demand, end)
            admitted.addAll(incoming)
            assertEquals(admitted.size * 1024L, policy.reservedBytes)
            assertTrue(policy.reservedBytes <= policy.capacityBytes)
        }
        assertTrue("The scenario must exercise repeated rollover", rollovers > 100)
    }

    @Test fun overlappingAdmissionCountsOnlyNewPiecesAndFailedAdmissionIsAtomic() {
        val policy = RollingPieceWindow(100, 1024, 5L * 1024 + 1023, 1024, 0)
        policy.admit(10, 12)
        policy.admit(11, 14)
        policy.admit(10, 14)
        assertEquals(5L * 1024, policy.reservedBytes)
        assertFalse(policy.needsEviction(11, 13))
        assertTrue(policy.needsEviction(14, 15))
        assertThrows(IllegalStateException::class.java) { policy.admit(14, 15) }
        assertEquals(5L * 1024, policy.reservedBytes)
        policy.reset(12, 15)
        assertEquals(3L * 1024, policy.reservedBytes)
        policy.admit(15, 15)
        assertEquals(4L * 1024, policy.reservedBytes)
    }

    @Test fun rolloverIntersectsRatherThanInventingAdmissionsAndAllowsReadmission() {
        val policy = RollingPieceWindow(100, 1024, 8L * 1024, 2L * 1024, 1024)
        policy.admit(0, 2)
        policy.admit(50, 51)
        policy.admit(99, 99)
        policy.reset(49, 52)
        assertEquals(2L * 1024, policy.reservedBytes)
        policy.admit(0, 2)
        assertEquals(5L * 1024, policy.reservedBytes)
        policy.reset(70, 73)
        assertEquals(0L, policy.reservedBytes)
        policy.admit(99, 99)
        assertEquals(1024L, policy.reservedBytes)
    }

    @Test fun windowsRespectSelectedFileBoundsRoundingAndMaximumPieceCount() {
        val policy = RollingPieceWindow(100, 1024, 10L * 1024, 2049, 1025)
        assertEquals(11, policy.windowEnd(10, 99))
        assertEquals(11, policy.windowEnd(10, 11))
        assertEquals(8, policy.retainedStart(10, 0))
        assertEquals(9, policy.retainedStart(10, 9))
        assertEquals(99, policy.retainedEnd(99, 99))
        val wide = RollingPieceWindow(100, 1024, 100L * 1024, Long.MAX_VALUE, 0)
        assertEquals(41, wide.windowEnd(10, 99))
        val demandOnly = RollingPieceWindow(100, 1024, 2L * 1024, 0, 0)
        assertEquals(10, demandOnly.windowEnd(10, 99))
        assertEquals(10, demandOnly.retainedStart(10, 0))
    }

    @Test fun capacityMustFitRetainedNeighborhoodPlusRefillHeadroom() {
        assertThrows(IllegalArgumentException::class.java) {
            RollingPieceWindow(100, 1024, 8L * 1024 - 1, 4L * 1024, 3L * 1024)
        }
        val policy = RollingPieceWindow(100, 1024, 8L * 1024, 4L * 1024, 3L * 1024)
        policy.admit(7, 13)
        policy.reset(policy.retainedStart(10, 0), policy.retainedEnd(10, 99))
        assertFalse(policy.needsEviction(11, policy.windowEnd(11, 99)))
        policy.admit(11, policy.windowEnd(11, 99))
        assertEquals(8L * 1024, policy.reservedBytes)
    }

    @Test fun shortFinalPieceStillReservesAWholePiece() {
        // A 1025-byte torrent has two 1024-byte pieces; its one-byte tail is not discounted.
        assertThrows(IllegalArgumentException::class.java) { RollingPieceWindow(2, 1024, 1025) }
        val policy = RollingPieceWindow(2, 1024, 2048)
        policy.admit(1, 1)
        assertEquals(1024L, policy.reservedBytes)
        policy.admit(0, 0)
        assertEquals(2048L, policy.reservedBytes)
    }

    @Test fun invalidDimensionsAndIntervalsAreRejectedWithoutChangingReservation() {
        assertThrows(IllegalArgumentException::class.java) { RollingPieceWindow(0, 1024, 4096) }
        assertThrows(IllegalArgumentException::class.java) { RollingPieceWindow(10, 0, 4096) }
        assertThrows(IllegalArgumentException::class.java) { RollingPieceWindow(10, 1024, 0) }
        assertThrows(IllegalArgumentException::class.java) { RollingPieceWindow(10, 1024, 4096, -1, 0) }
        assertThrows(IllegalArgumentException::class.java) { RollingPieceWindow(10, 1024, 4096, 0, -1) }
        val policy = RollingPieceWindow(10, 1024, 4096, 0, 0)
        policy.admit(2, 2)
        assertThrows(IllegalArgumentException::class.java) { policy.admit(-1, 2) }
        assertThrows(IllegalArgumentException::class.java) { policy.admit(2, 10) }
        assertThrows(IllegalArgumentException::class.java) { policy.needsEviction(4, 3) }
        assertThrows(IllegalArgumentException::class.java) { policy.reset(4, 3) }
        assertThrows(IllegalArgumentException::class.java) { policy.retainedStart(2, 3) }
        assertThrows(IllegalArgumentException::class.java) { policy.windowEnd(10, 10) }
        assertEquals(1024L, policy.reservedBytes)
    }

    @Test fun prefetchIncludesCancelledAdmissionsAndKeepsOneAuxiliaryPieceAvailable() {
        val policy = RollingPieceWindow(100, 1024, 20L * 1024, 4L * 1024, 2L * 1024)
        policy.admit(10, 13)
        var next = 14
        while (true) {
            val end = policy.prefetchEnd(next, 99, enabled = true)
            if (end < next) break
            assertTrue(end - next < 4)
            policy.admitPrefetch(10, next, end)
            next = end + 1
        }
        // No pieces are reported complete: cancelled/in-flight writes still consume budget.
        assertEquals(29, next)
        assertEquals(19L * 1024, policy.reservedBytes)
        assertFalse(policy.needsEviction(90, 90))
        policy.admit(90, 90)
        assertEquals(20L * 1024, policy.reservedBytes)
        assertEquals(28, policy.prefetchEnd(29, 99, enabled = true))
        // Previously admitted partial pieces can restart without a second reservation.
        assertEquals(17, policy.prefetchEnd(14, 99, enabled = true))
        assertEquals(20L * 1024, policy.reservedBytes)
    }

    @Test fun disablingPrefetchStopsAdmissionWithoutDroppingForwardReserve() {
        val policy = RollingPieceWindow(100, 1024, 16L * 1024, 4L * 1024, 3L * 1024)
        policy.admit(0, 3)
        policy.admitPrefetch(0, 4, 7)
        policy.admitPrefetch(0, 8, 11)
        policy.admitPrefetch(0, 12, 14)
        assertEquals(14, policy.prefetchEnd(15, 99, enabled = false))
        assertEquals(15L * 1024, policy.reservedBytes)
        assertEquals(14, policy.retainedEnd(1, 99))
        policy.admit(90, 90)
        for (demand in 1..11) {
            val end = policy.windowEnd(demand, 99)
            assertFalse("Resume must use the cached reserve before rolling over", policy.needsEviction(demand, end))
            policy.admit(demand, end)
        }
        assertTrue(policy.needsEviction(12, policy.windowEnd(12, 99)))
        val keepStart = policy.retainedStartForAdmission(12, 0, 99, 12, 15)
        assertEquals(9, keepStart)
        policy.reset(keepStart, policy.retainedEnd(12, 99))
        assertFalse(policy.needsEviction(12, 14))
        policy.admit(12, 15)
        assertEquals(7L * 1024, policy.reservedBytes)
    }

    @Test fun auxiliaryRolloverReclaimsLookbehindNotPausedForwardReserve() {
        val policy = RollingPieceWindow(100, 1024, 12L * 1024, 2L * 1024, 3L * 1024)
        policy.admit(10, 11)
        var next = 12
        while (next <= 20) {
            val end = policy.prefetchEnd(next, 99, enabled = true)
            policy.admitPrefetch(10, next, end)
            next = end + 1
        }
        // An auxiliary piece inside lookbehind consumes the last slot.
        policy.admit(9, 9)
        assertTrue(policy.needsEviction(90, 90))
        val keepStart = policy.retainedStartForAdmission(10, 0, 99, 90, 90)
        val keepEnd = policy.retainedEnd(10, 99)
        assertEquals(10, keepStart)
        assertEquals(20, keepEnd)
        policy.reset(keepStart, keepEnd)
        policy.admit(90, 90)
        assertEquals(12L * 1024, policy.reservedBytes)
        assertEquals(20, policy.retainedEnd(10, 99))
        assertEquals(20, policy.prefetchEnd(21, 99, enabled = true))
    }

    @Test fun seeksWithinReserveReuseItAndDistantSeeksRedirectRetention() {
        val policy = RollingPieceWindow(100, 1024, 12L * 1024, 2L * 1024, 3L * 1024)
        policy.admit(10, 11)
        policy.admitPrefetch(10, 12, 20)
        policy.redirectDemand(15)
        assertEquals(20, policy.retainedEnd(15, 99))
        policy.redirectDemand(70)
        assertEquals(71, policy.retainedEnd(70, 99))
        assertTrue(policy.needsEviction(70, 71))
        policy.reset(policy.retainedStartForAdmission(70, 0, 99, 70, 71), policy.retainedEnd(70, 99))
        policy.admit(70, 71)
        policy.admitPrefetch(70, 72, policy.prefetchEnd(72, 99, enabled = true))
        assertEquals(4L * 1024, policy.reservedBytes)
        assertEquals(73, policy.retainedEnd(70, 99))
    }

    @Test fun prefetchStopsAtSelectedFileEndAndUsesOrdinaryBatchBound() {
        val policy = RollingPieceWindow(1000, 1024, 1000L * 1024, Long.MAX_VALUE, 0)
        policy.admit(0, 31)
        assertEquals(63, policy.prefetchEnd(32, 999, enabled = true))
        assertEquals(40, policy.prefetchEnd(32, 40, enabled = true))
        policy.admitPrefetch(0, 32, 40)
        assertEquals(40, policy.retainedEnd(0, 40))
    }

    @Test fun verifiedAheadCountsOnlyContiguousResidentBytesFromReaderCursor() {
        val ahead = VerifiedReaderAhead(fileOffset = 500, fileSize = 3500, pieceLength = 1024)
        ahead.record(3) // A distant movie-hash read must not move the video reader.
        assertEquals(0L, ahead.bytesAhead())
        assertFalse(ahead.reachesEnd())
        ahead.moveReader(0)
        ahead.record(0)
        assertEquals(524L, ahead.bytesAhead())
        ahead.record(2)
        assertEquals(524L, ahead.bytesAhead())
        assertFalse(ahead.reachesEnd())
        ahead.record(1)
        assertEquals(3500L, ahead.bytesAhead())
        assertTrue(ahead.reachesEnd())
        ahead.moveReader(1000)
        assertEquals(2500L, ahead.bytesAhead())
        ahead.moveReader(3500)
        assertEquals(0L, ahead.bytesAhead())
        ahead.moveReader(2000)
        assertEquals(1500L, ahead.bytesAhead())
        ahead.retain(1, 2)
        assertEquals(572L, ahead.bytesAhead())
        assertFalse(ahead.reachesEnd())
        ahead.moveReader(0)
        assertEquals(0L, ahead.bytesAhead())
        ahead.record(0)
        assertEquals(2572L, ahead.bytesAhead())
        assertEquals(3, ahead.firstMissing(1))
    }

    @Test fun retainedFileRangesMapCrossFilePiecesWithoutKeepingOtherBytes() {
        assertEquals(900L..999L, retainedFileRange(0, 1000, 900, 1200))
        assertEquals(0L..199L, retainedFileRange(1000, 1000, 900, 1200))
        assertNull(retainedFileRange(2000, 1000, 900, 1200))
        assertEquals(0L..999L, retainedFileRange(1000, 1000, 0, 3000))
        assertNull(retainedFileRange(0, 900, 900, 1200))
        assertNull(retainedFileRange(1200, 500, 900, 1200))
        assertNull(retainedFileRange(1000, 0, 900, 1200))
        assertNull(retainedFileRange(1000, 1000, 1100, 1100))
    }

    @Test fun retainedFileRangesPreserveLongOffsetsAndValidateOverflow() {
        val offset = 5L * 1024 * 1024 * 1024
        assertEquals(100L..299L, retainedFileRange(offset, offset, offset + 100, offset + 300))
        assertEquals(0L..(offset - 1), retainedFileRange(offset, offset, 0, offset * 2))
        assertEquals(0L..9L, retainedFileRange(Long.MAX_VALUE - 10, 10, 0, Long.MAX_VALUE))
        assertThrows(IllegalArgumentException::class.java) { retainedFileRange(Long.MAX_VALUE, 1, 0, 1) }
        assertThrows(IllegalArgumentException::class.java) { retainedFileRange(-1, 1, 0, 1) }
        assertThrows(IllegalArgumentException::class.java) { retainedFileRange(0, -1, 0, 1) }
        assertThrows(IllegalArgumentException::class.java) { retainedFileRange(0, 1, -1, 1) }
        assertThrows(IllegalArgumentException::class.java) { retainedFileRange(0, 1, 2, 1) }
    }
}
