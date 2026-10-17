package com.xing.tv.spider.js;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * 采集规则里的 HTML 选择器（TVBox 生态的 pdfh / pdfa / pd / pdfl）。
 *
 * 规则写法是 jQuery 风格，支持多级下钻：
 *
 *   pdfh(html, ".box .item&&Text")           取文本
 *   pdfa(html, ".list&&li")                  取列表（返回 outerHtml 数组）
 *   pdfh(html, ".item&&a&&href")             逐级下钻
 *   pdfh(html, ".item&&a&&href||img&&src")   多个候选，先命中的算
 *   pd(html, ".item&&a&&href", base)         取到值后补成绝对地址
 *
 * `&&` 前面是选择器，后面是取值方式；`;` 用于连接「列表规则;条目规则」。
 */
public final class HtmlRules {

    private HtmlRules() {
    }

    // ------------------------------------------------------------ 取值

    /** 取列表：返回节点 outerHtml 的 JSON 数组文本。 */
    public static String pdfa(String html, String rule) {
        List<String> out = new ArrayList<String>();
        try {
            for (Element el : list(html, rule)) {
                out.add(el.outerHtml());
            }
        } catch (Throwable ignore) {
        }
        return toJsonArray(out);
    }

    /** 取单值。 */
    public static String pdfh(String html, String rule) {
        try {
            return extract(parse(html), rule);
        } catch (Throwable t) {
            return "";
        }
    }

    /** 取单值并补全地址。 */
    public static String pd(String html, String rule, String base) {
        String v = pdfh(html, rule);
        return absolutize(v, base);
    }

    /** 列表 + 条目规则两级取值。 */
    public static String pdfl(String html, String rule, String listRule, String base) {
        List<String> out = new ArrayList<String>();
        try {
            for (Element el : list(html, listRule)) {
                String v = extract(el, rule);
                out.add(absolutize(v, base));
            }
        } catch (Throwable ignore) {
        }
        return toJsonArray(out);
    }

    // ------------------------------------------------------------ 内部

    private static Document parse(String html) {
        return Jsoup.parseBodyFragment(html == null ? "" : html);
    }

    /** 按规则取出节点列表。规则可能是 "容器&&条目" 的两段式。 */
    private static List<Element> list(String html, String rule) {
        Document doc = parse(html);
        String r = rule == null ? "" : rule.trim();
        int idx = r.indexOf("&&");
        Elements found;
        if (idx > 0) {
            Elements holders = selectChain(doc, r.substring(0, idx));
            String item = r.substring(idx + 2);
            if (item.length() == 0 || "Text".equalsIgnoreCase(item) || "Html".equalsIgnoreCase(item)) {
                found = holders;
            } else {
                Elements acc = new Elements();
                for (Element h : holders) {
                    acc.addAll(selectChain(h, item));
                }
                found = acc;
            }
        } else {
            found = selectChain(doc, r);
        }
        return new ArrayList<Element>(found);
    }

    /** 逐段下钻；每段可以是被 `;` 或空格切开的多个候选。 */
    private static Elements selectChain(Element root, String chain) {
        Elements cur = new Elements(root);
        String[] parts = chain.split("&&");
        for (String raw : parts) {
            String part = raw.trim();
            if (part.length() == 0) continue;
            cur = select(cur, part);
            if (cur.isEmpty()) return cur;
        }
        return cur;
    }

    /** 单个选择器；`;` 分隔多个候选，先命中的算。 */
    private static Elements select(Elements roots, String selector) {
        Elements out = new Elements();
        String[] cands = selector.split(";");
        for (String cand : cands) {
            String s = cand.trim();
            if (s.length() == 0) continue;
            for (Element root : roots) {
                Elements found = root.select(s);
                if (!found.isEmpty()) {
                    out.addAll(found);
                }
            }
            if (!out.isEmpty()) return out;
        }
        return out;
    }

    /** 从节点按规则取值。支持 `选择器&&字段` 的多级下钻以及 `||` 兜底。 */
    private static String extract(Element root, String rule) {
        String r = rule == null ? "" : rule.trim();
        if (r.length() == 0) return "";

        String[] alternatives = r.split("\\|\\|");
        for (String alt : alternatives) {
            String v = extractOne(root, alt.trim());
            if (v != null && v.length() > 0) return v;
        }
        return "";
    }

    private static String extractOne(Element root, String rule) {
        String[] parts = rule.split("&&");
        Element cur = root;
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i].trim();
            if (p.length() == 0) continue;
            boolean last = i == parts.length - 1;
            if (last) {
                // 末段既可能是字段名，也可能是最后一级选择器
                if (isField(p)) return fieldOf(cur, p);
                Elements found = select(new Elements(cur), p);
                if (found.isEmpty()) return "";
                return fieldOf(found.first(), "Text");
            }
            Elements found = select(new Elements(cur), p);
            if (found.isEmpty()) return "";
            cur = found.first();
        }
        return "";
    }

    private static boolean isField(String s) {
        String t = s.toLowerCase();
        return "text".equals(t) || "html".equals(t) || "outerhtml".equals(t)
                || "ownhtml".equals(t) || "href".equals(t) || "src".equals(t)
                || "title".equals(t) || "alt".equals(t) || "value".equals(t)
                || "data-src".equals(t) || "data-original".equals(t)
                || "data-echo".equals(t) || "data-url".equals(t)
                || "data-lazy-src".equals(t) || "style".equals(t) || "id".equals(t)
                || "class".equals(t) || "content".equals(t);
    }

    private static String fieldOf(Element el, String field) {
        if (el == null) return "";
        String f = field == null ? "Text" : field.trim();
        String lower = f.toLowerCase();
        if ("text".equals(lower)) return el.text();
        if ("html".equals(lower)) return el.html();
        if ("outerhtml".equals(lower)) return el.outerHtml();
        if ("ownhtml".equals(lower)) return el.ownText();
        // 属性取法：先看有没有直接属性，再退回常见的懒加载写法
        String v = el.attr(f);
        if (v.length() == 0 && ("src".equals(lower) || "href".equals(lower))) {
            for (String k : new String[]{"data-src", "data-original", "data-echo", "data-url", "data-lazy-src"}) {
                v = el.attr(k);
                if (v.length() > 0) break;
            }
        }
        return v;
    }

    private static String absolutize(String value, String base) {
        if (value == null || value.length() == 0) return "";
        if (base == null || base.length() == 0) return value;
        if (value.startsWith("http://") || value.startsWith("https://") || value.startsWith("data:")) {
            return value;
        }
        return join(base, value);
    }

    /** 相对地址拼绝对地址。 */
    public static String join(String base, String rel) {
        if (rel == null) return "";
        String r = rel.trim();
        if (r.length() == 0) return base == null ? "" : base;
        if (r.startsWith("http://") || r.startsWith("https://") || r.startsWith("data:")) return r;
        if (base == null || base.length() == 0) return r;
        try {
            return URI.create(base).resolve(r).toString();
        } catch (Throwable ignore) {
        }
        // 退化成手工拼接
        if (r.startsWith("//")) {
            int p = base.indexOf("://");
            return p > 0 ? base.substring(0, p + 1) + r : r;
        }
        int cut = base.indexOf("://");
        if (cut < 0) return r;
        int slash = base.indexOf('/', cut + 3);
        String root = slash < 0 ? base : base.substring(0, slash);
        if (r.startsWith("/")) return root + r;
        int lastSlash = base.lastIndexOf('/');
        String dir = lastSlash > cut + 2 ? base.substring(0, lastSlash + 1) : root + "/";
        return dir + r;
    }

    private static String toJsonArray(List<String> list) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(quote(list.get(i)));
        }
        return sb.append(']').toString();
    }

    private static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(' ');
                    else sb.append(c);
            }
        }
        return sb.append('"').toString();
    }
}
