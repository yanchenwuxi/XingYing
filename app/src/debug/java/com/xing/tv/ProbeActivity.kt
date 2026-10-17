package com.xing.tv

import android.app.Activity
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import com.xing.tv.api.ConfigHolder
import com.xing.tv.api.ConfigLoader
import com.xing.tv.api.SourceFactory
import com.xing.tv.api.T4Source
import com.xing.tv.py.PySource
import com.xing.tv.proxy.LocalProxyServer
import com.xing.tv.proxy.Sniffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 仅存在于 debug 源集的测试探针页（release 构建不包含）。
 *
 * 用途：在无法注入 UI 事件的设备上，用 `am start` 直接驱动各数据源，
 * 把结果写到界面上，再用 uiautomator dump 读回，实现无点击的端到端验证。
 *
 *   adb shell am start -n com.xing.tv/.ProbeActivity \
 *     --es config http://127.0.0.1:8099/config.json \
 *     --es action home --es site t1json
 *
 * action: load | home | cat | detail | search | play | py
 */
class ProbeActivity : Activity() {

    private lateinit var tv: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tv = TextView(this).apply {
            textSize = 11f
            setPadding(24, 24, 24, 24)
        }
        setContentView(ScrollView(this).apply { addView(tv) })

        val cfgUrl = intent.getStringExtra("config") ?: ""
        val action = intent.getStringExtra("action") ?: "home"
        val siteKey = intent.getStringExtra("site") ?: ""
        val id = intent.getStringExtra("id") ?: ""
        val kw = intent.getStringExtra("kw") ?: ""
        val tid = intent.getStringExtra("tid") ?: ""
        val pg = intent.getStringExtra("pg")?.toIntOrNull() ?: 1
        val flag = intent.getStringExtra("flag") ?: ""

        tv.text = "RUNNING..."
        GlobalScope.launch(Dispatchers.Main) {
            val out = withContext(Dispatchers.IO) { run(cfgUrl, action, siteKey, id, kw, tid, pg, flag) }
            tv.text = out
        }
    }

    private fun run(
        cfgUrl: String, action: String, siteKey: String,
        id: String, kw: String, tid: String, pg: Int, flag: String
    ): String {
        return try {
            if (cfgUrl.isNotEmpty()) {
                val cfg = ConfigLoader.load(cfgUrl, force = true)
                ConfigHolder.update(cfg, cfgUrl)
                if (action == "load") {
                    return "LOAD OK sites=${cfg.sites.size} parses=${cfg.parses.size} " +
                            "names=" + cfg.sites.joinToString("/") { it.name }
                }
            }
            // 嗅探与站点无关，单独处理
            if (action == "sniff") {
                val direct = Sniffer.isDirect(id)
                val resolved = Sniffer.resolve(id)
                return "SNIFF direct=$direct resolved=$resolved"
            }
            if (action == "proxy") {
                // 代理分发依赖已加载的 py 脚本，先加载站点再发请求
                val s = ConfigHolder.siteOf(siteKey)
                if (s != null && s.isPy) {
                    val ps = SourceFactory.create(s) as? PySource
                    ps?.detail("P7001") // 触发脚本加载
                    LocalProxyServer.setSiteKey(s.key)
                }
                return runProxy(id)
            }
            val site = ConfigHolder.siteOf(siteKey)
                ?: return "NO SITE key=$siteKey known=" + ConfigHolder.sites.joinToString(",") { it.key }
            val src = SourceFactory.create(site)
            when (action) {
                "home" -> {
                    val r = src.home(1)
                    "HOME site=${site.name} type=${site.type} items=${r.items.size} " +
                            "names=" + r.items.joinToString(" | ") { it.vodName }
                }
                "ui" -> {
                    // 模拟 MainActivity 真实调用顺序：先分类目录，再首页列表，再翻页
                    val cats = src.categories()
                    val p1 = src.home(1)
                    val p2 = src.home(2)
                    "UI site=${site.name} type=${site.type} " +
                            "cats=${cats.size}[${cats.joinToString(",") { it.name }}] " +
                            "p1=${p1.items.size}(pages=${p1.pageCount})[${p1.items.joinToString(",") { it.vodName }}] " +
                            "p2=${p2.items.size}[${p2.items.joinToString(",") { it.vodName }}]"
                }
                "cat" -> {
                    val cats = src.categories()
                    val r = src.category(tid, pg)
                    "CAT site=${site.name} categories=${cats.size}[${cats.joinToString(",") { it.name }}] " +
                            "items=${r.items.size} names=" + r.items.joinToString(" | ") { it.vodName }
                }
                "detail" -> {
                    val d = src.detail(id) ?: return "DETAIL null id=$id"
                    val lines = d.lines.joinToString(" || ") { ln ->
                        "${ln.name}(${ln.episodes.size}): " +
                                ln.episodes.take(3).joinToString(",") { it.name }
                    }
                    "DETAIL name=${d.item.vodName} actor=${d.vodActor} lines=${d.lines.size} -> $lines"
                }
                "search" -> {
                    val r = src.search(kw, pg)
                    "SEARCH kw=$kw items=${r.items.size} names=" + r.items.joinToString(" | ") { it.vodName }
                }
                "play" -> {
                    when {
                        site.isPy -> {
                            val p = (src as PySource).player(flag, id)
                            "PYPLAY parse=${p.first} url=${p.second}"
                        }
                        site.type == 4 -> "T4PLAY url=${(src as T4Source).play(flag, id)}"
                        else -> "PLAY(direct) url=$id"
                    }
                }
                else -> "UNKNOWN action=$action"
            }
        } catch (t: Throwable) {
            "ERROR ${t.javaClass.simpleName}: ${t.message}"
        }
    }

    /**
     * 验证本机代理链路：启动 LocalProxyServer，发一个 HTTP 请求，
     * 应用侧把请求转给已加载 py 源的 localProxy()，返回其产出内容。
     */
    private fun runProxy(path: String): String {
        val port = LocalProxyServer.ensureStarted()
        if (port == 0) return "PROXY start failed"
        val url = "http://127.0.0.1:$port/$path"
        return runCatching {
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 8000
            val code = conn.responseCode
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            "PROXY port=$port code=$code body=${body.trim().take(140).replace("\n", "|")}"
        }.getOrElse { "PROXY port=$port error: $it" }
    }
}
