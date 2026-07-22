package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import io.legado.app.data.entities.AiAgentJob
import io.legado.app.data.entities.AiAgentSession
import io.legado.app.data.entities.AiAgentTrace

@Dao
interface AiAgentDao {

    @Query("SELECT * FROM ai_agent_sessions WHERE sessionId = :sessionId LIMIT 1")
    fun session(sessionId: String): AiAgentSession?

    @Query("SELECT * FROM ai_agent_jobs WHERE jobId = :jobId LIMIT 1")
    fun job(jobId: String): AiAgentJob?

    @Query(
        """
        SELECT * FROM ai_agent_jobs
        WHERE status IN ('pending', 'running', 'waiting_resume')
        ORDER BY updatedAt DESC
        """
    )
    fun activeJobs(): List<AiAgentJob>

    @Query(
        """
        SELECT * FROM ai_agent_jobs
        WHERE status = 'running' AND leaseUntil > 0 AND leaseUntil < :now
        ORDER BY updatedAt DESC
        """
    )
    fun expiredRunningJobs(now: Long): List<AiAgentJob>

    @Query("SELECT * FROM ai_agent_traces WHERE jobId = :jobId ORDER BY createdAt ASC")
    fun traces(jobId: String): List<AiAgentTrace>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertSession(session: AiAgentSession)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertJob(job: AiAgentJob)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertTrace(trace: AiAgentTrace)

    @Query("UPDATE ai_agent_sessions SET status = :status, lastError = :error, updatedAt = :updatedAt WHERE sessionId = :sessionId")
    fun updateSessionStatus(sessionId: String, status: String, error: String = "", updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE ai_agent_jobs SET status = :status, error = :error, outputJson = :outputJson, leaseUntil = 0, updatedAt = :updatedAt WHERE jobId = :jobId")
    fun finishJob(
        jobId: String,
        status: String,
        error: String = "",
        outputJson: String = "",
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query("UPDATE ai_agent_jobs SET status = :status, checkpointJson = :checkpointJson, leaseUntil = :leaseUntil, updatedAt = :updatedAt WHERE jobId = :jobId AND status = 'running'")
    fun updateJobCheckpoint(
        jobId: String,
        status: String,
        checkpointJson: String,
        leaseUntil: Long,
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query(
        """
        UPDATE ai_agent_jobs
        SET status = :status,
            error = :error,
            leaseUntil = 0,
            nextRunAt = :nextRunAt,
            retryCount = retryCount + 1,
            updatedAt = :updatedAt
        WHERE jobId = :jobId
        """
    )
    fun markJobWaitingResume(
        jobId: String,
        status: String = AiAgentJob.STATUS_WAITING_RESUME,
        error: String = "",
        nextRunAt: Long = 0L,
        updatedAt: Long = System.currentTimeMillis()
    )

    /**
     * 单事务批量 sweep 过期 running job：避免中途异常留下部分 job 已切回 waitingResume、
     * 部分仍是 running 的不一致状态。每条 job 的事务边界 = (job 行 + session 行)。
     */
    @Transaction
    fun sweepExpiredRunningJobs(now: Long, nextRunAt: Long, reason: String): Int {
        val expired = expiredRunningJobs(now)
        expired.forEach { job ->
            markJobWaitingResume(
                jobId = job.jobId,
                error = reason,
                nextRunAt = nextRunAt,
                updatedAt = now
            )
            updateSessionStatus(
                sessionId = job.sessionId,
                status = AiAgentSession.STATUS_WAITING_RESUME,
                error = reason,
                updatedAt = now
            )
        }
        return expired.size
    }
}
