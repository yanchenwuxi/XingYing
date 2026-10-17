package com.xing.tv.spider.js;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ESM（模块化）源码预处理。
 *
 * 影视仓那套 js 源有两类写法：模块型（import / export）和规则型（drpy 的 var rule）。
 * 内置的 JS 引擎按脚本模式求值，认不得 import/export，所以要把模块语法折成脚本语义：
 *
 *   import a from "b.js"     →  （删掉，库已被预置进全局）
 *   export default {...}     →  globalThis.__ESM_DEFAULT__ = {...}
 *   export function f(){}    →  globalThis.f = function f(){}
 *   export const x = 1       →  globalThis.x = 1
 *   export { a, b as c }     →  globalThis.a = a; globalThis.c = b;
 *
 * 三个坑（都是实测踩出来的，必须按下面的规则匹配）：
 *   1. 压缩库会把十几条 import 挤在同一行 —— 不能按整行匹配；
 *   2. 库代码里到处是 "import"/"export" 字样的字符串（cheerio 的选择器解析器里
 *      就有一句 "expected at least one import name"）—— 按词边界匹配会从字符串
 *      中间开始吃掉大段代码，所以 import 必须位于语句边界且模块名要以 .js 结尾；
 *   3. 尾部的分号一律不吃：`;import"a.js";import"b.js";` 是一条接一条的，
 *      吃掉分号会让下一条失去语句边界，结果只剥掉一半。
 */
public final class JsModule {

    private JsModule() {
    }

    private static final String BOUNDARY = "(^|[;{}])\\s*";

    /**
     * `import X from "path"` / `import * as X from "path"` / `import {a,b} from "path"`。
     *
     * 不能简单删掉：删了之后代码里引用的 X 就没了。这些库是宿主预置的，
     * 引擎按「文件名去掉目录和扩展名」把每个库挂成全局，所以这里把 import
     * 改写成对这个全局名的绑定，语义就接上了：
     *
     *   import cheerio from "assets://js/lib/cheerio.min.js"
     *     → const cheerio = globalThis["cheerio"];
     */
    private static final Pattern IMPORT_FROM = Pattern.compile(
            BOUNDARY + "import\\s*"
                    + "(\\*\\s*as\\s+[^\\s{},*\"']+|\\{[^}]*\\}|[^\\s{},*\"']+)"
                    + "\\s*from\\s*[\"']([^\"']*\\.(?:js|mjs|json))[\"']",
            Pattern.MULTILINE);

    /** 纯副作用引入（`import "path"`）可以直接去掉：库已由宿主预置。 */
    private static final Pattern IMPORT_BARE = Pattern.compile(
            BOUNDARY + "import\\s*[\"'][^\"']*\\.(?:js|mjs|json)[\"']",
            Pattern.MULTILINE);

    private static final Pattern EXPORT_STAR = Pattern.compile(
            BOUNDARY + "export\\s+\\*[^;\\n]*", Pattern.MULTILINE);

    private static final Pattern EXPORT_DEFAULT = Pattern.compile(
            BOUNDARY + "export\\s+default\\s*", Pattern.MULTILINE);

    private static final Pattern EXPORT_FUNCTION = Pattern.compile(
            BOUNDARY + "export\\s+(async\\s+)?function\\s*([A-Za-z_$][\\w$]*)", Pattern.MULTILINE);

    private static final Pattern EXPORT_VAR = Pattern.compile(
            BOUNDARY + "export\\s*(const|let|var)\\s+([A-Za-z_$][\\w$]*)\\s*=", Pattern.MULTILINE);

    private static final Pattern EXPORT_LIST = Pattern.compile(
            BOUNDARY + "export\\s*\\{([^}]*)\\}", Pattern.MULTILINE);

    private static final Pattern EXPORT_LEFT = Pattern.compile(
            BOUNDARY + "export\\s+", Pattern.MULTILINE);

    /**
     * `A as B`。标识符不能只认 ASCII：生态里的导出名有中文
     * （`i as 模板` 就是实际存在的写法），按 ASCII 匹配会漏，漏了就生成
     * 一段语法错误的代码。这里放宽成「非空白、非逗号、非花括号」。
     */
    /**
     * 语句开头的 const / let 降级成 var。
     *
     * 引擎是按「全局脚本」逐份求值的：规范里脚本顶层的 let/const 进入全局词法环境、
     * 是持久的；但换一个宿主（比如某些封装把它当 eval 跑）时这些绑定在求值结束就被
     * 丢掉了，后面的库和源就找不到它们（drpy 的 $js 就是这么丢的）。
     * 统一降成 var，两种情况下都对。只动语句边界上的声明，
     * `for (const x of …)` 这类不动。
     */
    private static final Pattern DECL = Pattern.compile("(^|[;{}\\n])\\s*(const|let)\\s+", Pattern.MULTILINE);

    private static final Pattern ALIAS = Pattern.compile(
            "^(\\S+)\\s+as\\s+(\\S+)$");

    /** 是否是模块型写法。 */
    public static boolean isModule(String source) {
        if (source == null) return false;
        return Pattern.compile("(^|[;{}\\s])export[\\s{]").matcher(source).find();
    }

    public static String preprocess(String source) {
        if (source == null) return "";
        String s = source;
        s = replaceImports(s);
        s = replace(IMPORT_BARE, s, 1);
        s = replace(EXPORT_STAR, s, 1);
        s = replace(EXPORT_DEFAULT, s, 1, "globalThis.__ESM_DEFAULT__ = ");
        s = preprocessFunction(s);
        s = preprocessVar(s);
        s = preprocessList(s);
        s = replace(EXPORT_LEFT, s, 1);
        return s;
    }

    /** 把语句边界的 const/let 换成 var。 */
    public static String demote(String src) {
        if (src == null) return "";
        Matcher m = DECL.matcher(src);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1) + "var "));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 把 import 改写成对预置库全局名的绑定。 */
    private static String replaceImports(String s) {
        Matcher m = IMPORT_FROM.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String head = m.group(1);
            String binding = m.group(2) == null ? "" : m.group(2).trim();
            String path = m.group(3);
            String name;
            String rep;
            if (binding.startsWith("{")) {
                // import {a, b as c} from "lib" —— 宿主把每个导出都挂成了全局名，
                // 所以逐个取全局，而不是从 lib 对象上解构。
                StringBuilder decl = new StringBuilder(head);
                String body = binding.substring(1, binding.length() - 1);
                for (String item : body.split(",")) {
                    String it = item.trim();
                    if (it.length() == 0) continue;
                    Matcher a = ALIAS.matcher(it);
                    String src;
                    String dst;
                    if (a.find()) {
                        src = a.group(1);
                        dst = a.group(2);
                    } else {
                        src = it;
                        dst = it;
                    }
                    decl.append("var ").append(dst).append(" = globalThis[").append(jsString(src)).append("];");
                }
                rep = decl.toString();
            } else {
                if (binding.startsWith("*")) {
                    int as = binding.indexOf("as");
                    name = as >= 0 ? binding.substring(as + 2).trim() : "spider";
                } else {
                    name = binding;
                }
                if (name.length() == 0) {
                    m.appendReplacement(sb, Matcher.quoteReplacement(head));
                    continue;
                }
                rep = head + "var " + name + " = globalThis[" + jsString(baseOf(path)) + "];";
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 模块路径 → 宿主挂载用的库名：去目录、去扩展名、去 .min。 */
    public static String baseOf(String path) {
        String p = path == null ? "" : path.trim();
        int slash = p.lastIndexOf('/');
        if (slash >= 0) p = p.substring(slash + 1);
        if (p.endsWith(".js") || p.endsWith(".mjs") || p.endsWith(".json")) {
            p = p.substring(0, p.lastIndexOf('.'));
        }
        if (p.endsWith(".min")) p = p.substring(0, p.length() - 4);
        return aliasOf(p);
    }

    /**
     * 打包时不能带非 ASCII 文件名：资源打进 APK 时中文名会被双重编码，
     * 运行时按原名根本取不到文件。所以随包的库统一用 ASCII 名，
     * 源里引用的中文名在这里映射过去，两边才对得上。
     */
    private static String aliasOf(String name) {
        if ("模板".equals(name)) return "moban";
        return name;
    }

    private static String jsString(String v) {
        return "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** export function f / export async function f */
    private static String preprocessFunction(String s) {
        Matcher m = EXPORT_FUNCTION.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String head = m.group(1);
            String asyncKw = m.group(2) == null ? "" : m.group(2);
            String fn = m.group(3);
            String rep = head + "globalThis." + fn + " = " + asyncKw + "function " + fn;
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** export const x = ... */
    private static String preprocessVar(String s) {
        Matcher m = EXPORT_VAR.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String rep = m.group(1) + "globalThis." + m.group(2) + " =";
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** export { a, b as c } */
    private static String preprocessList(String s) {
        Matcher m = EXPORT_LIST.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String rep = m.group(1) + listAssignments(m.group(2));
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** `{ a, b as c }` → `globalThis.a = a;globalThis.c = b;` */
    private static String listAssignments(String body) {
        StringBuilder out = new StringBuilder();
        for (String raw : body.split(",")) {
            String item = raw.trim();
            if (item.length() == 0) continue;
            Matcher a = ALIAS.matcher(item);
            String src;
            String dst;
            if (a.find()) {
                src = a.group(1);
                dst = a.group(2);
            } else {
                src = item;
                dst = item;
            }
            out.append("globalThis.").append(dst).append(" = ").append(src).append(';');
        }
        return out.toString();
    }

    /** 只保留第 group 组，其余丢弃。 */
    private static String replace(Pattern p, String s, int group) {
        return replace(p, s, group, "");
    }

    private static String replace(Pattern p, String s, int group, String suffix) {
        Matcher m = p.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(m.group(group) + suffix));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
