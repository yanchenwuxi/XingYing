package com.xing.tv.spider.js;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 容错 JSON 解析器（自研，不引三方库）。
 *
 * 面向「非常规 JSON 类接口」——这类接口在采集源里到处都是，标准的 JSONObject
 * 一碰就抛异常，实际会遇到下面这些写法，全部要能吃下去：
 *
 *   1. 无引号键名          {name:"x", api:"y"}
 *   2. 单引号字符串        {'name':'x'}
 *   3. 注释                // 行注释、/* 块注释 *&#47;
 *   4. 尾逗号              {"a":1,"b":2,}   /  [1,2,3,]
 *   5. 前后挂说明文字      "以下是接口列表：{...} 更新时间 2024"
 *   6. 变量前缀            var config = {...};
 *   7. NaN / Infinity      {"score":NaN}
 *   8. 十六进制数字         {"flag":0xff}
 *   9. 字符串里的裸引号    {'title':'他是"谁"'}
 *  10. 未转义换行/制表符   多行字符串
 *
 * 做法不是「直接构造对象」，而是先把输入规范化成严格 JSON 文本，再交给平台的
 * org.json 解析。这样既能复用平台成熟的解析器，又只需要维护一层字符状态机。
 */
public final class LenientJson {

    private LenientJson() {
    }

    // ------------------------------------------------------------ 对外接口

    /** 解析任意文本，返回 JSONObject 或 JSONArray。 */
    public static Object parse(String raw) throws Exception {
        if (raw == null) throw new IllegalArgumentException("empty");
        String s = normalize(raw);
        if (s == null || s.length() == 0) throw new IllegalArgumentException("no json found");
        char c = s.charAt(0);
        if (c == '{') return new JSONObject(s);
        if (c == '[') return new JSONArray(s);
        throw new IllegalArgumentException("not a container");
    }

    public static JSONObject obj(String raw) throws Exception {
        Object o = parse(raw);
        if (o instanceof JSONObject) return (JSONObject) o;
        throw new IllegalArgumentException("not a json object");
    }

    public static JSONArray arr(String raw) throws Exception {
        Object o = parse(raw);
        if (o instanceof JSONArray) return (JSONArray) o;
        throw new IllegalArgumentException("not a json array");
    }

    /** 解析失败返回 null，不抛异常。 */
    public static JSONObject objOrNull(String raw) {
        try {
            return obj(raw);
        } catch (Throwable t) {
            return null;
        }
    }

    public static JSONArray arrOrNull(String raw) {
        try {
            return arr(raw);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 解析成对象，失败时按数组再试一次，最后再试「值是字符串的 JSON 文本」。
     * 采集接口有三种常见外壳：直接给对象、直接给数组、把 JSON 塞在 content 字段里。
     */
    public static Object anyOrNull(String raw) {
        if (raw == null) return null;
        try {
            return parse(raw);
        } catch (Throwable ignore) {
        }
        // 有些接口把 JSON 当字符串又包了一层
        JSONObject o = objOrNull(raw);
        if (o != null) {
            for (String k : new String[]{"content", "data", "result", "json"}) {
                String inner = o.optString(k, "");
                if (inner.length() > 1 && (inner.charAt(0) == '{' || inner.charAt(0) == '[')) {
                    try {
                        return parse(inner);
                    } catch (Throwable ignore) {
                    }
                }
            }
            return o;
        }
        return null;
    }

    // ------------------------------------------------------------ 规范化

    /** 扫描字符流，把宽松写法整理成严格 JSON；遇到第一个完整的容器就收工。 */
    static String normalize(String src) {
        int n = src.length();
        int i = 0;

        // 跳过 BOM
        if (n > 0 && src.charAt(0) == '\uFEFF') i = 1;

        // 定位起始：允许前面有 "var x =" / "接口列表：" 之类的说明文字
        while (i < n) {
            char c = src.charAt(i);
            if (c == '{' || c == '[') break;
            i++;
        }
        if (i >= n) return null;

        StringBuilder out = new StringBuilder(n);
        Deque<Character> stack = new ArrayDeque<Character>();
        boolean expectKey = false;
        boolean inStr = false;
        char quote = '"';

        while (i < n) {
            char c = src.charAt(i);

            // ---- 字符串内部 ----
            if (inStr) {
                if (c == '\\') {
                    if (i + 1 < n) {
                        char e = src.charAt(i + 1);
                        // JSON 里 '\'' 是非法转义，统一降级成裸单引号
                        if (e == '\'') {
                            out.append('\'');
                        } else {
                            out.append('\\').append(e);
                        }
                        i += 2;
                        continue;
                    }
                    i++;
                    continue;
                }
                if (c == quote) {
                    out.append('"');
                    inStr = false;
                    i++;
                    continue;
                }
                if (c == '"') {
                    out.append("\\\"");
                    i++;
                    continue;
                }
                if (c == '\n') {
                    out.append("\\n");
                    i++;
                    continue;
                }
                if (c == '\r') {
                    out.append("\\r");
                    i++;
                    continue;
                }
                if (c == '\t') {
                    out.append("\\t");
                    i++;
                    continue;
                }
                if (c < 0x20) {
                    out.append(' ');
                    i++;
                    continue;
                }
                out.append(c);
                i++;
                continue;
            }

            // ---- 注释（只在字符串外识别）----
            if (c == '/' && i + 1 < n) {
                char d = src.charAt(i + 1);
                if (d == '/') {
                    i += 2;
                    while (i < n && src.charAt(i) != '\n') i++;
                    continue;
                }
                if (d == '*') {
                    i += 2;
                    while (i + 1 < n && !(src.charAt(i) == '*' && src.charAt(i + 1) == '/')) i++;
                    i += 2;
                    continue;
                }
            }

            // ---- 字符串起始 ----
            if (c == '"' || c == '\'') {
                quote = c;
                inStr = true;
                out.append('"');
                expectKey = false;
                i++;
                continue;
            }

            // ---- 结构字符 ----
            if (c == '{') {
                stack.push('{');
                out.append('{');
                expectKey = true;
                i++;
                continue;
            }
            if (c == '[') {
                stack.push('[');
                out.append('[');
                expectKey = false;
                i++;
                continue;
            }
            if (c == '}' || c == ']') {
                trimTrailingComma(out);
                out.append(c);
                if (!stack.isEmpty()) stack.pop();
                i++;
                if (stack.isEmpty()) return out.toString(); // 后面的说明文字不再关心
                expectKey = false;
                continue;
            }
            if (c == ',') {
                out.append(',');
                expectKey = !stack.isEmpty() && stack.peek() == '{';
                i++;
                continue;
            }
            if (c == ':') {
                out.append(':');
                expectKey = false;
                i++;
                continue;
            }
            if (c == ';') { // "var a = {...};" 之类的收尾
                i++;
                continue;
            }
            if (c <= ' ') {
                i++;
                continue;
            }

            // ---- 裸词：键名 / 数字 / 字面量 ----
            int j = i;
            while (j < n && !isStop(src.charAt(j))) j++;
            String word = src.substring(i, j);
            if (word.length() == 0) { // 兜底防死循环
                i++;
                continue;
            }
            boolean key = expectKey && !stack.isEmpty() && stack.peek() == '{';
            if (key) {
                out.append('"').append(word).append('"');
            } else {
                out.append(wordValue(word));
            }
            expectKey = false;
            i = j;
        }
        return null;
    }

    /** 裸词是否为分隔符。 */
    private static boolean isStop(char c) {
        if (c <= ' ') return true;
        switch (c) {
            case ',':
            case ':':
            case '{':
            case '}':
            case '[':
            case ']':
            case '"':
            case '\'':
            case '/':
            case ';':
                return true;
            default:
                return false;
        }
    }

    /** 裸词的归一化：数字/字面量原样放行，非法值降级，其余当字符串。 */
    private static String wordValue(String w) {
        if ("true".equals(w) || "false".equals(w) || "null".equals(w)) return w;
        if ("NaN".equals(w) || "Infinity".equals(w) || "-Infinity".equals(w)
                || "+Infinity".equals(w) || "undefined".equals(w)) {
            return "0";
        }
        // 十六进制
        String t = w;
        boolean neg = t.startsWith("-");
        if (neg) t = t.substring(1);
        if (t.startsWith("0x") || t.startsWith("0X")) {
            try {
                long v = Long.parseLong(t.substring(2), 16);
                return (neg ? "-" : "") + v;
            } catch (Throwable ignore) {
                return "0";
            }
        }
        // 常规数字
        if (isNumber(t)) return w;
        // 余下的裸词按字符串处理（很多接口会写 {type:cms}）
        return "\"" + w.replace("\"", "\\\"") + "\"";
    }

    private static boolean isNumber(String s) {
        if (s.length() == 0) return false;
        boolean dot = false;
        boolean exp = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '+' || c == '-') {
                if (i != 0 && !(s.charAt(i - 1) == 'e' || s.charAt(i - 1) == 'E')) return false;
            } else if (c == '.') {
                if (dot || exp) return false;
                dot = true;
            } else if (c == 'e' || c == 'E') {
                if (exp) return false;
                exp = true;
            } else if (!Character.isDigit(c)) {
                return false;
            }
        }
        return true;
    }

    /** 删掉容器收尾前那个多余的逗号。 */
    private static void trimTrailingComma(StringBuilder out) {
        int k = out.length() - 1;
        while (k >= 0 && out.charAt(k) <= ' ') k--;
        if (k >= 0 && out.charAt(k) == ',') out.deleteCharAt(k);
    }
}
