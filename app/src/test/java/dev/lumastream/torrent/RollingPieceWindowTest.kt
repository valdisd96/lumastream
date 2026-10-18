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
