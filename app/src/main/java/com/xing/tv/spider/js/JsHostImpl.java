package com.xing.tv.spider.js;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

/**
 * JS 爬虫宿主能力的实现：把脚本需要的网络、选择器、编解码、存储、定时器
 * 逐一落到本机能力上。
 *
 * 全部方法只收发字符串，JS 侧做 JSON 编解码 —— 跨语言只有一种类型，最稳。
 */
public class JsHostImpl implements JsHost {

    private final Context ctx;
    private final String siteKey;
    private final SharedPreferences sp;
    /** 异步请求完成后回调到 JS 线程。 */
    private final Listener listener;

    private final ScheduledExecutorService timers =
            Executors.newScheduledThreadPool(2, new java.util.concurrent.ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "js-timer");
                    t.setDaemon(true);
                    return t;
                }
            });

    /** 定时器句柄，clearTimeout 用它取消。 */
    private final Map<String, ScheduledFuture<?>> pending = new HashMap<String, ScheduledFuture<?>>();

    public interface Listener {
        void onHttp(String callbackId, String resultJson);

        void fireTimer(String callbackId);
    }

    public JsHostImpl(Context ctx, String siteKey, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.siteKey = siteKey == null ? "default" : siteKey;
        this.listener = listener;
        this.sp = this.ctx.getSharedPreferences("starfilm_js_" + this.siteKey, Context.MODE_PRIVATE);
    }

    // ---------------------------------------------------------------- 网络

    @Override
    public String req(String url, String optionsJson) {
        try {
            JSONObject o = parseOptions(optionsJson);
            String method = o.optString("method", "GET").toUpperCase();
            String body = o.optString("body", "");
            if (body.length() == 0 && o.has("data")) {
                Object d = o.opt("data");
                body = d instanceof String ? (String) d : String.valueOf(d);
            }
            int timeout = o.optInt("timeout", 15000);
            Map<String, String> headers = toStringMap(o.optJSONObject("headers"));
            // 表单提交：data 是对象且没显式声明 content-type 时按表单编码
            if ("POST".equals(method) && body.length() == 0 && o.optJSONObject("data") != null) {
                body = formEncode(o.optJSONObject("data"));
                if (!headers.containsKey("Content-Type")) {
                    headers.put("Content-Type", "application/x-www-form-urlencoded");
                }
            }
            if ("POST".equals(method) && !headers.containsKey("Content-Type")
                    && body.length() > 0 && body.charAt(0) == '{') {
                headers.put("Content-Type", "application/json");
            }
            return doRequest(url, method, headers, body, timeout);
        } catch (Throwable t) {
            return errResult();
        }
    }

    @Override
    public String http(String url, String optionsJson, String callbackId) {
        final String u = url;
        final String opt = optionsJson;
        final String id = callbackId;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                String r = req(u, opt);
                if (listener != null) listener.onHttp(id, r);
            }
        }, "js-http");
        t.setDaemon(true);
        t.start();
        return "";
    }

    /** 发请求，返回 {code, headers, content} 的 JSON 文本。 */
    private String doRequest(String url, String method, Map<String, String> headers,
                             String body, int timeout) {
        HttpURLConnection conn = null;
        try {
            URL u = JsHttp.toUrl(url);
            conn = (HttpURLConnection) u.openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(timeout);
            conn.setReadTimeout(timeout);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", JsHttp.UA);
            conn.setRequestProperty("Accept", "*/*");
            conn.setRequestProperty("Accept-Encoding", "gzip");
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    conn.setRequestProperty(e.getKey(), e.getValue());
                }
            }
            if (body != null && body.length() > 0 && !"GET".equals(method)) {
                conn.setDoOutput(true);
                conn.getOutputStream().write(body.getBytes("UTF-8"));
            }
            int code = conn.getResponseCode();
            InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            byte[] data = in == null ? new byte[0] : readAll(in, "gzip".equalsIgnoreCase(conn.getContentEncoding()));
            String content = JsHttp.decode(data);

            JSONObject out = new JSONObject();
            out.put("code", code);
            JSONObject hs = new JSONObject();
            for (Map.Entry<String, java.util.List<String>> e : conn.getHeaderFields().entrySet()) {
                if (e.getKey() == null || e.getValue() == null || e.getValue().isEmpty()) continue;
                hs.put(e.getKey(), e.getValue().get(0));
            }
            out.put("headers", hs);
            out.put("content", content);
            return out.toString();
        } catch (Throwable t) {
            return errResult();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static byte[] readAll(InputStream in, boolean gzip) throws Exception {
        InputStream src = gzip ? new GZIPInputStream(in) : in;
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = src.read(buf)) > 0) bos.write(buf, 0, n);
        try {
            src.close();
        } catch (Throwable ignore) {
        }
        return bos.toByteArray();
    }

    private static String errResult() {
        return "{\"code\":0,\"headers\":{},\"content\":\"\"}";
    }

    private static JSONObject parseOptions(String json) {
        if (json == null || json.length() == 0) return new JSONObject();
        JSONObject o = LenientJson.objOrNull(json);
        return o == null ? new JSONObject() : o;
    }

    private static Map<String, String> toStringMap(JSONObject o) {
        Map<String, String> m = new HashMap<String, String>();
        if (o == null) return m;
        Iterator<String> it = o.keys();
        while (it.hasNext()) {
            String k = it.next();
            m.put(k, o.optString(k, ""));
        }
        return m;
    }

    private static String formEncode(JSONObject o) throws Exception {
        StringBuilder sb = new StringBuilder();
        Iterator<String> it = o.keys();
        while (it.hasNext()) {
            String k = it.next();
            if (sb.length() > 0) sb.append('&');
            sb.append(java.net.URLEncoder.encode(k, "UTF-8"))
                    .append('=')
                    .append(java.net.URLEncoder.encode(o.optString(k, ""), "UTF-8"));
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- 选择器

    @Override
    public String pdfh(String html, String rule) {
        return HtmlRules.pdfh(html, rule);
    }

    @Override
    public String pdfa(String html, String rule) {
        return HtmlRules.pdfa(html, rule);
    }

    @Override
    public String pd(String html, String rule, String base) {
        return HtmlRules.pd(html, rule, base);
    }

    @Override
    public String pdfl(String html, String rule, String listRule, String base) {
        return HtmlRules.pdfl(html, rule, listRule, base);
    }

    @Override
    public String joinUrl(String base, String rel) {
        return HtmlRules.join(base, rel);
    }

    // ---------------------------------------------------------------- 编解码

    @Override
    public String md5(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(text.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    @Override
    public String base64(String text, String encode) {
        try {
            if ("1".equals(encode)) {
                return Base64.encodeToString(text.getBytes("UTF-8"), Base64.NO_WRAP);
            }
            byte[] raw = Base64.decode(text, Base64.DEFAULT);
            return new String(raw, "UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }

    @Override
    public String s2t(String text) {
        // 简繁转换：脚本里主要用于搜索词兼容，本机不做字形改写，原样返回
        return text == null ? "" : text;
    }

    @Override
    public String t2s(String text) {
        return text == null ? "" : text;
    }

    @Override
    public String gbkDecode(String jsonArray) {
        try {
            JSONArray arr = new JSONArray(jsonArray);
            byte[] raw = new byte[arr.length()];
            for (int i = 0; i < arr.length(); i++) {
                raw[i] = (byte) arr.optInt(i, 0);
            }
            return new String(raw, "GBK");
        } catch (Throwable t) {
            return "";
        }
    }

    // ---------------------------------------------------------------- 存储

    @Override
    public String localGet(String key) {
        return sp.getString(key, "");
    }

    @Override
    public String localSet(String key, String value) {
        sp.edit().putString(key, value).apply();
        return "";
    }

    @Override
    public String localDel(String key) {
        sp.edit().remove(key).apply();
        return "";
    }

    // ---------------------------------------------------------------- 代理

    @Override
    public String getProxy(String vip) {
        return LocalProxy.base();
    }

    @Override
    public String js2Proxy(String vip, String siteType, String siteKey, String headerJson, String url) {
        return LocalProxy.js2Proxy(vip, siteType, siteKey, headerJson, url);
    }

    // ---------------------------------------------------------------- 定时器

    @Override
    public String setTimeout(final String callbackId, String ms) {
        int delay = 0;
        try {
            delay = (int) Double.parseDouble(ms);
        } catch (Throwable ignore) {
        }
        final String id = callbackId;
        ScheduledFuture<?> f = timers.schedule(new Runnable() {
            @Override
            public void run() {
                pending.remove(id);
                if (listener != null) listener.fireTimer(id);
            }
        }, delay, TimeUnit.MILLISECONDS);
        pending.put(id, f);
        return "";
    }

    @Override
    public String clearTimeout(String callbackId) {
        ScheduledFuture<?> f = pending.remove(callbackId);
        if (f != null) f.cancel(false);
        return "";
    }

    // ---------------------------------------------------------------- 杂项

    @Override
    public String log(String text) {
        android.util.Log.d("starfilm-js", text == null ? "" : text);
        return "";
    }

    @Override
    public String toast(String text) {
        return "";
    }

    @Override
    public String getAppVersion() {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "1.0.0";
        }
    }

    @Override
    public String isVideoFormat(String url) {        if (url == null) return "0";
        String u = url.toLowerCase();
        int q = u.indexOf('?');
        if (q > 0) u = u.substring(0, q);
        String[] exts = {".m3u8", ".mp4", ".mkv", ".avi", ".flv", ".ts", ".mov", ".wmv", ".rmvb", ".mp3", ".m4a"};
        for (String e : exts) {
            if (u.endsWith(e)) return "1";
        }
        return "0";
    }

    @Override
    public String getCtx() {
        return "{}";
    }
}
