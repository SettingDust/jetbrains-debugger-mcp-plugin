package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.util

import com.intellij.xdebugger.frame.XCompositeNode
import com.intellij.xdebugger.frame.XNamedValue
import com.intellij.xdebugger.frame.XStackFrame
import com.intellij.xdebugger.frame.XValue
import com.intellij.xdebugger.frame.XValueChildrenList
import com.intellij.xdebugger.frame.XValueNode
import com.intellij.xdebugger.frame.XValuePlace
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.concurrent.thread

class ValuePresentationResolverTest {

    @Test
    fun resolvesRealValueAfterPlaceholder() = runBlocking {
        val value = object : XValue() {
            override fun computePresentation(node: XValueNode, place: XValuePlace) {
                node.setPresentation(null, null, "Collecting data...", false)
                thread {
                    Thread.sleep(50)
                    node.setPresentation(null, null, "42", false)
                }
            }
        }

        val resolved = ValuePresentationResolver.resolve(value, timeoutMillis = 500L)

        assertEquals("42", resolved.value)
        assertEquals(ValuePresentationResolver.STATUS_RESOLVED, resolved.presentationStatus)
        assertTrue(resolved.isValueComplete)
    }

    @Test
    fun marksPlaceholderTimeoutAsIncomplete() = runBlocking {
        val value = object : XValue() {
            override fun computePresentation(node: XValueNode, place: XValuePlace) {
                node.setPresentation(null, null, "Collecting data...", false)
            }
        }

        val resolved = ValuePresentationResolver.resolve(value, timeoutMillis = 100L)

        assertEquals("Collecting data...", resolved.value)
        assertEquals(ValuePresentationResolver.STATUS_PLACEHOLDER_SUSPECTED, resolved.presentationStatus)
        assertFalse(resolved.isValueComplete)
    }

    @Test
    fun collectorReturnsPartialResultsWithoutFailingWholeBatch() = runBlocking {
        val fast = object : XValue() {
            override fun computePresentation(node: XValueNode, place: XValuePlace) {
                node.setPresentation(null, null, "ready", false)
            }
        }
        val slowPlaceholder = object : XValue() {
            override fun computePresentation(node: XValueNode, place: XValuePlace) {
                node.setPresentation(null, null, "正在收集数据...", false)
            }
        }

        val frame = object : XStackFrame() {
            override fun computeChildren(node: XCompositeNode) {
                val children = XValueChildrenList()
                children.add("fast", fast)
                children.add("slow", slowPlaceholder)
                node.addChildren(children, true)
            }
        }

        val variables = VariableChildrenCollector.collect(
            frame,
            VariableChildrenCollector.Config(
                maxVariables = 10,
                perVariableTimeoutMillis = 100L,
                totalTimeoutMillis = 500L,
                concurrencyLimit = 2
            )
        )

        assertEquals(2, variables.size)
        assertEquals("ready", variables.first { it.name == "fast" }.value)
        val slow = variables.first { it.name == "slow" }
        assertEquals(ValuePresentationResolver.STATUS_PLACEHOLDER_SUSPECTED, slow.presentationStatus)
        assertFalse(slow.isValueComplete)
    }

    @Test
    fun collectorKeepsPartialChildrenWhenEnumerationTimesOutBeforeLastBatch() = runBlocking {
        val partial = object : XValue() {
            override fun computePresentation(node: XValueNode, place: XValuePlace) {
                node.setPresentation(null, "String", "partial-value", false)
            }
        }

        val frame = object : XStackFrame() {
            override fun computeChildren(node: XCompositeNode) {
                val children = XValueChildrenList()
                children.add("partial", partial)
                node.addChildren(children, false)
            }
        }

        val variables = VariableChildrenCollector.collect(
            frame,
            VariableChildrenCollector.Config(
                maxVariables = 10,
                perVariableTimeoutMillis = 200L,
                totalTimeoutMillis = 100L,
                concurrencyLimit = 1
            )
        )

        assertEquals(1, variables.size)
        assertEquals("partial", variables.single().name)
        assertEquals("partial-value", variables.single().value)
        assertEquals(ValuePresentationResolver.STATUS_RESOLVED, variables.single().presentationStatus)
    }

    @Test
    fun resolverTimesOutInsteadOfWaitingForDelayedPresentationCallback() = runBlocking {
        val value = object : XValue() {
            override fun computePresentation(node: XValueNode, place: XValuePlace) {
                thread {
                    Thread.sleep(250)
                    node.setPresentation(null, null, "late", false)
                }
            }
        }

        val resolved = ValuePresentationResolver.resolve(value, timeoutMillis = 50L)

        assertEquals(ValuePresentationResolver.STATUS_TIMED_OUT, resolved.presentationStatus)
        assertFalse(resolved.isValueComplete)
    }
}
