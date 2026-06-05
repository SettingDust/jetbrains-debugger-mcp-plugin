package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.util

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.VariableInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.SimpleTextAttributes
import com.intellij.xdebugger.frame.XCompositeNode
import com.intellij.xdebugger.frame.XDebuggerTreeNodeHyperlink
import com.intellij.xdebugger.frame.XStackFrame
import com.intellij.xdebugger.frame.XValue
import com.intellij.xdebugger.frame.XValueChildrenList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.Icon
import kotlin.coroutines.resume

object VariableChildrenCollector {

    data class Config(
        val maxVariables: Int = 100,
        val perVariableTimeoutMillis: Long = 1500L,
        val totalTimeoutMillis: Long = 5000L,
        val concurrencyLimit: Int = 4
    )

    suspend fun collect(frame: XStackFrame, config: Config = Config()): List<VariableInfo> {
        val children = collectChildren(frame, config.maxVariables, config.totalTimeoutMillis)
        if (children.isEmpty()) return emptyList()

        return coroutineScope {
            val semaphore = Semaphore(config.concurrencyLimit.coerceAtLeast(1))
            val results = arrayOfNulls<VariableInfo>(children.size)
            val scope = CoroutineScope(coroutineContext + SupervisorJob())

            val jobs = children.mapIndexed { index, child ->
                scope.launch {
                    semaphore.withPermit {
                        val resolved = ValuePresentationResolver.resolve(
                            value = child.value,
                            timeoutMillis = config.perVariableTimeoutMillis
                        )
                        results[index] = child.toVariableInfo(resolved)
                    }
                }
            }

            withTimeoutOrNull(config.totalTimeoutMillis) {
                for (job in jobs) {
                    job.join()
                }
            }

            jobs.filter { it.isActive }.forEach { it.cancel() }
            jobs.filter { it.isCancelled || it.isActive }.forEach {
                try {
                    it.cancelAndJoin()
                } catch (_: Exception) {
                }
            }

            children.mapIndexed { index, child ->
                results[index] ?: child.toVariableInfo(
                    ValuePresentationResolver.ResolvedValuePresentation(
                        value = "",
                        type = "unknown",
                        hasChildren = false,
                        presentationStatus = ValuePresentationResolver.STATUS_TIMED_OUT,
                        isValueComplete = false
                    )
                )
            }
        }
    }

    private suspend fun collectChildren(
        frame: XStackFrame,
        maxVariables: Int,
        timeoutMillis: Long
    ): List<NamedValue> {
        val completed = AtomicBoolean(false)
        val values = Collections.synchronizedList(mutableListOf<NamedValue>())

        fun snapshot(): List<NamedValue> = synchronized(values) { values.toList() }

        return withTimeoutOrNull(timeoutMillis) {
            suspendCancellableCoroutine { continuation ->
                val node = object : XCompositeNode {
                    override fun addChildren(children: XValueChildrenList, last: Boolean) {
                        synchronized(values) {
                            for (i in 0 until children.size()) {
                                if (values.size >= maxVariables) break
                                values += NamedValue(children.getName(i), children.getValue(i))
                            }
                        }

                        if ((last || values.size >= maxVariables) && completed.compareAndSet(false, true)) {
                            runCatching { continuation.resume(snapshot()) }
                        }
                    }

                    override fun setAlreadySorted(alreadySorted: Boolean) = Unit

                    override fun setErrorMessage(errorMessage: String) {
                        if (completed.compareAndSet(false, true)) {
                            runCatching { continuation.resume(snapshot()) }
                        }
                    }

                    override fun setErrorMessage(errorMessage: String, link: XDebuggerTreeNodeHyperlink?) {
                        if (completed.compareAndSet(false, true)) {
                            runCatching { continuation.resume(snapshot()) }
                        }
                    }

                    override fun setMessage(
                        message: String,
                        icon: Icon?,
                        attributes: SimpleTextAttributes,
                        link: XDebuggerTreeNodeHyperlink?
                    ) = Unit

                    @Deprecated("Deprecated in Java")
                    override fun tooManyChildren(remaining: Int) = Unit

                    override fun tooManyChildren(remaining: Int, addNextChildren: Runnable) = Unit

                    override fun isObsolete(): Boolean = completed.get()
                }

                continuation.invokeOnCancellation {
                    completed.set(true)
                }

                val application = ApplicationManager.getApplication()
                if (application != null) {
                    application.invokeLater {
                        if (!completed.get()) {
                            frame.computeChildren(node)
                        }
                    }
                } else if (!completed.get()) {
                    frame.computeChildren(node)
                }
            }
        } ?: run {
            completed.set(true)
            snapshot()
        }
    }

    private data class NamedValue(
        val name: String,
        val value: XValue
    ) {
        fun toVariableInfo(resolved: ValuePresentationResolver.ResolvedValuePresentation): VariableInfo {
            return VariableInfo(
                name = name,
                value = resolved.value,
                type = resolved.type,
                hasChildren = resolved.hasChildren,
                presentationStatus = resolved.presentationStatus,
                isValueComplete = resolved.isValueComplete,
                fullValue = resolved.fullValue,
                error = resolved.error
            )
        }
    }
}
