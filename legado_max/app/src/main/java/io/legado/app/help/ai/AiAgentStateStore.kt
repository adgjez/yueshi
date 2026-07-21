package io.legado.app.help.ai

import io.legado.app.data.appDb
import io.legado.app.data.entities.AiAgentJob
import io.legado.app.data.entities.AiAgentSession
import io.legado.app.data.entities.AiAgentTrace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID

object AiAgentStateStore {

    private const val DEFAULT_LEASE_MILLIS = 10 * 60 * 1000L
    private const val EXPIRY_SWEEP_INTERVAL_MILLIS = 60_000L

    /**
     * 进程级维护协程：定期把 lease 过期但仍标记 RUNNING 的 job 转为 WAITING_RESUME。
     *
     * 替代此前在 [activeJobs] / [startRun] 入口的同步调用，避免每次读活跃任务都触发
     * DB 查询。`startRun` 仍保留一次同步 sweep 以保证新 run 启动前状态干净。
     * scope 随 object 单例生命周期常驻，进程退出即销毁。
     */
    private val maintenanceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        maintenanceScope.launch {
            while (isActive) {
                delay(EXPIRY_SWEEP_INTERVAL_MILLIS)
                runCatching { markExpiredRunningJobs() }
            }
        }
    }

    data class Run(
        val sessionId: String,
        val jobId: String,
        val scope: String,
        val type: String
    )

    fun startRun(
        sessionId: String = UUID.randomUUID().toString(),
        scope: String,
        type: String,
        currentGoal: String = "",
        currentTask: String = "",
        inputJson: String = ""
    ): Run {
        markExpiredRunningJobs()
        val now = System.currentTimeMillis()
        val jobId = UUID.randomUUID().toString()
        appDb.aiAgentDao.upsertSession(
            AiAgentSession(
                sessionId = sessionId,
                scope = scope,
                status = AiAgentSession.STATUS_RUNNING,
                currentGoal = currentGoal.take(2_000),
                currentTask = currentTask.take(2_000),
                currentStep = "start",
                createdAt = appDb.aiAgentDao.session(sessionId)?.createdAt ?: now,
                updatedAt = now
            )
        )
        appDb.aiAgentDao.upsertJob(
            AiAgentJob(
                jobId = jobId,
                sessionId = sessionId,
                type = type,
                status = AiAgentJob.STATUS_RUNNING,
                inputJson = inputJson.take(16_000),
                leaseUntil = now + DEFAULT_LEASE_MILLIS,
                createdAt = now,
                updatedAt = now
            )
        )
        val run = Run(sessionId, jobId, scope, type)
        trace(run, AiAgentTrace.EVENT_STATUS, JSONObject().put("stage", "start"))
        return run
    }

    fun trace(
        run: Run?,
        eventType: String,
        payload: JSONObject,
        round: Int = 0,
        success: Boolean = true,
        usage: AiUsageStats? = null,
        checkpointPayload: JSONObject? = null
    ) {
        if (run == null) return
        val now = System.currentTimeMillis()
        insertTrace(
            sessionId = run.sessionId,
            jobId = run.jobId,
            eventType = eventType,
            payload = payload,
            round = round,
            success = success,
            usage = usage,
            createdAt = now
        )
        val checkpoint = buildCheckpoint(
            eventType = eventType,
            payload = payload,
            round = round,
            success = success,
            updatedAt = now,
            checkpointPayload = checkpointPayload
        )
        appDb.aiAgentDao.updateJobCheckpoint(
            jobId = run.jobId,
            status = AiAgentJob.STATUS_RUNNING,
            checkpointJson = checkpoint.toString(),
            leaseUntil = now + DEFAULT_LEASE_MILLIS,
            updatedAt = now
        )
    }

    fun markWaitingResume(
        run: Run?,
        reason: String,
        delayMillis: Long = 5_000L
    ) {
        if (run == null) return
        val now = System.currentTimeMillis()
        val nextRunAt = now + delayMillis.coerceAtLeast(0L)
        appDb.aiAgentDao.markJobWaitingResume(
            jobId = run.jobId,
            error = reason.take(4_000),
            nextRunAt = nextRunAt,
            updatedAt = now
        )
        appDb.aiAgentDao.updateSessionStatus(
            sessionId = run.sessionId,
            status = AiAgentSession.STATUS_WAITING_RESUME,
            error = reason.take(4_000),
            updatedAt = now
        )
    }

    /**
     * 独立埋点：不依赖 run 上下文，直接插入 AiAgentTrace。
     *
     * 适用于 read aloud 角色分配、AI 图像生成等"非 agent run"的 LLM 调用。
     * 使用 scope 作为 sessionId 便于按场景查询（如 "read_aloud" / "image"）。
     */
    fun traceStandalone(
        scope: String,
        eventType: String,
        payload: JSONObject,
        success: Boolean = true,
        usage: AiUsageStats? = null
    ) {
        insertTrace(
            sessionId = scope,
            jobId = "",
            eventType = eventType,
            payload = payload,
            success = success,
            usage = usage
        )
    }

    private fun insertTrace(
        sessionId: String,
        jobId: String,
        eventType: String,
        payload: JSONObject,
        round: Int = 0,
        success: Boolean = true,
        usage: AiUsageStats? = null,
        createdAt: Long = System.currentTimeMillis()
    ) {
        appDb.aiAgentDao.insertTrace(
            AiAgentTrace(
                sessionId = sessionId,
                jobId = jobId,
                round = round,
                eventType = eventType,
                payloadJson = payload.toString().take(16_000),
                usageJson = usage?.toJson()?.toString().orEmpty(),
                success = success,
                createdAt = createdAt
            )
        )
    }

    fun finish(run: Run?, success: Boolean, outputJson: String = "", error: String = "") {
        if (run == null) return
        val status = if (success) AiAgentJob.STATUS_DONE else AiAgentJob.STATUS_FAILED
        val sessionStatus = if (success) AiAgentSession.STATUS_DONE else AiAgentSession.STATUS_FAILED
        val now = System.currentTimeMillis()
        trace(
            run = run,
            eventType = if (success) AiAgentTrace.EVENT_STATUS else AiAgentTrace.EVENT_ERROR,
            payload = JSONObject()
                .put("stage", if (success) "finish" else "failed")
                .put("error", error.take(4_000)),
            success = success
        )
        appDb.aiAgentDao.finishJob(
            jobId = run.jobId,
            status = status,
            error = error.take(4_000),
            outputJson = outputJson.take(16_000),
            updatedAt = now
        )
        appDb.aiAgentDao.updateSessionStatus(
            sessionId = run.sessionId,
            status = sessionStatus,
            error = error.take(4_000),
            updatedAt = now
        )
    }

    fun cancel(run: Run?, reason: String = "") {
        if (run == null) return
        val now = System.currentTimeMillis()
        appDb.aiAgentDao.finishJob(
            jobId = run.jobId,
            status = AiAgentJob.STATUS_CANCELLED,
            error = reason.take(4_000),
            updatedAt = now
        )
        appDb.aiAgentDao.updateSessionStatus(
            sessionId = run.sessionId,
            status = AiAgentSession.STATUS_CANCELLED,
            error = reason.take(4_000),
            updatedAt = now
        )
    }

    fun activeJobs(): List<AiAgentJob> {
        return appDb.aiAgentDao.activeJobs()
    }

    fun markExpiredRunningJobs(now: Long = System.currentTimeMillis()) {
        // 委托给 DAO 层 @Transaction 方法，保证整批 sweep 在单事务内完成，
        // 避免中途异常留下部分 job 已切回 waitingResume、部分仍是 running 的不一致状态。
        appDb.aiAgentDao.sweepExpiredRunningJobs(
            now = now,
            nextRunAt = now + 5_000L,
            reason = "任务被系统中断，等待恢复"
        )
    }

    private fun buildCheckpoint(
        eventType: String,
        payload: JSONObject,
        round: Int,
        success: Boolean,
        updatedAt: Long,
        checkpointPayload: JSONObject?
    ): JSONObject {
        val checkpoint = checkpointPayload?.let {
            runCatching { JSONObject(it.toString()) }.getOrNull()
        } ?: JSONObject()
        checkpoint.put("eventType", eventType)
        checkpoint.put("round", checkpoint.optInt("round", round))
        if (!checkpoint.has("stage")) {
            checkpoint.put("stage", payload.optString("stage"))
        }
        if (!checkpoint.has("toolName")) {
            checkpoint.put("toolName", payload.optString("name"))
        }
        checkpoint.put("success", success)
        checkpoint.put("updatedAt", updatedAt)
        return checkpoint
    }

    private fun AiUsageStats.toJson(): JSONObject {
        return JSONObject()
            .put("inputTokens", inputTokens)
            .put("cachedInputTokens", cachedInputTokens)
            .put("outputTokens", outputTokens)
            .put("totalTokens", totalTokens)
    }
}
