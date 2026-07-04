package com.sibirskyspeak.sim

import com.sibirskyspeak.data.CardType
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SimHarnessTest {
    private fun inventory(): List<SimItem> {
        val file = sequenceOf(File("src/main/assets/bootstrap_notes.jsonl"), File("app/src/main/assets/bootstrap_notes.jsonl"))
            .first { it.exists() }
        var id = 1L
        return file.useLines { lines -> lines.mapNotNull { line ->
            val row = JSONObject(line)
            if (row.optInt("tier", 0) != 0 || !row.has("unit")) return@mapNotNull null
            SimItem(id++, row.optInt("unit"), row.optInt("generalFreqRank", 5000), CardType.RU_TO_MEANING)
        }.take(2_000).toList() }
    }

    private data class Result(val maxUnit: Int, val maxDebt: Double, val seen: Set<CardType>, val introduced: Int)

    private fun run(seed: Int, strictLinearBug: Boolean = false): Result {
        val items = inventory()
        val learner = SyntheticLearner(seed)
        val seen = mutableSetOf<CardType>()
        var introduced = 0
        var debt = 0
        var maxDebt = 0.0
        repeat(400) { day ->
            val capacity = 24
            val due = (debt + 12).coerceAtMost(capacity)
            repeat(due) { offset ->
                if (introduced > 0) {
                    val item = items[(day * 17 + offset) % introduced]
                    val types = CardType.entries.filter { it != CardType.STRESS_MARK }
                    val type = types[(day + offset) % types.size]
                    seen += type
                    if (!learner.answer(item.copy(type = type), 2)) debt++
                }
            }
            debt = (debt - capacity / 2).coerceAtLeast(0)
            if ((!strictLinearBug || day < 15) && introduced < items.size) introduced = (introduced + 8).coerceAtMost(items.size)
            maxDebt = maxOf(maxDebt, debt / capacity.toDouble())
        }
        return Result(items.take(introduced).maxOfOrNull { it.unit } ?: 0, maxDebt, seen, introduced)
    }

    @Test fun fastProfileHasNoDeadlockBoundedDebtReachabilityOrStarvation() {
        val results = (0 until 25).map(::run)
        assertTrue(results.all { it.introduced >= 1_900 })
        assertTrue(results.sortedBy { it.maxDebt }[23].maxDebt < 1.0)
        assertTrue(results.sortedBy { it.maxUnit }[12].maxUnit >= 30)
        val required = CardType.entries.filter { it != CardType.STRESS_MARK }.toSet()
        assertTrue(results.all { it.seen.containsAll(required) })
    }

    @Test fun historicalStrictLinearGateBugIsDetected() {
        val healthy = run(7)
        val broken = run(7, strictLinearBug = true)
        assertTrue(healthy.introduced > broken.introduced * 5)
    }
}
