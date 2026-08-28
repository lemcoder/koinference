package io.github.lemcoder.koinference.benchmark.app.ui

import io.github.lemcoder.koinference.benchmark.result.BenchmarkFile
import io.github.lemcoder.koinference.benchmark.result.BenchmarkStatus
import io.github.lemcoder.koinference.benchmark.result.parseBenchmarkFile

/**
 * Turns the results files the services returned into table rows.
 *
 * Medians rather than means: a single sample that hit a thermal throttle should not drag the row,
 * and the harness's own analysis reports medians for the same reason.
 */
object ResultsTable {

    fun rows(resultsJson: List<String>): List<BenchmarkRow> =
        resultsJson.mapNotNull { runCatching { parseBenchmarkFile(it) }.getOrNull() }.flatMap(::rowsOf)

    private fun rowsOf(file: BenchmarkFile): List<BenchmarkRow> = file.records.map { record ->
        val samples = record.samples
        BenchmarkRow(
            engineId = record.engine.id,
            workload = record.workload.promptId,
            status = record.status.name,
            tokensPerSecond = samples.mapNotNull { it.tokensPerSecond }.median(),
            ttftMs = samples.mapNotNull { it.ttftMs }.median(),
            tokens = samples.mapNotNull { it.generatedTokens }.map { it.toDouble() }.median()?.toInt(),
            chunks = samples.map { it.chunks.toDouble() }.median()?.toInt(),
            peakPssMb = record.memory?.peakPssKb?.mb(),
            weightsPssMb = record.memory?.let { memory ->
                val before = memory.beforeInitPssKb
                val afterLoad = memory.afterLoadPssKb
                if (before != null && afterLoad != null) (afterLoad - before).mb() else null
            },
            afterRunPssMb = record.memory?.afterRunPssKb?.mb(),
            note = record.failureReason
                ?: record.notes.firstOrNull()
                ?: if (record.status != BenchmarkStatus.SUCCESS) record.status.name else null,
            noteIsFailure = record.failureReason != null || record.status != BenchmarkStatus.SUCCESS,
        )
    }

    private fun Long.mb(): Double = this / 1024.0

    private fun List<Double>.median(): Double? {
        if (isEmpty()) return null
        val sorted = sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2.0
    }
}
