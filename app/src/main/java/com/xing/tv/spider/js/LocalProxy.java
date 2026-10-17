package com.xing.tv.spider.js;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 本机代理。
 *
 * 脚本源的播放地址有两种：直链和「指回本机」的代理地址。后者是脚本自己拼的
 * （getProxy / js2Proxy），形如：
 *
 *   http://127.0.0.1:9978/proxy?do=js&key=<站点>&url=<目标地址>
 *
 * 播放器（系统解码器）只能吃普通 http 地址，所以这里起一个只监听本机回环的
 * 小服务，把它转回爬虫的 proxy()，由脚本现算内容返回。
 *
 * 只绑 127.0.0.1，不对外网开放。
 */
public final class LocalProxy {

    private static final String TAG = "starfilm-proxy";
    private static final int[] CANDIDATE_PORTS = {9978, 9979, 9980, 19978};

    private static volatile boolean started = false;
    private static volatile int port = 9978;
    private static ServerSocket server;
    private static ExecutorService pool;
    private static Handler handler;

    /** 爬虫侧的内容提供者。 */
    public interface Handler {
        Result handle(String kind, Map<String, String> params);
    }

    public static final class Result {
        public String mime = "application/octet-stream";
        public byte[] data;
        public int code = 200;

        public Result(String mime, byte[] data) {
            if (mime != null && mime.length() > 0) this.mime = mime;
            this.data = data;
        }
    }

    private LocalProxy() {
    }

    // ---------------------------------------------------------------- 对外

    public static void setHandler(Handler h) {
        handler = h;
    }

    /** 启动监听；重复调用只生效一次。 */
    public static synchronized void start() {
        if (started) return;
        for (int p : CANDIDATE_PORTS) {
            try {
                ServerSocket s = new ServerSocket(p, 8, InetAddress.getByName("127.0.0.1"));
                server = s;
                port = p;
                break;
            } catch (Throwable ignore) {
                // 端口被占，试下一个
            }
        }
        if (server == null) {
            Log.d(TAG, "no free port for local proxy");
            return;
        }
        started = true;
        pool = Executors.newFixedThreadPool(4, new java.util.concurrent.ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "local-proxy");
                t.setDaemon(true);
                return t;
            }
        });
        Thread accept = new Thread(new Runnable() {
            @Override
            public void run() {
                while (started) {
                    try {
                        final Socket s = server.accept();
                        s.setSoTimeout(20000);
                        pool.execute(new Runnable() {
                            @Override
                            public void run() {
                                serve(s);
                            }
                        });
                    } catch (Throwable t) {
                        if (!started) break;
                    }
                }
            }
        }, "local-proxy-accept");
        accept.setDaemon(true);
        accept.start();
    }

    /** 代理前缀，脚本用它拼播放地址。 */
    public static String base() {
        return "http://127.0.0.1:" + port + "/proxy";
    }

    /** 生成 js 源自己的代理地址。 */
    public static String js2Proxy(String vip, String siteType, String siteKey, String headerJson, String url) {
        try {
            StringBuilder sb = new StringBuilder(base());
            sb.append("?do=js");
            sb.append("&vip=").append(enc(vip));
            sb.append("&type=").append(enc(siteType));
            sb.append("&key=").append(enc(siteKey));
            sb.append("&header=").append(enc(headerJson));
            sb.append("&url=").append(enc(url));
            return sb.toString();
        } catch (Throwable t) {
            return url;
        }
    }

    public static int port() {
        return port;
    }

    // ---------------------------------------------------------------- 服务

    private static void serve(Socket sock) {
        OutputStream out = null;
        try {
            InputStream in = sock.getInputStream();
            out = sock.getOutputStream();
            String requestLine = readLine(in);
            if (requestLine == null || requestLine.length() == 0) {
                return;
            }
            String[] parts = requestLine.split(" ");
            String rawPath = parts.length > 1 ? parts[1] : "/";
            // 把 header 读干净（不需要内容）
            String h;
            while ((h = readLine(in)) != null && h.length() > 0) {
                // 空行结束
            }

            int q = rawPath.indexOf('?');
            String path = q >= 0 ? rawPath.substring(0, q) : rawPath;
            String query = q >= 0 ? rawPath.substring(q + 1) : "";
            Map<String, String> params = parseQuery(query);

            if (!path.endsWith("/proxy")) {
                writeText(out, 404, "text/plain", "not found");
                return;
            }

            String kind = params.get("do");
            if (kind == null || kind.length() == 0) kind = "js";

            Result r = handler == null ? null : handler.handle(kind, params);
            if (r == null || r.data == null) {
                writeText(out, 502, "text/plain", "no content");
                return;
            }
            writeBytes(out, r.code, r.mime, r.data);
        } catch (Throwable t) {
            Log.d(TAG, "serve failed: " + t.getMessage());
        } finally {
            try {
                if (out != null) out.flush();
            } catch (Throwable ignore) {
            }
            try {
                sock.close();
            } catch (Throwable ignore) {
            }
        }
    }

    private static void writeBytes(OutputStream out, int code, String mime, byte[] data) throws Exception {
        StringBuilder head = new StringBuilder();
        head.append("HTTP/1.1 ").append(code).append(" OK\r\n");
        head.append("Content-Type: ").append(mime == null ? "application/octet-stream" : mime).append("\r\n");
        head.append("Content-Length: ").append(data.length).append("\r\n");
        head.append("Accept-Ranges: none\r\n");
        head.append("Access-Control-Allow-Origin: *\r\n");
        head.append("Connection: close\r\n\r\n");
        out.write(head.toString().getBytes("UTF-8"));
        out.write(data);
    }

    private static void writeText(OutputStream out, int code, String mime, String text) throws Exception {
        writeBytes(out, code, mime, text.getBytes("UTF-8"));
    }

    private static String readLine(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') bos.write(c);
            if (bos.size() > 8192) break;
        }
        if (c == -1 && bos.size() == 0) return null;
        return new String(bos.toByteArray(), "UTF-8");
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> m = new HashMap<String, String>();
        if (query == null || query.length() == 0) return m;
        for (String kv : query.split("&")) {
            if (kv.length() == 0) continue;
            int eq = kv.indexOf('=');
            String k = eq >= 0 ? kv.substring(0, eq) : kv;
            String v = eq >= 0 ? kv.substring(eq + 1) : "";
            try {
                m.put(dec(k), dec(v));
            } catch (Throwable ignore) {
            }
        }
        return m;
    }

    private static String enc(String s) throws Exception {
        if (s == null) return "";
        return java.net.URLEncoder.encode(s, "UTF-8");
    }

    private static String dec(String s) throws Exception {
        if (s == null) return "";
        return URLDecoder.decode(s, "UTF-8");
    }
}
