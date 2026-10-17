package com.xing.tv.api

import android.util.Log
import com.google.gson.JsonParser
import com.xing.tv.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.net.IDN

/**
 * 配置加载扩展：中文域名 Punycode、单仓/多仓识别。
 * 与 [ConfigLoader] 互补——本对象面向「先判断结构再决定是否展开多仓」的 UI 流程。
 */
object ConfigLoaderExtension {
    private const val TAG = "ConfigLoaderExt"

    suspend fun loadRemoteConfig(rawUrl: String): ConfigResult = withContext(Dispatchers.IO) {
        try {
            val normalizedUrl = convertChineseDomainToPunycode(rawUrl)
            val responseString = Http.get(normalizedUrl, api = true)
            parseConfigContent(responseString)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load config from $rawUrl", e)
            ConfigResult.Error(e.message ?: "Unknown network exception")
        }
    }

    fun convertChineseDomainToPunycode(urlString: String): String {
        return try {
            // 优先 OkHttp 规范化（与 Http.normalizeUrl 一致）
            runCatching { urlString.toHttpUrl().toString() }.getOrNull()?.let { return it }
            val uri = java.net.URI(urlString)
            val host = uri.host ?: return urlString
            val asciiHost = IDN.toASCII(host, IDN.ALLOW_UNASSIGNED)
            if (asciiHost == host) urlString
            else urlString.replace(host, asciiHost)
        } catch (e: Exception) {
            Log.w(TAG, "IDN conversion skipped: $urlString", e)
            urlString
        }
    }

    fun parseConfigContent(content: String): ConfigResult {
        return try {
            val body = ConfigLoader.cleanForParse(content)
            val el = JsonParser.parseString(body)
            if (el.isJsonArray) {
                return ConfigResult.StandardConfig(body)
            }
            if (!el.isJsonObject) {
                return ConfigResult.Error("Unrecognized JSON config structure")
            }
            val json = el.asJsonObject
            when {
                json.has("urls") && json.get("urls").isJsonArray -> {
                    val arr = json.getAsJsonArray("urls")
                    val subConfigs = mutableListOf<SubConfigItem>()
                    for (i in 0 until arr.size()) {
                        val item = arr[i]
                        when {
                            item.isJsonObject -> {
                                val o = item.asJsonObject
                                val name = o.get("name")?.takeIf { it.isJsonPrimitive }?.asString
                                    ?: "线路 ${i + 1}"
                                val url = o.get("url")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                                if (url.isNotEmpty()) subConfigs.add(SubConfigItem(name, url))
                            }
                            item.isJsonPrimitive -> {
                                val url = item.asString
                                if (url.isNotEmpty()) subConfigs.add(SubConfigItem("线路 ${i + 1}", url))
                            }
                        }
                    }
                    ConfigResult.MultiConfig(subConfigs)
                }
                json.has("stores") || json.has("storehouse") || json.has("sites") ->
                    ConfigResult.StandardConfig(body)
                else ->
                    ConfigResult.Error("Unrecognized JSON config structure. Missing 'urls' or 'sites'.")
            }
        } catch (e: Exception) {
            ConfigResult.Error("JSON Parsing Exception: ${e.message}")
        }
    }
}

sealed class ConfigResult {
    data class StandardConfig(val rawJson: String) : ConfigResult()
    data class MultiConfig(val items: List<SubConfigItem>) : ConfigResult()
    data class Error(val message: String) : ConfigResult()
}

data class SubConfigItem(
    val name: String,
    val url: String
)
