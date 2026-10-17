package com.xing.tv.spider.js;

import android.content.Context;
import android.util.Log;

import app.cash.quickjs.QuickJs;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * JS 源运行时。
 *
 * 一个源 = 一个实例 = 一条 JS 线程（引擎不是线程安全的，绝不能并发求值）。
 * 宿主能力通过引擎的对象绑定直接挂成全局的 __host：跨语言只走字符串，
 * JS 那边负责 JSON.parse / JSON.stringify。
 *
 * 三件必须处理的事：
 *   1. 模块化语法（import/export）做预处理，引擎只认脚本语义；
 *   2. async/await 去糖（引擎封装不驱动微任务队列，Promise 永远不 resolve）；
 *   3. 宿主函数在 JS 线程内被同步调用，异步请求回到 JS 线程再触发回调。
 */
public class JsRuntime {

    private static final String TAG = "starfilm-js";
    private static final String HOST_NAME = "__host";

    private final Context ctx;
    private final String siteKey;
    private final String threadName;
    private final ExecutorService worker;

    private QuickJs quickJs;
    private JsHostImpl host;
    private volatile boolean closed = false;
    /** 最近一次 JS 异常，界面用它说明「这个源为什么没数据」。 */
    public volatile String lastError = "";

    public JsRuntime(Context ctx, String siteKey) {
        this.ctx = ctx.getApplicationContext();
        this.siteKey = siteKey == null || siteKey.length() == 0 ? "js" : siteKey;
        this.threadName = "js-" + this.siteKey;
        this.worker = Executors.newSingleThreadExecutor(new java.util.concurrent.ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, threadName);
                t.setDaemon(true);
                return t;
            }
        });
    }

    // ---------------------------------------------------------------- 生命周期

    /** 起引擎、绑宿主、注入桥接脚本、加载预置库。 */
    public void prepare() {
        onJs(new Task() {
            @Override
            public Object run() {
                QuickJs q = QuickJs.create();
                quickJs = q;
                host = new JsHostImpl(ctx, siteKey, new JsHostImpl.Listener() {
                    @Override
                    public void onHttp(String callbackId, String resultJson) {
                        final String id = callbackId;
                        final String json = resultJson;
                        post(new Runnable() {
                            @Override
                            public void run() {
                                evaluate("globalThis.__fireHttp(" + quote(id) + ", " + quote(json) + ");");
                            }
                        });
                    }

                    @Override
                    public void fireTimer(final String callbackId) {
                        post(new Runnable() {
                            @Override
                            public void run() {
                                evaluate("globalThis.__fireTimer(" + quote(callbackId) + ");");
                            }
                        });
                    }
                });
                q.set(HOST_NAME, JsHost.class, host);
                evaluate(SHIM);
                evaluate(INVOKE);
                return null;
            }
        });
        loadLibs();
    }

    /** 把一份 JS 源装进来，返回识别到的形态（evalReturn / default / drpy / globals / none）。 */
    public String loadSpider(String source) {
        if (source == null || source.length() == 0) return "none";
        String code = source;

        boolean module = JsModule.isModule(code);
        boolean async = JsAsync.needed(code);
        if (module) code = JsModule.preprocess(code);
        if (async) {
            // 去糖前先把 Promise 的静态方法同步化：去糖后残留的 Promise.all 会拿到真 Promise 对象，
            // 后面按数组用就崩；而这些位置上的值本来就已经是最终值（宿主全同步）。
            evaluate(PROMISE_SYNC);
            code = JsAsync.deasync(code);
        }

        final String script = code;
        Object r = onJs(new Task() {
            @Override
            public Object run() {
                evaluate(script);
                return evaluate(BOOTSTRAP);
            }
        });
        return r == null ? "none" : String.valueOf(r);
    }

    /** 调源里的方法，返回 JSON 文本；失败或未实现返回空串。 */
    public String invoke(String name, Object... args) {
        StringBuilder sb = new StringBuilder();
        sb.append("globalThis.__invoke(").append(quote(name)).append(", [");
        for (int i = 0; i < args.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(literal(args[i]));
        }
        sb.append("]);");
        final String script = sb.toString();
        Object r = onJs(new Task() {
            @Override
            public Object run() {
                return evaluate(script);
            }
        });
        if (r == null) return "";
        return r instanceof String ? (String) r : String.valueOf(r);
    }

    public void close() {
        if (closed) return;
        closed = true;
        try {
            onJs(new Task() {
                @Override
                public Object run() {
                    if (quickJs != null) quickJs.close();
                    quickJs = null;
                    return null;
                }
            });
        } catch (Throwable ignore) {
        }
        worker.shutdownNow();
    }

    // ---------------------------------------------------------------- 线程模型

    private interface Task {
        Object run();
    }

    /** 在 JS 线程上执行；已经在 JS 线程内就直接跑，避免自锁。 */
    private Object onJs(Task t) {
        if (Thread.currentThread().getName().equals(threadName)) {
            return t.run();
        }
        try {
            Future<Object> f = worker.submit(new java.util.concurrent.Callable<Object>() {
                @Override
                public Object call() {
                    return t.run();
                }
            });
            return f.get();
        } catch (Throwable e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            lastError = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
            Log.d(TAG, "js call failed: " + lastError);
            return null;
        }
    }

    private void post(Runnable r) {
        if (closed) return;
        try {
            worker.execute(r);
        } catch (Throwable ignore) {
        }
    }

    private Object evaluate(String script) {
        try {
            if (quickJs == null) return null;
            return quickJs.evaluate(script);
        } catch (Throwable t) {
            lastError = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            Log.d(TAG, "js error: " + lastError);
            return null;
        }
    }

    // ---------------------------------------------------------------- 预置库

    /**
     * 库作用域隔离之后，仍要把生态约定的一批全局名补出来。
     *
     * 这些名字不是模块导出，而是同行都默认「引擎会给」的：drpy 的 $js（在 js 里
     * 执行规则字符串）、以及一批 UA / 宿主常量。库被包进函数作用域后它们就取不到了，
     * 所以在库代码末尾把它们原样回挂到 globalThis。
     */
    private static final String LIB_EXPORTS =
            "\n;try{if(typeof $js!=='undefined'&&typeof globalThis.$js==='undefined')globalThis.$js=$js;}catch(e){}\n"
            + ";try{if(typeof MOBILE_UA!=='undefined'&&typeof globalThis.MOBILE_UA==='undefined')globalThis.MOBILE_UA=MOBILE_UA;}catch(e){}\n"
            + ";try{if(typeof PC_UA!=='undefined'&&typeof globalThis.PC_UA==='undefined')globalThis.PC_UA=PC_UA;}catch(e){}\n"
            + ";try{if(typeof UC_UA!=='undefined'&&typeof globalThis.UC_UA==='undefined')globalThis.UC_UA=UC_UA;}catch(e){}\n"
            + ";try{if(typeof HOST!=='undefined'&&typeof globalThis.HOST==='undefined')globalThis.HOST=HOST;}catch(e){}\n"
            + ";try{if(typeof rule!=='undefined'&&typeof globalThis.rule==='undefined')globalThis.rule=rule;}catch(e){}\n";

    /** 生态常用库。顺序即依赖顺序：底层工具在前，drpy 引擎最后。 */
    private static final String[] LIBS = {
            "js/lib/utils.js",
            "js/lib/http.js",
            "js/lib/net.js",
            "js/lib/crypto-js.js",
            "js/lib/json5.js",
            "js/lib/pako.min.js",
            "js/lib/dayjs.min.js",
            "js/lib/uri.min.js",
            "js/lib/gbk.js",
            "js/lib/jsencrypt.js",
            "js/lib/node-rsa.js",
            "js/lib/underscore-esm-min.js",
            "js/lib/cheerio.min.js",
            "js/lib/drpy-core-lite.min.js",
            "js/lib/sortName.js",
            "js/lib/similarity.js",
            "js/lib/jinja.js",
            "js/lib/mod.js",
            "js/lib/moban.js",
            "js/lib/live2cms.js",
            "js/lib/drpy.min.js",
            "js/lib/drpy2.min.js",
    };

    private void loadLibs() {
        for (final String path : LIBS) {
            // 库名 = 文件名去掉目录与扩展名（cheerio.min.js → cheerio）。
            // 脚本里的 `import cheerio from "…/cheerio.min.js"` 会改写成对这个
            // 全局名的绑定，所以每个库加载完必须按这个名字挂上去。
            final String base = JsModule.baseOf(path);
            onJs(new Task() {
                @Override
                public Object run() {
                    try {
                        String code = readAsset(path);
                        if (code.length() == 0) return null;
                        if (JsModule.isModule(code)) code = JsModule.preprocess(code);
                        // 清掉上一轮可能残留的默认导出，避免张冠李戴
                        evaluate("globalThis.__ESM_DEFAULT__ = undefined;"
                                + "try { delete globalThis['default']; } catch (e) { globalThis['default'] = undefined; }");
                        // 每个库跑在自己的函数作用域里：库之间重名的顶层声明
                        // （underscore 的 _、crypto-js 的 VERSION 之类）在同一个
                        // 全局作用域下会直接撞成 "already declared"。包一层之后
                        // 互不干扰，对外只留预处理改写出去的那批 globalThis 挂载。
                        evaluate("(function () {\n" + code + "\n" + LIB_EXPORTS + "})();");
                        // 默认导出（含 export{... as default} 这种写法）挂成库名
                        evaluate("(function () {"
                                + "var d = (typeof globalThis.__ESM_DEFAULT__ !== 'undefined'"
                                + " && globalThis.__ESM_DEFAULT__ !== null)"
                                + " ? globalThis.__ESM_DEFAULT__ : globalThis['default'];"
                                + "if (d === undefined || d === null) return;"
                                + "globalThis[" + quote(base) + "] = d;"
                                // drpy 引擎（init/home/category…）单独认出来，规则型源要用
                                + "if (typeof d.init === 'function' || typeof d.home === 'function') {"
                                + "  globalThis.__DRPY__ = d;"
                                + "}"
                                + "})();");
                    } catch (Throwable t) {
                        Log.d(TAG, "lib failed: " + path + " / " + t.getMessage());
                    }
                    return null;
                }
            });
        }
        onJs(new Task() {
            @Override
            public Object run() {
                evaluate("globalThis.__ESM_DEFAULT__ = undefined;");
                return null;
            }
        });
    }

    private String readAsset(String path) {
        InputStream in = null;
        try {
            in = ctx.getAssets().open(path);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return "";
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignore) {
                }
            }
        }
    }

    // ---------------------------------------------------------------- 字面量

    static String quote(String text) {
        if (text == null) return "\"\"";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\u2028': sb.append("\\u2028"); break;
                case '\u2029': sb.append("\\u2029"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    /** Java 值 → JS 字面量。 */
    static String literal(Object v) {
        if (v == null) return "null";
        if (v instanceof String) return quote((String) v);
        if (v instanceof Boolean) return ((Boolean) v) ? "true" : "false";
        if (v instanceof Number) return String.valueOf(v);
        // 集合必须按 JSON 字面量传：TVBox 的 detailContent(ids) 收数组、
        // categoryContent 的 extend 收对象。若退化成字符串，脚本里 ids[0]
        // 拿到的是 '['，extend.area 直接 undefined。
        if (v instanceof Map) return toJson(v);
        if (v instanceof List) return toJson(v);
        return quote(String.valueOf(v));
    }

    private static String toJson(Object v) {
        try {
            if (v instanceof Map) {
                JSONObject o = new JSONObject();
                for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                    o.put(String.valueOf(e.getKey()), e.getValue());
                }
                return o.toString();
            }
            if (v instanceof List) {
                JSONArray a = new JSONArray();
                for (Object o : (List<?>) v) a.put(o);
                return a.toString();
            }
        } catch (Throwable ignore) {
        }
        return String.valueOf(v);
    }

    // ---------------------------------------------------------------- 桥接脚本

    /** TVBox 约定的宿主 API，JS 侧只做 JSON 编解码。 */
    private static final String SHIM =
            "(function () {\n" +
            "  var cbs = {}, timers = {}, seq = 0;\n" +
            "  function nextId(p) { seq++; return p + seq; }\n" +
            "  function safeParse(s, def) { try { return JSON.parse(s); } catch (e) { return def; } }\n" +
            "  globalThis.__fireHttp = function (id, json) {\n" +
            "    var cb = cbs[id]; if (!cb) return; delete cbs[id];\n" +
            "    try { cb(safeParse(json, { code: 0, content: '', headers: {} })); } catch (e) {}\n" +
            "  };\n" +
            "  globalThis.__fireTimer = function (id) {\n" +
            "    var cb = timers[id]; if (!cb) return; delete timers[id];\n" +
            "    try { cb(); } catch (e) {}\n" +
            "  };\n" +
            "  function syncReq(url, options) {\n" +
            "    var res = __host.req(String(url), JSON.stringify(options || {}));\n" +
            "    return safeParse(res, { code: 0, content: '', headers: {} });\n" +
            "  }\n" +
            "  globalThis._http = function (url, options) {\n" +
            "    options = options || {};\n" +
            "    if (typeof options.complete === 'function') {\n" +
            "      var id = nextId('h'); cbs[id] = options.complete;\n" +
            "      var o = {}; for (var k in options) { if (k !== 'complete') o[k] = options[k]; }\n" +
            "      __host.http(String(url), JSON.stringify(o), id);\n" +
            "      return null;\n" +
            "    }\n" +
            "    return syncReq(url, options);\n" +
            "  };\n" +
            "  if (typeof globalThis.req === 'undefined') globalThis.req = function (url, options) { return syncReq(url, options); };\n" +
            "  if (typeof globalThis.http === 'undefined') globalThis.http = globalThis._http;\n" +
            "  if (typeof globalThis.console === 'undefined') {\n" +
            "    function emit(a) {\n" +
            "      var parts = [];\n" +
            "      for (var i = 0; i < a.length; i++) {\n" +
            "        var v = a[i];\n" +
            "        try { parts.push(typeof v === 'string' ? v : JSON.stringify(v)); } catch (e) { parts.push(String(v)); }\n" +
            "      }\n" +
            "      return parts.join(' ');\n" +
            "    }\n" +
            "    globalThis.console = {\n" +
            "      log: function () { __host.log(emit(arguments)); },\n" +
            "      info: function () { __host.log(emit(arguments)); },\n" +
            "      warn: function () { __host.log(emit(arguments)); },\n" +
            "      error: function () { __host.log(emit(arguments)); },\n" +
            "      debug: function () { __host.log(emit(arguments)); }\n" +
            "    };\n" +
            "  }\n" +
            "  if (typeof globalThis.print === 'undefined') globalThis.print = function (x) { __host.log(String(x)); };\n" +
            "  if (typeof globalThis.request === 'undefined') {\n" +
            "    globalThis.request = function (url, options) {\n" +
            "      var r = syncReq(url, options);\n" +
            "      return r && r.content !== undefined ? r.content : '';\n" +
            "    };\n" +
            "  }\n" +
            "  if (typeof globalThis.post === 'undefined') {\n" +
            "    globalThis.post = function (url, data, options) {\n" +
            "      options = options || {};\n" +
            "      options.method = 'POST';\n" +
            "      options.body = (typeof data === 'string') ? data : JSON.stringify(data);\n" +
            "      return globalThis.request(url, options);\n" +
            "    };\n" +
            "  }\n" +
            "  if (typeof globalThis.fetch === 'undefined') {\n" +
            "    globalThis.fetch = function (url, options) {\n" +
            "      var r = syncReq(url, options);\n" +
            "      return {\n" +
            "        ok: r.code >= 200 && r.code < 300,\n" +
            "        status: r.code,\n" +
            "        headers: r.headers || {},\n" +
            "        text: function () { return r.content || ''; },\n" +
            "        json: function () { return JSON.parse(r.content || 'null'); }\n" +
            "      };\n" +
            "    };\n" +
            "  }\n" +
            "  if (typeof globalThis.toast === 'undefined') globalThis.toast = function (x) { __host.toast(String(x)); };\n" +
            "  if (typeof globalThis.open === 'undefined') globalThis.open = function () { return null; };\n" +
            "  if (typeof globalThis.copy === 'undefined') globalThis.copy = function (t) { __host.log(String(t)); return true; };\n" +
            "  globalThis.pdfh = function (html, rule) { return __host.pdfh(String(html), String(rule)); };\n" +
            "  globalThis.pdfa = function (html, rule) { return safeParse(__host.pdfa(String(html), String(rule)), []); };\n" +
            "  globalThis.pd = function (html, rule, base) { return __host.pd(String(html), String(rule), base == null ? '' : String(base)); };\n" +
            "  globalThis.pdfl = function (html, rule, list, base) { return safeParse(__host.pdfl(String(html), String(rule), String(list), base == null ? '' : String(base)), []); };\n" +
            "  globalThis.joinUrl = function (base, rel) { return __host.joinUrl(String(base), rel == null ? '' : String(rel)); };\n" +
            "  globalThis.md5 = function (x) { return __host.md5(String(x)); };\n" +
            "  globalThis.base64Encode = function (x) { return __host.base64(String(x), '1'); };\n" +
            "  globalThis.base64Decode = function (x) { return __host.base64(String(x), '0'); };\n" +
            "  globalThis.s2t = function (x) { return __host.s2t(String(x)); };\n" +
            "  globalThis.t2s = function (x) { return __host.t2s(String(x)); };\n" +
            "  globalThis.gbkDecode = function (arr) { return __host.gbkDecode(JSON.stringify(arr)); };\n" +
            "  if (typeof globalThis.local === 'undefined') {\n" +
            "    globalThis.local = {\n" +
            "      get: function (k, d) { var v = __host.localGet(String(k)); return (v === '' && d !== undefined) ? d : v; },\n" +
            "      set: function (k, v) { __host.localSet(String(k), String(v)); },\n" +
            "      'delete': function (k) { __host.localDel(String(k)); }\n" +
            "    };\n" +
            "  }\n" +
            "  globalThis.getProxy = function (vip) { return __host.getProxy(vip ? '1' : '0'); };\n" +
            "  globalThis.js2Proxy = function (vip, type, key, header, url) {\n" +
            "    return __host.js2Proxy(vip ? '1' : '0', String(type == null ? 0 : type), String(key == null ? '' : key), JSON.stringify(header || {}), String(url == null ? '' : url));\n" +
            "  };\n" +
            "  globalThis.setTimeout = function (fn, ms) { var id = nextId('t'); timers[id] = fn; __host.setTimeout(id, String(ms || 0)); return id; };\n" +
            "  globalThis.clearTimeout = function (id) { delete timers[id]; __host.clearTimeout(String(id)); };\n" +
            "  globalThis.getAppVersion = function () { return __host.getAppVersion(); };\n" +
            "  globalThis.isVideoFormat = function (u) { return __host.isVideoFormat(String(u)) === '1'; };\n" +
            "  globalThis.getCtx = function () { return {}; };\n" +
            "})();";

    /** 去糖模式下的 Promise 补丁。 */
    private static final String PROMISE_SYNC =
            "(function () {\n" +
            "  var P = globalThis.Promise;\n" +
            "  if (!P || P.__syncPatched) return;\n" +
            "  P.__syncPatched = true;\n" +
            "  P.all = function (list) { return Array.prototype.slice.call(list); };\n" +
            "  P.allSettled = function (list) {\n" +
            "    return Array.prototype.slice.call(list).map(function (v) { return { status: 'fulfilled', value: v }; });\n" +
            "  };\n" +
            "  P.race = function (list) { var a = Array.prototype.slice.call(list); return a.length ? a[0] : undefined; };\n" +
            "  P.resolve = function (v) { return v; };\n" +
            "  P.reject = function (e) { throw e; };\n" +
            "})();";

    /** 建立 __JS_SPIDER__：模块型 / 规则型 / 全局函数型都认。 */
    private static final String BOOTSTRAP =
            "(function () {\n" +
            "  try {\n" +
            "    if (typeof __jsEvalReturn === 'function') {\n" +
            "      var a = __jsEvalReturn();\n" +
            "      if (a) { globalThis.__JS_SPIDER__ = a; return 'evalReturn'; }\n" +
            "    }\n" +
            "    var d = globalThis.__ESM_DEFAULT__;\n" +
            "    if (d !== undefined && d !== null) {\n" +
            "      globalThis.__JS_SPIDER__ = (typeof d === 'function') ? d() : d;\n" +
            "      if (globalThis.__JS_SPIDER__) return 'default';\n" +
            "    }\n" +
            "    if (typeof globalThis.rule === 'object' && globalThis.rule !== null && globalThis.__DRPY__) {\n" +
            "      try { globalThis.__DRPY__.init(globalThis.rule); } catch (e) {}\n" +
            "      var D = globalThis.__DRPY__;\n" +
            "      globalThis.__JS_SPIDER__ = {\n" +
            "        home: function (f) { return D.home(f); },\n" +
            "        homeVod: function () { return D.homeVod(); },\n" +
            "        category: function (t, p, fl, e) { return D.category(t, p, fl, e); },\n" +
            "        detail: function (ids) { return D.detail(ids); },\n" +
            "        search: function (k, q) { return D.search(k, q); },\n" +
            "        play: function (f, i, e) { return D.play(f, i, e); },\n" +
            "        proxy: function (p) { return D.proxy(p); }\n" +
            "      };\n" +
            "      return 'drpy';\n" +
            "    }\n" +
            "    var g = globalThis;\n" +
            "    if (typeof g.__JS_SPIDER__ === 'object' && g.__JS_SPIDER__ !== null) return 'exists';\n" +
            "    var fallback = {};\n" +
            "    var KEYS = ['homeContent','homeVideoContent','categoryContent','detailContent','searchContent','playerContent','init','action','liveContent'];\n" +
            "    for (var i = 0; i < KEYS.length; i++) { if (typeof g[KEYS[i]] === 'function') fallback[KEYS[i]] = g[KEYS[i]]; }\n" +
            "    var SHORT = ['home','homeVod','category','detail','search','play','proxy'];\n" +
            "    for (var j = 0; j < SHORT.length; j++) { if (typeof g[SHORT[j]] === 'function') fallback[SHORT[j]] = g[SHORT[j]]; }\n" +
            "    if (typeof g.rule === 'object' && g.rule !== null) { fallback.__rule = g.rule; }\n" +
            "    globalThis.__JS_SPIDER__ = fallback;\n" +
            "    return 'globals';\n" +
            "  } catch (e) {\n" +
            "    globalThis.__LAST_ERROR = String(e);\n" +
            "    return 'none';\n" +
            "  }\n" +
            "})();";

    /** 方法分发：TVBox 名与 js 名互相兜底。 */
    private static final String INVOKE =
            "globalThis.__invoke = function (name, args) {\n" +
            "  var ALIAS = {\n" +
            "    homeContent: ['homeContent', 'home'],\n" +
            "    homeVideoContent: ['homeVideoContent', 'homeVod'],\n" +
            "    categoryContent: ['categoryContent', 'category'],\n" +
            "    detailContent: ['detailContent', 'detail'],\n" +
            "    searchContent: ['searchContent', 'search'],\n" +
            "    playerContent: ['playerContent', 'play'],\n" +
            "    action: ['action'],\n" +
            "    liveContent: ['liveContent'],\n" +
            "    init: ['init']\n" +
            "  };\n" +
            "  try {\n" +
            "    var s = globalThis.__JS_SPIDER__;\n" +
            "    if (!s) return '';\n" +
            "    var names = ALIAS[name] || [name];\n" +
            "    var fn = null;\n" +
            "    for (var i = 0; i < names.length; i++) {\n" +
            "      if (typeof s[names[i]] === 'function') { fn = s[names[i]]; break; }\n" +
            "    }\n" +
            "    if (!fn) return '';\n" +
            "    var r = fn.apply(s, args || []);\n" +
            "    if (r === undefined || r === null) return '';\n" +
            "    if (typeof r === 'string') return r;\n" +
            "    try { return JSON.stringify(r); } catch (e) { return ''; }\n" +
            "  } catch (e) {\n" +
            "    globalThis.__LAST_ERROR = String(e);\n" +
            "    return '';\n" +
            "  }\n" +
            "};";
}
