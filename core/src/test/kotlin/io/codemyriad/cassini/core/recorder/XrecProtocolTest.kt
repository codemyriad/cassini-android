package io.codemyriad.cassini.core.recorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class XrecProtocolTest {
    @Test fun buildsTheCommandsTheAppSends() {
        val key = ByteArray(32) { it.toByte() }
        assertEquals("+XREC:PING", XrecCommands.ping())
        val keyHex = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
        assertEquals("+XREC:PAIR,012345,$keyHex", XrecCommands.pair("012345", key))
        assertEquals("+XREC:CHAL", XrecCommands.challenge())
        assertEquals("+XREC:AUTH,${"ab".repeat(32)}", XrecCommands.auth("ab".repeat(32)))
        assertEquals("+XREC:STAT", XrecCommands.stat())
        assertEquals("+XREC:RTC", XrecCommands.rtc())
        // 2026-11-25 is a Wednesday: weekday 2 with Monday = 0.
        assertEquals("+XREC:RTC,2026,11,25,9,5,7,2", XrecCommands.setRtc(LocalDateTime.of(2026, 11, 25, 9, 5, 7)))
        val sunday = LocalDateTime.of(2026, 11, 29, 23, 59, 59)
        assertEquals("+XREC:RTC,2026,11,29,23,59,59,6", XrecCommands.setRtc(sunday))
        assertEquals("+XREC:LS", XrecCommands.list())
        assertEquals("+XREC:GETS,7680,3840,640,REC_0001.wav", XrecCommands.gets(7680, 3840, 640, "REC_0001.wav"))
        assertEquals("+XREC:DEL,REC_0001.wav", XrecCommands.delete("REC_0001.wav"))
        assertEquals(
            "+XREC:PUT,600,600,41393,REC,REC_0001.cwx:",
            XrecCommands.putHeader(600, 600, 41393, PutTarget.Record("REC_0001.cwx")),
        )
        val cassini = PutTarget.Cassini("notes.bin")
        assertEquals("+XREC:PUT,0,12,1,CAS,notes.bin:", XrecCommands.putHeader(0, 12, 1, cassini))
        assertEquals("+XREC:PUTEND", XrecCommands.putEnd())
        assertEquals("+XREC:SYNC,0", XrecCommands.sync(0))
        assertEquals("+XREC:SYNC,2,45", XrecCommands.sync(2, 45))
    }

    @Test fun namesMayContainCommas() {
        assertEquals("+XREC:GETS,0,1,1,a,b.wav", XrecCommands.gets(0, 1, 1, "a,b.wav"))
    }

    @Test fun rejectsInvalidArguments() {
        assertThrows { XrecCommands.pair("12345", ByteArray(32)) }
        assertThrows { XrecCommands.pair("12345a", ByteArray(32)) }
        assertThrows { XrecCommands.pair("123456", ByteArray(31)) }
        assertThrows { XrecCommands.auth("AB".repeat(32)) }
        assertThrows { XrecCommands.gets(0, 3840, 641, "a.wav") }
        assertThrows { XrecCommands.gets(-1, 3840, 640, "a.wav") }
        assertThrows { XrecCommands.gets(0, 3840, 640, "../a.wav") }
        assertThrows { XrecCommands.delete("a:b") }
        assertThrows { XrecCommands.putHeader(0, 0, 0, PutTarget.Record("a.cwx")) }
        assertThrows { XrecCommands.putHeader(0, 641, 0, PutTarget.Record("a.cwx")) }
        assertThrows { XrecCommands.putHeader(0, 1, 0x10000, PutTarget.Record("a.cwx")) }
        assertThrows { PutTarget.Record("a.wav") }
        assertThrows { PutTarget.Record("x".repeat(27) + ".cwx") }
        assertThrows { PutTarget.Record("é.cwx") }
        assertThrows { XrecCommands.sync(-1) }
        assertThrows { XrecCommands.sync(0x10000) }
        assertThrows { XrecCommands.sync(1, -1) }
    }

    @Test fun followsTheFirmwareNameRules() {
        assertTrue(XrecNames.isSafe("REC_0001.wav"))
        assertTrue(XrecNames.isSafe("a,b c.wav"))
        assertTrue(XrecNames.isSafe("x".repeat(62)))
        assertFalse(XrecNames.isSafe("x".repeat(63)))
        val unsafe = listOf(
            "", ".", "..", "a/b", "a\\b", "a:b", "a*b", "a?b", "a\"b", "a<b", "a>b", "a|b", "a\u0001b", "a.", "a ",
        )
        unsafe.forEach { assertFalse(it, XrecNames.isSafe(it)) }
        assertTrue(XrecNames.isSidecarName("x".repeat(26) + ".cwx"))
        assertTrue(XrecNames.isSidecarName("REC_0001.CWX"))
        assertFalse(XrecNames.isSidecarName("x".repeat(27) + ".cwx"))
        assertFalse(XrecNames.isSidecarName(".cwx"))
        assertFalse(XrecNames.isSidecarName(".a.cwx"))
        assertFalse(XrecNames.isSidecarName(".wav.cwx"))
    }

    @Test fun derivesTheSidecarName() {
        assertEquals("REC_0001.cwx", XrecNames.sidecarFor("REC_0001.wav"))
        assertEquals("REC_0001.cwx", XrecNames.sidecarFor("REC_0001.WAV"))
        assertEquals("noext.cwx", XrecNames.sidecarFor("noext"))
        assertNull(XrecNames.sidecarFor(".wav"))
        assertNull(XrecNames.sidecarFor(".hidden.wav"))
        assertNull(XrecNames.sidecarFor("x".repeat(40) + ".wav"))
        assertNull(XrecNames.sidecarFor("é.wav"))
    }

    @Test fun parsesPong() {
        assertEquals(
            XrecReply.Pong("openfw_v5", "v5", bound = true, authenticated = false, pairingOpen = true),
            XrecReplies.parse("+XREC:PONG,openfw_v5,v5,bound=1,auth=0,pair=1"),
        )
        assertEquals(
            XrecReply.Pong(
                "openfw_v5",
                "v5",
                bound = true,
                authenticated = true,
                pairingOpen = false,
                recordTarget = true,
                syncStatusSupported = true,
                liveRecordingSupported = true,
            ),
            XrecReplies.parse("+XREC:PONG,openfw_v5,v5,bound=1,auth=1,pair=0,rec=1,sync=1,live=1,future=x"),
        )
    }

    @Test fun parsesChallenge() {
        assertEquals(
            XrecReply.Challenge("a0a1a2a3a4a5a6a7a8a9aaabacadaeaf"),
            XrecReplies.parse("+XREC:CHAL,A0A1A2A3A4A5A6A7A8A9AAABACADAEAF"),
        )
        assertTrue(XrecReplies.parse("+XREC:CHAL,a0a1") is XrecReply.Other)
    }

    @Test fun parsesStat() {
        val stat = XrecReplies.parse("+XREC:STAT,87,3990,1,1,33,125,1500,7600") as XrecReply.Status
        assertEquals(XrecReply.Status(87, 3990, 1, true, 33, 125, 1500, 7600), stat)
        assertTrue(stat.recording)
        assertFalse((XrecReplies.parse("+XREC:STAT,87,3990,0,0,16,0,1500,7600") as XrecReply.Status).recording)
        assertTrue(XrecReplies.parse("+XREC:STAT,87,x,0,0,16,0,1500,7600") is XrecReply.Other)
        assertTrue(XrecReplies.parse("+XREC:STAT,87") is XrecReply.Other)
        assertNull(stat.recordingIndex)
        assertFalse(stat.namesActiveFile)
    }

    @Test fun parsesTheStatFieldsOfSyncWhileRecording() {
        val stat = XrecReplies.parse("+XREC:STAT,87,3990,0,0,33,12,1500,7600,7,342,4096") as XrecReply.Status
        assertEquals(XrecReply.Status(87, 3990, 0, false, 33, 12, 1500, 7600, 7, 342, 4096), stat)
        assertEquals(4096L, stat.recordingBytes)
        assertTrue(stat.namesActiveFile)
        val idle = XrecReplies.parse("+XREC:STAT,87,3990,0,0,16,0,1500,7600,0,0,0") as XrecReply.Status
        assertEquals(0L, idle.recordingIndex)
        assertEquals(0L, idle.recordingBytes)
        assertTrue(idle.namesActiveFile)
        // Later fields, and fields that are not numbers after the eighth, do not make the reply unreadable.
        val extra = XrecReplies.parse("+XREC:STAT,87,3990,0,0,33,12,1500,7600,7,342,8192,future,9") as XrecReply.Status
        assertEquals(7L, extra.recordingIndex)
        assertEquals(8192L, extra.recordingBytes)
        val odd = XrecReplies.parse("+XREC:STAT,87,3990,0,0,33,12,1500,7600,x,x,x") as XrecReply.Status
        assertNull(odd.recordingIndex)
        assertNull(odd.paceMillis)
        assertNull(odd.recordingBytes)
        assertEquals(7600L, odd.totalMb)
    }

    @Test fun parsesRtc() {
        assertEquals(
            XrecReply.Clock("2026-11-25 10:11:12", LocalDateTime.of(2026, 11, 25, 10, 11, 12), 2),
            XrecReplies.parse("+XREC:RTC,2026-11-25 10:11:12,2"),
        )
        assertEquals(XrecReply.Clock("0-00-00 00:00:00", null, 0), XrecReplies.parse("+XREC:RTC,0-00-00 00:00:00,0"))
    }

    @Test fun parsesFileLinesWithCommasInTheName() {
        assertEquals(XrecReply.Entry(1234, "REC_0001,a b.wav"), XrecReplies.parse("+XREC:F,1234,REC_0001,a b.wav"))
        assertEquals(XrecReply.Entry(0, "e.wav"), XrecReplies.parse("+XREC:F,0,e.wav"))
        assertTrue(XrecReplies.parse("+XREC:F,x,e.wav") is XrecReply.Other)
        assertTrue(XrecReplies.parse("+XREC:F,12,") is XrecReply.Other)
        assertEquals(XrecReply.End, XrecReplies.parse("+XREC:END"))
    }

    @Test fun parsesOkAndErr() {
        assertEquals(XrecReply.Ok("PUT", listOf("600", "40")), XrecReplies.parse("+XREC:OK,PUT,600,40"))
        assertEquals(XrecReply.Ok("PAIR", emptyList<String>()), XrecReplies.parse("+XREC:OK,PAIR"))
        val err = XrecReplies.parse("+XREC:ERR,NOENT") as XrecReply.Failed
        assertEquals(XrecFailure.NOT_FOUND, err.failure)
        assertEquals(XrecFailure.OTHER_DEVICE_ERROR, XrecReply.Failed("NEW").failure)
        val later = XrecReplies.parse("+XREC:ERR,LATER,30000") as XrecReply.Failed
        assertEquals(XrecFailure.LATER, later.failure)
        assertEquals(30_000L, later.retryAfterMillis)
        assertNull(XrecReply.Failed("LATER").retryAfterMillis)
        assertNull(err.retryAfterMillis)
    }

    @Test fun mapsEveryDeviceReason() {
        val reasons = listOf(
            "LOCKED", "AUTH", "UNBOUND", "NOCHAL", "PAIRCLOSED", "PIN", "BADKEY", "SAVE", "NOENT", "NOSD", "NAME",
            "BUSY", "DEL", "CRC", "OFF", "ARG", "ARGS", "UNSUP", "SDIO", "CMD", "LINE", "RTCRNG", "RTC", "NOPUT",
            "LATER",
        )
        reasons.forEach { assertEquals(it, XrecFailure.ofReason(it).reason) }
    }

    @Test fun ignoresOtherLines() {
        assertNull(XrecReplies.parse("OK"))
        assertNull(XrecReplies.parse(""))
        assertTrue(XrecReplies.parse("+XREC:OK,RECSTART,REC_0002.wav") is XrecReply.Ok)
        assertTrue(XrecReplies.parse("+XREC:BOGUS") is XrecReply.Other)
        assertTrue(XrecReplies.parse("+XREC:END,1") is XrecReply.Other)
    }

    private fun assertThrows(block: () -> Unit) {
        try {
            block()
        } catch (_: IllegalArgumentException) {
            return
        }
        throw AssertionError("Expected IllegalArgumentException")
    }
}
