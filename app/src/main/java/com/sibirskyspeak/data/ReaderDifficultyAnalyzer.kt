package com.sibirskyspeak.data

/** Pure reader-difficulty calculation kept outside the repository orchestration. */
data class ReaderDifficultyMetrics(
    val syntaxComplexity: Double,
    val morphologyNovelty: Double,
    val idiomDensity: Double,
    val difficultyScore: Double
)

object ReaderDifficultyAnalyzer {
    fun analyze(
        coverage: Double,
        tokenCount: Int,
        sentenceCount: Int,
        morphologyCount: Int,
        idiomCount: Int
    ): ReaderDifficultyMetrics {
        val assessed = tokenCount.coerceAtLeast(0)
        val sentences = sentenceCount.coerceAtLeast(1)
        val averageSentenceLength = assessed.toDouble() / sentences
        val syntaxComplexity = ((averageSentenceLength - 8.0) / 18.0).coerceIn(0.0, 1.0)
        val morphologyNovelty = if (assessed == 0) 0.0 else morphologyCount.coerceAtLeast(0).toDouble() / assessed
        val idiomDensity = if (assessed == 0) 0.0 else idiomCount.coerceAtLeast(0).toDouble() / assessed
        val difficultyScore = (
            (1.0 - coverage.coerceIn(0.0, 1.0)) * 0.35 +
                syntaxComplexity * 0.25 +
                morphologyNovelty * 0.25 +
                idiomDensity * 0.15
            ).coerceIn(0.0, 1.0)
        return ReaderDifficultyMetrics(syntaxComplexity, morphologyNovelty, idiomDensity, difficultyScore)
    }
}
