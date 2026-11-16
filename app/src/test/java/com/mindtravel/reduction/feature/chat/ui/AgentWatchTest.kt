package com.mindtravel.reduction.feature.chat.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentWatchTest {

    private var quiet = false
    private var accepts = true
    private val delivered = ArrayList<String>()
    private val watch = AgentWatch(
        finished = { emptyList() },
        quiet = { quiet },
        deliver = { if (accepts) delivered += it.title; accepts },
        scope = CoroutineScope(Dispatchers.Unconfined)
    )

    private fun report(number: Int) = AgentWatch.Report("任务 $number 结束了", "结果 $number")

    @Test
    fun aReportWaitsForAQuietMoment() {
        watch.take(listOf(report(1)))
        watch.pass()
        assertEquals(emptyList<String>(), delivered)

        quiet = true
        watch.pass()
        watch.pass()
        assertEquals(listOf("任务 1 结束了"), delivered)
    }

    @Test
    fun reportsGoOneAtATimeInOrder() {
        quiet = true
        watch.take(listOf(report(1), report(2)))
        watch.pass()
        assertEquals(listOf("任务 1 结束了"), delivered)
        watch.pass()
        assertEquals(listOf("任务 1 结束了", "任务 2 结束了"), delivered)
    }

    @Test
    fun aReportThatCouldNotBeHandedOverIsKept() {
        quiet = true
        accepts = false
        watch.take(listOf(report(1)))
        watch.pass()
        accepts = true
        watch.pass()
        assertEquals(listOf("任务 1 结束了"), delivered)
    }
}
