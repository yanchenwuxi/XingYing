package com.xing.tv.py

import android.content.Context
import android.util.Log
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.xing.tv.net.Http
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Chaquopy 运行时桥接：启动 CPython，加载 py 源脚本并按 TVBox py 约定分发调用。
 */
object PyBridge {

    private const val TAG = "PyBridge"
    private const val PY_DIR = "py"

    @Volatile
    private var started = false

    private val spiders = ConcurrentHashMap<String, com.chaquo.python.PyObject>()

    /** 最近一次失败原因，便于 UI 提示 */
    @Volatile
    var lastError: String = ""
        private set

    private fun ensureStarted(context: Context) {
        if (started) return
        synchronized(this) {
            if (started) return
            if (!Python.isStarted()) {
                Python.start(AndroidPlatform(context.applicationContext))
            }
            started = true
        }
    }

    private fun pyRoot(context: Context): File =
        File(context.filesDir, PY_DIR).apply { mkdirs() }

    /**
     * 加载站点脚本。
     * @param api 站点 api：py_xxx / xxx.py / https://.../xxx.py
     * @param ext 站点 ext：脚本 URL 或附加参数
     */
    @Synchronized
    fun load(context: Context, key: String, api: String, ext: String): Boolean {
        return try {
            lastError = ""
            ensureStarted(context)
            val dir = pyRoot(context)

            // 公共库 + 全局爬虫包（多数 py 脚本只写 api=py_xxx，真实文件在 jar 里）
            runCatching { ensurePyBase(key, dir) }
            runCatching { ensureSpiderJarReady() }

            val scriptFile = resolveScriptFile(dir, key, api, ext)
            if (scriptFile == null || !scriptFile.exists() || scriptFile.length() == 0L) {
                lastError = "找不到 Python 脚本（api=$api ext=$ext jar=${com.xing.tv.api.ConfigHolder.spiderJar}）。请确认配置含 spider 爬虫包，或 ext/api 为 .py 地址"
                Log.e(TAG, lastError)
                spiders.remove(key)
                return false
            }

            val scriptName = scriptFile.name.removeSuffix(".py")
            val app = Python.getInstance().getModule("app")
            val obj = app.callAttr("spider", dir.absolutePath, scriptName)

            // 空命名空间 = 加载失败
            if (obj == null || isEmptyNs(obj)) {
                lastError = "脚本加载失败：${scriptFile.name}（语法错误或文件为空）"
                Log.e(TAG, lastError)
                spiders.remove(key)
                return false
            }

            // 依赖
            runCatching {
                val deps = app.callAttr("getDependence", obj).asList()
                deps.forEach { dep ->
                    val name = dep.toString()
                    val file = File(dir, "$name.py")
                    if (!file.exists()) {
                        val base = downloadBase(api, ext)
                        if (base.isNotEmpty()) {
                            runCatching { Http.download("$base/$name.py", file) }
                                .onFailure { Log.w(TAG, "dep $name failed: $it") }
                        }
                    }
                }
            }

            app.callAttr("setKey", obj, key)
            val site = com.xing.tv.api.ConfigHolder.siteOf(key)
            val initExt = site?.ext?.takeIf { !it.endsWith(".py") && !it.contains(".py?") } ?: ""
            app.callAttr("init", obj, initExt)

            spiders[key] = obj
            Log.i(TAG, "loaded py spider key=$key file=${scriptFile.name}")
            true
        } catch (t: Throwable) {
            lastError = t.message ?: "unknown"
            Log.e(TAG, "load py spider failed key=$key", t)
            spiders.remove(key)
            false
        }
    }

    private fun isEmptyNs(obj: com.chaquo.python.PyObject): Boolean {
        return try {
            // 类式 Spider 实例：通过 has_method 探测
            val app = Python.getInstance().getModule("app")
            val hasHome = runCatching {
                app.callAttr("has_method", obj, "homeContent")?.toBoolean() == true
            }.getOrDefault(false)
            if (hasHome) return false
            val hasCat = runCatching {
                app.callAttr("has_method", obj, "categoryContent")?.toBoolean() == true
            }.getOrDefault(false)
            if (hasCat) return false
            // 模块 dict 命名空间
            val keys = obj.asMap()?.keys.orEmpty()
            val names = keys.map { it.toString() }.toSet()
            if (names.any { it in setOf("homeContent", "categoryContent", "detailContent", "searchContent", "init", "Spider") }) {
                return false
            }
            true
        } catch (_: Throwable) {
            val s = obj.toString()
            s == "{}" || s == "None" || s.isBlank()
        }
    }

    /**
     * 解析并确保本地存在 .py 文件。
     * 优先级：ext 为 URL → api 为 URL → 已有本地文件（api 名）.
     */
    private fun resolveScriptFile(dir: File, key: String, api: String, ext: String): File? {
        // 1) ext 是远程脚本
        val extUrl = ext.trim()
        if (extUrl.startsWith("http") && (extUrl.contains(".py") || extUrl.endsWith(".py"))) {
            val name = fileNameFromUrl(extUrl, "$key.py")
            val target = File(dir, name)
            if (needDownload(target)) {
                runCatching { Http.download(extUrl, target) }
                    .onFailure { Log.w(TAG, "download ext failed: $it") }
            }
            if (target.exists() && target.length() > 0L) return target
        }

        // 2) api 是远程脚本
        val apiTrim = api.trim()
        if (apiTrim.startsWith("http") && (apiTrim.contains(".py") || apiTrim.endsWith(".py"))) {
            val name = fileNameFromUrl(apiTrim, "$key.py")
            val target = File(dir, name)
            if (needDownload(target)) {
                runCatching { Http.download(apiTrim, target) }
                    .onFailure { Log.w(TAG, "download api failed: $it") }
            }
            if (target.exists() && target.length() > 0L) return target
        }

        // 3) 本地已有：api.py / api 去 py_ 前缀
        val candidates = listOf(
            apiTrim.removeSuffix(".py") + ".py",
            apiTrim,
            apiTrim.removePrefix("py_") + ".py",
            "$key.py"
        ).distinct()
        for (c in candidates) {
            val f = File(dir, c.substringAfterLast('/'))
            if (f.exists() && f.length() > 0L) return f
        }

        // 4) ext 形如相对名 xxx.py，且同目录有 base URL（从上次成功下载推断不可行）
        if (extUrl.endsWith(".py") && !extUrl.startsWith("http")) {
            val f = File(dir, extUrl.substringAfterLast('/'))
            if (f.exists() && f.length() > 0L) return f
        }

        // 5) 影视仓写法：脚本放在爬虫包里（api=py_xxx → 包内 py/xxx.py 或 xxx.py）
        extractFromJar(dir, key, apiTrim, extUrl)?.let { return it }

        return null
    }

    /**
     * 从爬虫 jar 里抠出 py 脚本落到本地目录。
     *
     * TVBox 体系的 py 源绝大多数不在配置里给 URL，而是 `api=py_xxx` + 一个全局
     * 爬虫包，脚本躺在包内 `py/xxx.py`。少了这条，这类站点会全部报「找不到脚本」。
     */
    private fun extractFromJar(dir: File, key: String, api: String, ext: String): File? {
        val spec = com.xing.tv.api.ConfigHolder.siteOf(key)?.jar?.takeIf { it.isNotBlank() }
            ?: com.xing.tv.api.ConfigHolder.spiderJar?.takeIf { it.isNotBlank() }
            ?: return null
        val pkg = runCatching { com.xing.tv.spider.js.JarLoader.ensure(spec) }.getOrNull() ?: return null

        // 先补公共库：TVBox 的 py 脚本普遍 `from base.spider import Spider`
        extractPyBase(pkg, dir)

        val bare = api.removePrefix("py_").removePrefix("./").removePrefix("py/").substringAfterLast('/')
            .removeSuffix(".py")
        val names = listOf(
            "$bare.py",
            "py_$bare.py",
            bare,
            ext.removePrefix("./").substringAfterLast('/').takeIf { it.endsWith(".py") } ?: "",
            "$key.py",
            "py_$key.py"
        ).filter { it.isNotBlank() }.distinct()

        for (name in names) {
            for (prefix in listOf("py/", "python/", "spider/", "scripts/", "")) {
                val bytes = runCatching {
                    com.xing.tv.spider.js.JarLoader.entry(pkg, prefix + name)
                }.getOrNull() ?: continue
                if (bytes.isEmpty()) continue
                val target = File(dir, name)
                runCatching { target.writeBytes(bytes) }
                if (target.exists() && target.length() > 0L) {
                    Log.i(TAG, "py script from jar: ${prefix + name}")
                    return target
                }
            }
        }
        return null
    }

    /**
     * 把爬虫包里的 python 公共库解到脚本目录。
     *
     * 影视仓那套 py 源几乎都写 `from base.spider import Spider`，而 base/ 目录
     * 不在配置里、只在包里。少了它，脚本 exec 阶段就 ImportError。
     */
    /** 预下载配置里的全局 spider jar，供 py 脚本 extractFromJar 使用 */
    private fun ensureSpiderJarReady() {
        val spec = com.xing.tv.api.ConfigHolder.spiderJar?.takeIf { it.isNotBlank() } ?: return
        runCatching {
            com.xing.tv.spider.js.JarLoader.ensure(spec)
        }.onFailure { Log.w(TAG, "ensure spider jar failed: $spec", it) }
    }

    private fun ensurePyBase(key: String, dir: File) {
        // 先从 APK assets 落一份最小 base.spider，保证 from base.spider import Spider 可用
        extractBaseFromAssets(dir)
        val spec = com.xing.tv.api.ConfigHolder.siteOf(key)?.jar?.takeIf { it.isNotBlank() }
            ?: com.xing.tv.api.ConfigHolder.spiderJar?.takeIf { it.isNotBlank() }
            ?: return
        val pkg = runCatching { com.xing.tv.spider.js.JarLoader.ensure(spec) }.getOrNull() ?: return
        extractPyBase(pkg, dir)
    }

    private fun extractPyBase(pkg: java.io.File, dir: File) {
        // jar 里的 base 通常更完整，覆盖 assets 精简版
        val names = runCatching { com.xing.tv.spider.js.JarLoader.list(pkg) }.getOrDefault(emptyList())
        var count = 0
        for (name in names) {
            val rel = when {
                name.startsWith("base/") -> name
                name.startsWith("py/base/") -> name.removePrefix("py/")
                else -> continue
            }
            if (!rel.endsWith(".py")) continue
            val bytes = runCatching { com.xing.tv.spider.js.JarLoader.entry(pkg, name) }.getOrNull() ?: continue
            if (bytes.isEmpty()) continue
            val target = File(dir, rel)
            target.parentFile?.mkdirs()
            runCatching { target.writeBytes(bytes) }.onSuccess { count++ }
        }
        if (count > 0) Log.i(TAG, "py base extracted: $count files")
    }

    /** 从 assets/py/base 复制内置 base.spider，避免无爬虫包时 import 失败 */
    private fun extractBaseFromAssets(dir: File) {
        val marker = File(dir, "base/spider.py")
        if (marker.exists() && marker.length() > 0L) return
        try {
            val ctx = com.xing.tv.App.instance
            val am = ctx.assets
            val names = runCatching { am.list("py/base")?.toList().orEmpty() }.getOrDefault(emptyList())
            if (names.isEmpty()) {
                runCatching {
                    am.open("py/base/spider.py").use { input ->
                        marker.parentFile?.mkdirs()
                        marker.outputStream().use { input.copyTo(it) }
                    }
                }.onSuccess { Log.i(TAG, "py base from assets: spider.py") }
                return
            }
            for (name in names) {
                if (!name.endsWith(".py")) continue
                val target = File(dir, "base/$name")
                target.parentFile?.mkdirs()
                runCatching {
                    am.open("py/base/$name").use { input ->
                        target.outputStream().use { input.copyTo(it) }
                    }
                }
            }
            Log.i(TAG, "py base from assets: ${names.size} files")
        } catch (t: Throwable) {
            Log.w(TAG, "extractBaseFromAssets failed", t)
        }
    }

    private fun needDownload(f: File): Boolean =
        !f.exists() || f.length() == 0L

    private fun fileNameFromUrl(url: String, fallback: String): String {
        val raw = url.substringAfterLast('/').substringBefore('?').ifBlank { fallback }
        return if (raw.endsWith(".py")) raw else "$raw.py"
    }

    private fun downloadBase(api: String, ext: String): String {
        val u = when {
            ext.startsWith("http") -> ext
            api.startsWith("http") -> api
            else -> ""
        }
        return u.substringBeforeLast('/').trimEnd('/')
    }

    fun ready(key: String): Boolean = spiders.containsKey(key)

    fun loadedKeys(): List<String> = spiders.keys.toList()

    fun unload(key: String) {
        spiders.remove(key)
    }

    fun unloadAll() {
        spiders.clear()
    }

    fun call(key: String, method: String, vararg args: Any?): String {
        val obj = spiders[key] ?: run {
            Log.w(TAG, "call $method: spider not loaded for $key")
            return ""
        }
        return try {
            val app = Python.getInstance().getModule("app")
            val fullArgs = arrayOfNulls<Any>(args.size + 1)
            fullArgs[0] = obj
            args.forEachIndexed { i, a -> fullArgs[i + 1] = a ?: "" }
            app.callAttr(method, *fullArgs)?.toString().orEmpty()
        } catch (t: Throwable) {
            Log.e(TAG, "call $method failed key=$key", t)
            lastError = t.message ?: "call failed"
            ""
        }
    }
}
