package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.util

import com.intellij.openapi.vfs.JarFileSystem
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager

/**
 * Resolves user-provided source paths to IntelliJ virtual files.
 *
 * Supports normal local paths, file:// URLs, and jar-internal paths in both
 * IntelliJ URL form (jar://...!/... ) and bare filesystem form (...jar!/... ).
 */
object VirtualFileResolver {
    private const val JAR_URL_PREFIX = "jar://"
    private const val FILE_URL_PREFIX = "file://"

    fun findFile(path: String): VirtualFile? {
        val normalized = normalize(path)

        return when (normalized.kind) {
            PathKind.JAR -> {
                VirtualFileManager.getInstance().findFileByUrl(normalized.vfsPath)
                    ?: JarFileSystem.getInstance().findFileByPath(normalized.vfsPath.removePrefix(JAR_URL_PREFIX))
            }
            PathKind.FILE_URL -> {
                VirtualFileManager.getInstance().findFileByUrl(normalized.vfsPath)
                    ?: LocalFileSystem.getInstance().findFileByPath(normalized.localPath ?: normalized.vfsPath)
            }
            PathKind.LOCAL -> LocalFileSystem.getInstance().findFileByPath(normalized.vfsPath)
        }
    }

    internal fun normalize(path: String): NormalizedPath {
        val trimmed = path.trim()

        return when {
            trimmed.startsWith(JAR_URL_PREFIX, ignoreCase = true) -> {
                NormalizedPath(PathKind.JAR, normalizeJarPath(trimmed.removePrefixIgnoreCase(JAR_URL_PREFIX)))
            }
            trimmed.contains('!') -> {
                NormalizedPath(PathKind.JAR, normalizeJarPath(trimmed))
            }
            trimmed.startsWith(FILE_URL_PREFIX, ignoreCase = true) -> {
                val localPath = normalizeFileUrlToLocalPath(trimmed)
                NormalizedPath(PathKind.FILE_URL, toFileUrl(localPath), localPath)
            }
            else -> NormalizedPath(PathKind.LOCAL, normalizeLocalPath(trimmed))
        }
    }

    private fun normalizeJarPath(pathWithoutJarPrefix: String): String {
        val slashPath = pathWithoutJarPrefix.replace('\\', '/')
        val bangIndex = slashPath.indexOf('!')
        if (bangIndex < 0) {
            return JAR_URL_PREFIX + slashPath
        }

        val jarPath = slashPath.substring(0, bangIndex)
        val entryPath = slashPath.substring(bangIndex + 1).trimStart('/')
        return "$JAR_URL_PREFIX$jarPath!/$entryPath"
    }

    private fun normalizeFileUrlToLocalPath(fileUrl: String): String {
        val withoutScheme = fileUrl.removePrefixIgnoreCase(FILE_URL_PREFIX).replace('\\', '/')
        return when {
            withoutScheme.matches(Regex("/[A-Za-z]:/.*")) -> withoutScheme.drop(1)
            withoutScheme.matches(Regex("[A-Za-z]:/.*")) -> withoutScheme
            else -> withoutScheme
        }
    }

    private fun toFileUrl(localPath: String): String {
        return if (localPath.matches(Regex("[A-Za-z]:/.*"))) {
            "$FILE_URL_PREFIX/$localPath"
        } else {
            "$FILE_URL_PREFIX$localPath"
        }
    }

    private fun normalizeLocalPath(path: String): String = path.replace('\\', '/')

    private fun String.removePrefixIgnoreCase(prefix: String): String =
        if (startsWith(prefix, ignoreCase = true)) substring(prefix.length) else this

    internal data class NormalizedPath(
        val kind: PathKind,
        val vfsPath: String,
        val localPath: String? = null
    )

    internal enum class PathKind {
        LOCAL,
        FILE_URL,
        JAR
    }
}
