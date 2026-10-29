package com.srep.novatv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UtilTest {
    @Test fun sha256() =
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Util.sha256Hex("abc".toByteArray()))

    @Test fun abi() {
        assertTrue(Util.abiCompatible(emptySet(), arrayOf("arm64-v8a")))
        assertTrue(Util.abiCompatible(setOf("armeabi-v7a"), arrayOf("arm64-v8a", "armeabi-v7a")))
        assertFalse(Util.abiCompatible(setOf("x86"), arrayOf("arm64-v8a")))
    }

    @Test fun risky() {
        assertEquals(1, Util.risky(listOf("android.permission.INTERNET", "android.permission.READ_SMS")).size)
        assertTrue(Util.risky(listOf("android.permission.INTERNET")).isEmpty())
    }

    @Test fun duration() = assertEquals("01:01:01", Util.duration(3_661_000))

    @Test fun fileKind() {
        assertEquals(Util.FileKind.Apk, Util.fileKind("App.APK"))
        assertEquals(Util.FileKind.Bundle, Util.fileKind("Кинопоиск_2.270.8_APKPure.xapk"))
        assertEquals(Util.FileKind.Bundle, Util.fileKind("a.apks"))
        assertEquals(Util.FileKind.Other, Util.fileKind("movie.mkv"))
    }

    @Test fun isUnder() {
        assertTrue(Util.isUnder("/data/x/provision/a.xapk", "/data/x/provision"))
        assertFalse(Util.isUnder("/data/x/provision2/a.xapk", "/data/x/provision"))
    }

    @Test fun sizeText() {
        assertEquals("512 Б", Util.sizeText(512))
        assertEquals("6 МБ", Util.sizeText(6_013_077))
    }
}
