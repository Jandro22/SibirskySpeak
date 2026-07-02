package com.sibirskyspeak.learning

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sibirskyspeak.data.AppDatabase
import com.sibirskyspeak.data.OptimizerParameter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModelRecoveryInstrumentedTest {
    @Test fun optimizerSnapshotTransactionSurvivesDatabaseCloseAndReopen(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "model-recovery-${System.nanoTime()}.db"
        try {
            val db = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
            try {
                db.learningModelDao().upsertParameters(listOf(
                    OptimizerParameter(ModelGovernance.CURRENT_VERSION_KEY, 2.0),
                    OptimizerParameter(ModelGovernance.snapshotKey(2, "retention"), .89),
                    OptimizerParameter("obsolete_policy_key", 1.0)
                ))
                db.learningModelDao().replaceParameters(
                    listOf("obsolete_policy_key"),
                    listOf(OptimizerParameter("active_policy_key", .89))
                )
            } finally { db.close() }
            val reopened = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
            try {
                val parameters = reopened.learningModelDao().parameters().associateBy { it.key }
                assertEquals(2.0, parameters.getValue(ModelGovernance.CURRENT_VERSION_KEY).value, 0.0)
                assertTrue(parameters.getValue(ModelGovernance.snapshotKey(2, "retention")).value.isFinite())
                assertTrue("obsolete_policy_key" !in parameters)
                assertEquals(.89, parameters.getValue("active_policy_key").value, 0.0)
            } finally { reopened.close() }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test fun malformedRestoredBanditStateIsRepairedOnDevice() {
        val bandit = ContextualBandit(3)
        bandit.restore(listOf(ContextualBandit.Snapshot("x", -1, doubleArrayOf(Double.NaN), doubleArrayOf(0.0))))
        assertTrue(bandit.score("x", doubleArrayOf(Double.NaN)).isFinite())
        assertEquals(3, bandit.snapshot().single().precision.size)
    }

    @Test fun corruptPersistedNumericsCannotPoisonAdaptiveModelsAfterRestart(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "model-corrupt-${System.nanoTime()}.db"
        try {
            val db = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
            try {
                db.learningModelDao().upsertParameters(listOf(
                    OptimizerParameter("global_skill_mu", Double.NaN),
                    OptimizerParameter("global_skill_sigma", Double.POSITIVE_INFINITY)
                ))
            } finally { db.close() }
            val reopened = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
            try {
                val values = reopened.learningModelDao().parameters().associate { it.key to it.value }
                val result = TrueSkill.update(
                    Gaussian(values.getValue("global_skill_mu"), values.getValue("global_skill_sigma")),
                    Gaussian(), MatchOutcome.WIN
                )
                assertTrue(result.a.mu.isFinite() && result.a.sigma.isFinite())
            } finally { reopened.close() }
        } finally {
            context.deleteDatabase(name)
        }
    }
}
