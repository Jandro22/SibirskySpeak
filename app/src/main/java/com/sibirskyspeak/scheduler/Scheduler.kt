package com.sibirskyspeak.scheduler

import com.sibirskyspeak.data.Card
import com.sibirskyspeak.data.Rating
import com.sibirskyspeak.data.ReviewLog

interface Scheduler {
    fun review(card: Card, rating: Rating, now: Long = System.currentTimeMillis()): Pair<Card, ReviewLog>
    fun preview(card: Card, now: Long = System.currentTimeMillis()): Map<Rating, Int>
    fun applyQueueConstraints(card: Card): Card
}
