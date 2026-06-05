package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.util

import org.junit.Assert.assertEquals
import org.junit.Test

class VirtualFileResolverTest {

    @Test
    fun normalizesLocalWindowsPath() {
        val normalized = VirtualFileResolver.normalize("D:\\project\\src\\Foo.kt")

        assertEquals(VirtualFileResolver.PathKind.LOCAL, normalized.kind)
        assertEquals("D:/project/src/Foo.kt", normalized.vfsPath)
    }

    @Test
    fun keepsLocalForwardSlashPath() {
        val normalized = VirtualFileResolver.normalize("D:/project/src/Foo.kt")

        assertEquals(VirtualFileResolver.PathKind.LOCAL, normalized.kind)
        assertEquals("D:/project/src/Foo.kt", normalized.vfsPath)
    }

    @Test
    fun normalizesFileUrlWithDrivePath() {
        val normalized = VirtualFileResolver.normalize("file:///D:/project/src/Foo.kt")

        assertEquals(VirtualFileResolver.PathKind.FILE_URL, normalized.kind)
        assertEquals("file:///D:/project/src/Foo.kt", normalized.vfsPath)
        assertEquals("D:/project/src/Foo.kt", normalized.localPath)
    }

    @Test
    fun normalizesFileUrlWithWindowsSlashes() {
        val normalized = VirtualFileResolver.normalize("file://D:\\project\\src\\Foo.kt")

        assertEquals(VirtualFileResolver.PathKind.FILE_URL, normalized.kind)
        assertEquals("file:///D:/project/src/Foo.kt", normalized.vfsPath)
        assertEquals("D:/project/src/Foo.kt", normalized.localPath)
    }

    @Test
    fun normalizesJarUrlWithoutDuplicatingScheme() {
        val normalized = VirtualFileResolver.normalize("jar://D:/libs/foo.jar!/com/example/Foo.class")

        assertEquals(VirtualFileResolver.PathKind.JAR, normalized.kind)
        assertEquals("jar://D:/libs/foo.jar!/com/example/Foo.class", normalized.vfsPath)
    }

    @Test
    fun normalizesBareJarPath() {
        val normalized = VirtualFileResolver.normalize("D:/libs/foo.jar!/com/example/Foo.class")

        assertEquals(VirtualFileResolver.PathKind.JAR, normalized.kind)
        assertEquals("jar://D:/libs/foo.jar!/com/example/Foo.class", normalized.vfsPath)
    }

    @Test
    fun normalizesBareWindowsJarPath() {
        val normalized = VirtualFileResolver.normalize("D:\\libs\\foo.jar!\\com\\example\\Foo.class")

        assertEquals(VirtualFileResolver.PathKind.JAR, normalized.kind)
        assertEquals("jar://D:/libs/foo.jar!/com/example/Foo.class", normalized.vfsPath)
    }

    @Test
    fun normalizesJarUrlWithWindowsSlashes() {
        val normalized = VirtualFileResolver.normalize("jar://D:\\libs\\foo.jar!\\com\\example\\Foo.class")

        assertEquals(VirtualFileResolver.PathKind.JAR, normalized.kind)
        assertEquals("jar://D:/libs/foo.jar!/com/example/Foo.class", normalized.vfsPath)
    }
}
