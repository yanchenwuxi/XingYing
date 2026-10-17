package com.xing.tv.spider.js;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * 极简 HTTP 客户端：只用 JDK 自带能力，不引任何三方库。
 * 支持 GET/POST、gzip、超时、自定义头、编码自动识别（UTF-8 / GBK）。
 */
public final class JsHttp {

    public static final String UA =
            "Mozilla/5.0 (Linux; Android 12; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/108.0 Mobile Safari/537.36";

    private JsHttp() {
    }

    public static String get(String url) throws Exception {
        return get(url, null, 15000);
    }

    public static String get(String url, Map<String, String> headers, int timeout) throws Exception {
        byte[] data = getBytes(url, headers, timeout);
        return decode(data);
    }

    public static String post(String url, String body, Map<String, String> headers) throws Exception {
        byte[] data = request(url, "POST", body == null ? null : body.getBytes("UTF-8"), headers, 15000);
        return decode(data);
    }

    public static byte[] getBytes(String url, Map<String, String> headers, int timeout) throws Exception {
        return request(url, "GET", null, headers, timeout);
    }

    public static byte[] request(String url, String method, byte[] body,
                                 Map<String, String> headers, int timeout) throws Exception {
        HttpURLConnection conn = null;
        try {
            URL u = toUrl(url);
            conn = (HttpURLConnection) u.openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(timeout);
            conn.setReadTimeout(timeout);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Accept", "*/*");
            conn.setRequestProperty("Accept-Encoding", "gzip");
            conn.setRequestProperty("Connection", "keep-alive");
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    conn.setRequestProperty(e.getKey(), e.getValue());
                }
            }
            if (body != null) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                OutputStream os = conn.getOutputStream();
                os.write(body);
                os.flush();
                os.close();
            }
            int code = conn.getResponseCode();
            if (code < 200 || code >= 400) {
                throw new RuntimeException("HTTP " + code);
            }
            InputStream is = conn.getInputStream();
            String enc = conn.getContentEncoding();
            if (enc != null && enc.toLowerCase().contains("gzip")) {
                is = new GZIPInputStream(is);
            } else if (enc != null && enc.toLowerCase().contains("deflate")) {
                is = new java.util.zip.InflaterInputStream(is);
            }
            return readAll(is);
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignore) {
                }
            }
        }
    }

    private static byte[] readAll(InputStream is) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        is.close();
        return bos.toByteArray();
    }

    /** 先按 UTF-8 严格解，失败退 GBK —— 采集接口这两种最多。 */
    /**
     * 构造 URL。中文域名（http://我不是.摸鱼儿.cc 这类）不能直接 new URL，
     * 要先转成 punycode；顺手把路径里的非 ASCII 字符编码掉。
     */
    public static URL toUrl(String url) throws Exception {
        try {
            return new URL(url);
        } catch (Throwable ignore) {
            // 落到下面手工处理
        }
        String raw = url == null ? "" : url.trim();
        int scheme = raw.indexOf("://");
        if (scheme < 0) throw new Exception("bad url");
        String prefix = raw.substring(0, scheme + 3);
        String rest = raw.substring(scheme + 3);
        int slash = rest.indexOf('/');
        String hostPort = slash >= 0 ? rest.substring(0, slash) : rest;
        String path = slash >= 0 ? rest.substring(slash) : "";
        String port = "";
        int colon = hostPort.lastIndexOf(':');
        if (colon > 0) {
            port = hostPort.substring(colon);
            hostPort = hostPort.substring(0, colon);
        }
        String ascii = java.net.IDN.toASCII(hostPort);
        return new URL(prefix + ascii + port + encodePath(path));
    }

    /** 路径里的中文/空格编码；已编码的百分号保持原样。 */
    private static String encodePath(String path) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c < 128) {
                sb.append(c);
            } else {
                try {
                    for (byte b : String.valueOf(c).getBytes("UTF-8")) {
                        sb.append('%').append(String.format("%02X", b));
                    }
                } catch (Throwable t) {
                    sb.append(c);
                }
            }
        }
        return sb.toString();
    }

    public static String decode(byte[] data) {
        if (data == null) return "";
        try {
            java.nio.charset.CharsetDecoder dec = java.nio.charset.Charset.forName("UTF-8")
                    .newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
            return dec.decode(java.nio.ByteBuffer.wrap(data)).toString();
        } catch (Throwable ignore) {
        }
        try {
            return new String(data, "GBK");
        } catch (Throwable ignore) {
        }
        return new String(data);
    }

    public static Map<String, String> headers(String... kv) {
        Map<String, String> m = new HashMap<String, String>();
        if (kv != null) {
            for (int i = 0; i + 1 < kv.length; i += 2) {
                m.put(kv[i], kv[i + 1]);
            }
        }
        return m;
    }
}
