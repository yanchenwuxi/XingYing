package com.xing.tv.spider.js;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 爬虫包（spider.jar / .zip）的装载。
 *
 * 配置里的写法五花八门，全部要认：
 *
 *   https://x/spider.jar                     直接地址
 *   https://x/spider.jar;md5;abcdef...       地址 + 校验值（对不上就重新下）
 *   https://x/spider.jar;md5;abc;extra       后面还挂别的东西
 *   ./spider.jar                             本地相对路径
 *
 * 包里的内容按路径取（脚本源写 "./js/xxx.js" 就是从包里取这个文件）。
 * 包里的 dex 用 DexClassLoader 加载，jar 源用得上。
 */
public final class JarLoader {

    private static final String TAG = "starfilm-jar";

    private JarLoader() {
    }

    // ---------------------------------------------------------------- 下载

    /** 按配置里的写法准备爬虫包，返回本地文件；准备不出来返回 null。 */
    public static File ensure(String spec) {
        if (spec == null || spec.length() == 0) return null;
        String url = spec;
        String md5 = "";
        String[] parts = spec.split(";");
        if (parts.length > 0) url = parts[0].trim();
        for (int i = 1; i < parts.length; i++) {
            String p = parts[i].trim();
            if ("md5".equalsIgnoreCase(p) && i + 1 < parts.length) {
                md5 = parts[i + 1].trim();
                break;
            }
        }
        if (url.length() == 0) return null;

        File dir = cacheDir();
        File target = new File(dir, nameOf(url));
        if (target.exists()) {
            if (md5.length() == 0 || md5.equalsIgnoreCase(md5Of(target))) {
                return target;
            }
            Log.d(TAG, "md5 mismatch, refetch: " + url);
        }
        try {
            byte[] data = JsHttp.getBytes(url, null, 30000);
            if (data == null || data.length == 0) return null;
            FileOutputStream fos = new FileOutputStream(target);
            fos.write(data);
            fos.close();
            return target;
        } catch (Throwable t) {
            Log.d(TAG, "download failed: " + url + " / " + t.getMessage());
            return null;
        }
    }

    public static File cacheDir() {
        File d = new File(com.xing.tv.App.Companion.getInstance().getFilesDir(), "spider");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private static String nameOf(String url) {
        String s = url;
        int q = s.indexOf('?');
        if (q > 0) s = s.substring(0, q);
        int slash = s.lastIndexOf('/');
        String name = slash >= 0 ? s.substring(slash + 1) : s;
        if (name.length() == 0 || name.indexOf('.') < 0) name = "spider.jar";
        return name.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /** 列出包内全部条目名；包打不开返回空表。Python 源要按前缀批量抠 base 库。 */
    public static java.util.List<String> list(File pkg) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (pkg == null || !pkg.exists()) return out;
        java.util.zip.ZipFile zip = null;
        try {
            zip = new java.util.zip.ZipFile(pkg);
            java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                java.util.zip.ZipEntry e = en.nextElement();
                if (!e.isDirectory()) out.add(e.getName());
            }
        } catch (Throwable t) {
            Log.d(TAG, "list jar failed: " + t.getMessage());
        } finally {
            if (zip != null) try { zip.close(); } catch (Throwable ignore) {}
        }
        return out;
    }

    // ---------------------------------------------------------------- 取内容

    /** 从包里取一个文件的内容；找不到返回 null。 */
    public static byte[] entry(File pkg, String path) {
        if (pkg == null || !pkg.exists() || path == null) return null;
        String p = path.startsWith("./") ? path.substring(2) : path;
        if (p.startsWith("/")) p = p.substring(1);
        ZipFile zip = null;
        try {
            zip = new ZipFile(pkg);
            ZipEntry e = zip.getEntry(p);
            if (e == null) {
                // 有些包把文件放在子目录下，退化成后缀匹配
                java.util.Enumeration<? extends ZipEntry> en = zip.entries();
                while (en.hasMoreElements()) {
                    ZipEntry cur = en.nextElement();
                    if (cur.isDirectory()) continue;
                    String n = cur.getName();
                    if (n.endsWith("/" + p) || n.equalsIgnoreCase(p)) {
                        e = cur;
                        break;
                    }
                }
            }
            if (e == null) return null;
            InputStream in = zip.getInputStream(e);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            return bos.toByteArray();
        } catch (Throwable t) {
            Log.d(TAG, "entry failed: " + path + " / " + t.getMessage());
            return null;
        } finally {
            if (zip != null) {
                try {
                    zip.close();
                } catch (Throwable ignore) {
                }
            }
        }
    }

    /** 取文本文件。 */
    public static String text(File pkg, String path) {
        byte[] d = entry(pkg, path);
        if (d == null) return "";
        try {
            return new String(d, "UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }

    /** 包里是否有这个文件。 */
    public static boolean has(File pkg, String path) {
        return entry(pkg, path) != null;
    }

    // ---------------------------------------------------------------- dex

    /**
     * 把包里的 dex 解到本地并加载。
     *
     * 直接对包本体做 DexClassLoader 也行，但包里除了 classes.dex 还可能有
     * 原生库和资源文件，先解出来再指过去更稳。
     */
    /** 该包装载后 dex 落地的目录（classLoader 与类名扫描都要用同一个）。 */
    public static File dexDirFor(File pkg) {
        return new File(cacheDir(), "dex-" + md5OfName(pkg.getName()));
    }

    public static ClassLoader classLoader(File pkg, File nativeDir) {
        try {
            File dexDir = dexDirFor(pkg);
            if (!dexDir.exists()) dexDir.mkdirs();
            unzip(pkg, "classes.dex", new File(dexDir, "classes.dex"));
            unzip(pkg, "classes2.dex", new File(dexDir, "classes2.dex"));
            unzip(pkg, "classes3.dex", new File(dexDir, "classes3.dex"));
            // 包里的原生库（有些爬虫带 so）
            if (nativeDir != null && !nativeDir.exists()) nativeDir.mkdirs();
            extractLibs(pkg, nativeDir);
            return new dalvik.system.DexClassLoader(
                    dexDir.getAbsolutePath(),
                    dexDir.getAbsolutePath(),
                    nativeDir == null ? dexDir.getAbsolutePath() : nativeDir.getAbsolutePath(),
                    JarLoader.class.getClassLoader());
        } catch (Throwable t) {
            Log.d(TAG, "classloader failed: " + t.getMessage());
            return null;
        }
    }

    private static void unzip(File pkg, String entryName, File out) {
        if (out.exists() && out.length() > 0) return;
        byte[] d = entry(pkg, entryName);
        if (d == null) return;
        try {
            FileOutputStream fos = new FileOutputStream(out);
            fos.write(d);
            fos.close();
        } catch (Throwable ignore) {
        }
    }

    /** 把包内 lib/<abi>/*.so 解到 nativeDir。 */
    private static void extractLibs(File pkg, File nativeDir) {
        if (nativeDir == null) return;
        ZipFile zip = null;
        try {
            zip = new ZipFile(pkg);
            java.util.Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String n = e.getName();
                if (e.isDirectory() || !n.endsWith(".so")) continue;
                if (n.indexOf("arm64-v8a") < 0) continue;
                File out = new File(nativeDir, n.substring(n.lastIndexOf('/') + 1));
                if (out.exists() && out.length() > 0) continue;
                InputStream in = zip.getInputStream(e);
                FileOutputStream fos = new FileOutputStream(out);
                byte[] buf = new byte[8192];
                int k;
                while ((k = in.read(buf)) > 0) fos.write(buf, 0, k);
                in.close();
                fos.close();
            }
        } catch (Throwable ignore) {
        } finally {
            if (zip != null) {
                try {
                    zip.close();
                } catch (Throwable ignore) {
                }
            }
        }
    }

    // ---------------------------------------------------------------- 清理

    public static void clear() {
        File d = cacheDir();
        delete(d);
    }

    public static long cacheBytes() {
        return size(cacheDir());
    }

    private static long size(File f) {
        if (f == null || !f.exists()) return 0;
        if (f.isFile()) return f.length();
        long sum = 0;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) sum += size(k);
        return sum;
    }

    private static void delete(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) delete(k);
        }
        f.delete();
    }

    private static String md5OfName(String name) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(name.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Throwable t) {
            return "dex";
        }
    }

    private static String md5Of(File f) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            in.close();
            byte[] d = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }
}
