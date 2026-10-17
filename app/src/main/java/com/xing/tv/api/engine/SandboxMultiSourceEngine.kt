package com.xing.tv.api.engine

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 多源沙箱执行引擎：超时隔离、失败熔断、回退。
 * 用于嗅探/解析类任务，避免单个坏源拖垮整条播放链路。
 */
class SandboxMultiSourceEngine {

    companion object {
        private const val TAG = "SandboxEngine"
        private const val DEFAULT_TIMEOUT_MS = 6000L
        private const val MAX_FAIL_COUNT = 3

        @Volatile
        private var instance: SandboxMultiSourceEngine? = null

        fun get(): SandboxMultiSourceEngine = instance ?: synchronized(this) {
            instance ?: SandboxMultiSourceEngine().also { instance = it }
        }
    }

    private val failureCounter = ConcurrentHashMap<String, AtomicInteger>()

    suspend fun executeInSandbox(
        sourceId: String,
        sourceType: String,
        rule: String,
        targetUrl: String,
        fallbackProvider: suspend () -> String?
    ): String? {
        if (isCircuitBroken(sourceId)) {
            Log.w(TAG, "Circuit open for $sourceId, fallback")
            return fallbackProvider()
        }
        return try {
            withTimeout(DEFAULT_TIMEOUT_MS) {
                withContext(Dispatchers.Default) {
                    when (sourceType.lowercase()) {
                        "json" -> parseJsonSafely(rule, targetUrl)
                        "xpath" -> parseXPathSafely(rule, targetUrl)
                        "py" -> parsePythonSafely(rule, targetUrl)
                        else -> throw IllegalArgumentException("Unsupported sandbox type: $sourceType")
                    }
                }
            }.also { resetFailureCount(sourceId) }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Timeout: $sourceId")
            recordFailure(sourceId)
            fallbackProvider()
        } catch (e: Throwable) {
            Log.e(TAG, "Crash: $sourceId → ${e.message}", e)
            recordFailure(sourceId)
            fallbackProvider()
        }
    }

    /**
     * 实际解析由上层注入；默认返回 targetUrl 作为可播放地址占位，
     * 避免未接规则时整链失败。接入嗅探规则后替换内部实现即可。
     */
    private suspend fun parseJsonSafely(rule: String, url: String): String {
        if (rule.isBlank()) return url
        return url
    }

    private suspend fun parseXPathSafely(rule: String, url: String): String {
        if (rule.isBlank()) return url
        return url
    }

    private suspend fun parsePythonSafely(script: String, url: String): String {
        // Python 源已有 PyBridge；沙箱层只做熔断包装
        if (script.isBlank()) return url
        return url
    }

    private fun isCircuitBroken(sourceId: String): Boolean {
        val count = failureCounter[sourceId]?.get() ?: 0
        return count >= MAX_FAIL_COUNT
    }

    private fun recordFailure(sourceId: String) {
        val counter = failureCounter.getOrPut(sourceId) { AtomicInteger(0) }
        val current = counter.incrementAndGet()
        Log.w(TAG, "Source $sourceId failures=$current")
    }

    private fun resetFailureCount(sourceId: String) {
        failureCounter[sourceId]?.set(0)
    }

    fun resetAll() {
        failureCounter.clear()
    }
}
