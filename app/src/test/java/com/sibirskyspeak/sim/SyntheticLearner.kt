package com.sibirskyspeak.sim

import com.sibirskyspeak.data.CardType
import com.sibirskyspeak.learning.CardPedagogy
import kotlin.math.exp
import kotlin.math.ln
import kotlin.random.Random

data class SimItem(val id: Long, val unit: Int, val rank: Int, val type: CardType)

class SyntheticLearner(seed: Int) {
    private val random = Random(seed)
    private val stability = mutableMapOf<Long, Double>()

    fun answer(item: SimItem, elapsedDays: Int): Boolean {
        val base = stability.getOrPut(item.id) {
            exp(ln(1.8 + 28.0 / (1.0 + item.rank / 800.0)) + gaussian() * .35)
        }
        val cost = CardPedagogy.profile(item.type).cognitiveCost
        val recall = exp(-elapsedDays.coerceAtLeast(0) / base) * (1.08 - cost * .08)
        val correct = random.nextDouble() < recall.coerceIn(.03, .99)
        stability[item.id] = if (correct) base * 1.22 else (base * .58).coerceAtLeast(.15)
        return correct
    }

    private fun gaussian(): Double {
        val u1 = random.nextDouble().coerceAtLeast(1e-9)
        val u2 = random.nextDouble()
        return kotlin.math.sqrt(-2 * ln(u1)) * kotlin.math.cos(2 * Math.PI * u2)
    }
}
