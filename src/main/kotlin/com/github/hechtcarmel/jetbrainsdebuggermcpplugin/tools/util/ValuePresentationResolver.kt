package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.util

import com.intellij.openapi.application.ApplicationManager
import com.intellij.xdebugger.frame.XFullValueEvaluator
import com.intellij.xdebugger.frame.XValue
import com.intellij.xdebugger.frame.XValueNode
import com.intellij.xdebugger.frame.XValuePlace
import com.intellij.xdebugger.frame.presentation.XValuePresentation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.Icon

object ValuePresentationResolver {

    const val STATUS_RESOLVED = "resolved"
    const val STATUS_RESOLVING = "resolving"
    const val STATUS_TIMED_OUT = "timed_out"
    const val STATUS_FAILED = "failed"
    const val STATUS_PLACEHOLDER_SUSPECTED = "placeholder_suspected"
    const val STATUS_OBSOLETE = "obsolete"

    private val placeholderValues = setOf(
        "Collecting data...",
        "正在收集数据..."
    )

    data class ResolvedValuePresentation(
        val value: String,
        val type: String,
        val hasChildren: Boolean,
        val presentationStatus: String,
        val isValueComplete: Boolean,
        val fullValue: String? = null,
        val error: String? = null,
        val isFullValueEvaluatorAvailable: Boolean = false
    )

    suspend fun resolve(
        value: XValue,
        timeoutMillis: Long,
        place: XValuePlace = XValuePlace.TREE
    ): ResolvedValuePresentation {
        val deferred = CompletableDeferred<ResolvedValuePresentation>()
        val obsolete = AtomicBoolean(false)
        val state = PresentationCaptureState()

        val node = object : XValueNode {
            override fun setPresentation(
                icon: Icon?,
                type: String?,
                valueText: String,
                hasChildren: Boolean
            ) {
                val snapshot = state.capture(
                    value = valueText,
                    type = type,
                    hasChildren = hasChildren
                )
                if (!snapshot.isPlaceholder) {
                    deferred.complete(snapshot.toResolved(STATUS_RESOLVED, isValueComplete = true))
                }
            }

            override fun setPresentation(
                icon: Icon?,
                presentation: XValuePresentation,
                hasChildren: Boolean
            ) {
                val renderedValue = VariablePresentationUtils.renderPresentation(presentation)
                val snapshot = state.capture(
                    value = renderedValue,
                    type = presentation.type,
                    hasChildren = hasChildren
                )
                if (!snapshot.isPlaceholder) {
                    deferred.complete(snapshot.toResolved(STATUS_RESOLVED, isValueComplete = true))
                }
            }

            override fun setFullValueEvaluator(fullValueEvaluator: XFullValueEvaluator) {
                state.fullValueEvaluatorAvailable = true
            }

            override fun isObsolete(): Boolean = obsolete.get()
        }

        val application = ApplicationManager.getApplication()
        val computePresentation = {
            if (!obsolete.get() && !deferred.isCompleted) {
                try {
                    value.computePresentation(node, place)
                } catch (e: Throwable) {
                    deferred.complete(failed(e.message ?: e::class.java.name))
                }
            }
        }

        when {
            application == null -> computePresentation()
            application.isDisposed -> deferred.complete(failed("Application is disposed"))
            application.isDispatchThread -> computePresentation()
            else -> application.invokeLater {
                computePresentation()
            }
        }

        val resolved = withTimeoutOrNull(timeoutMillis) { deferred.await() }
        if (resolved != null) {
            obsolete.set(true)
            return resolved.copy(
                isFullValueEvaluatorAvailable = state.fullValueEvaluatorAvailable
            )
        }

        obsolete.set(true)
        val snapshot = state.snapshot()
        return when {
            snapshot != null && snapshot.isPlaceholder -> snapshot.toResolved(
                presentationStatus = STATUS_PLACEHOLDER_SUSPECTED,
                isValueComplete = false,
                fullValueEvaluatorAvailable = state.fullValueEvaluatorAvailable
            )
            snapshot != null -> snapshot.toResolved(
                presentationStatus = STATUS_TIMED_OUT,
                isValueComplete = false,
                fullValueEvaluatorAvailable = state.fullValueEvaluatorAvailable
            )
            else -> ResolvedValuePresentation(
                value = "",
                type = "unknown",
                hasChildren = false,
                presentationStatus = STATUS_TIMED_OUT,
                isValueComplete = false,
                fullValue = null,
                error = null,
                isFullValueEvaluatorAvailable = state.fullValueEvaluatorAvailable
            )
        }
    }

    fun failed(errorMessage: String): ResolvedValuePresentation {
        return ResolvedValuePresentation(
            value = "",
            type = "error",
            hasChildren = false,
            presentationStatus = STATUS_FAILED,
            isValueComplete = false,
            fullValue = null,
            error = errorMessage,
            isFullValueEvaluatorAvailable = false
        )
    }

    private fun isPlaceholderSnapshot(value: String, type: String, hasChildren: Boolean): Boolean {
        if (!placeholderValues.contains(normalizePlaceholderCandidate(value))) {
            return false
        }

        val typeLooksUnresolved = type.isBlank() || type == "unknown"
        return typeLooksUnresolved && hasChildren
    }

    private fun normalizePlaceholderCandidate(value: String): String {
        val trimmed = value.trim()
        val unquoted = when {
            trimmed.length >= 2 && trimmed.first() == '"' && trimmed.last() == '"' -> trimmed.substring(1, trimmed.length - 1)
            trimmed.length >= 2 && trimmed.first() == '\'' && trimmed.last() == '\'' -> trimmed.substring(1, trimmed.length - 1)
            else -> trimmed
        }
        return unquoted.replace("\u2026", "...")
    }

    private class PresentationCaptureState {
        @Volatile
        var fullValueEvaluatorAvailable: Boolean = false

        @Volatile
        private var latest: Snapshot? = null

        fun capture(value: String, type: String?, hasChildren: Boolean): Snapshot {
            val snapshot = Snapshot(
                value = value,
                type = type ?: "unknown",
                hasChildren = hasChildren,
                isPlaceholder = isPlaceholderSnapshot(value, type ?: "unknown", hasChildren)
            )
            latest = snapshot
            return snapshot
        }

        fun snapshot(): Snapshot? = latest
    }

    private data class Snapshot(
        val value: String,
        val type: String,
        val hasChildren: Boolean,
        val isPlaceholder: Boolean
    ) {
        fun toResolved(
            presentationStatus: String,
            isValueComplete: Boolean,
            fullValue: String? = null,
            error: String? = null,
            fullValueEvaluatorAvailable: Boolean = false
        ): ResolvedValuePresentation {
            return ResolvedValuePresentation(
                value = value,
                type = type,
                hasChildren = hasChildren,
                presentationStatus = presentationStatus,
                isValueComplete = isValueComplete,
                fullValue = fullValue,
                error = error,
                isFullValueEvaluatorAvailable = fullValueEvaluatorAvailable
            )
        }
    }
}
