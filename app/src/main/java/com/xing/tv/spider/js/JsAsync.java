package com.xing.tv.spider.js;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * async / await 去糖。
 *
 * 内置的 JS 引擎封装只提供「求值 → 取返回值」，不带微任务队列的驱动接口
 * （源码里没有 executePendingJob 这类出口）。也就是说 async 函数返回的 Promise
 * 永远不会被 resolve，直接调用只能拿到空结果。
 *
 * 而这些 js 源里的 async 几乎全是形式上的：await 的对象是宿主提供的同步能力
 * （req / pdfh / …），本身就不是真异步。所以把 async/await 去掉后语义等价，
 * 而且能立刻拿到返回值：
 *
 *   async function home() { let h = await req(url); ... }
 *     → function home() { let h = req(url); ... }
 *
 * 匹配全部带前后文约束，避免误伤字符串或注释里出现的同名单词
 * （例如 `"请 await 一下"` 这种文本不会被改）。
 */
public final class JsAsync {

    private JsAsync() {
    }

    /** 是否需要去糖：出现 async 函数或 await 表达式。 */
    public static boolean needed(String source) {
        if (source == null) return false;
        return ASYNC_FN.matcher(source).find()
                || ASYNC_ARROW.matcher(source).find()
                || AWAIT.matcher(source).find();
    }

    public static String deasync(String source) {
        if (source == null) return "";
        String s = ASYNC_FN.matcher(source).replaceAll("function");
        s = ASYNC_ARROW.matcher(s).replaceAll("");
        Matcher m = AWAIT.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1) + m.group(2)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * `async function` —— 前面的 lookbehind 避开 `myasync function` 这种写法；
     * function 本身要一起吃掉（早先只删了 async，结果留下 `functionfunction init()`，
     * 整份脚本语法错误）。
     */
    private static final Pattern ASYNC_FN = Pattern.compile("(?<![A-Za-z0-9_$.])async\\s+function\\b");

    /** `async (x) => …` —— 只删 async 这个词，括号与参数原样保留。 */
    private static final Pattern ASYNC_ARROW = Pattern.compile("(?<![A-Za-z0-9_$.])async\\s*(?=\\([^()]*\\)\\s*=>)");

    /**
     * `await expr` —— 只认处在语法分隔位置上的 await。
     *
     * 不能只做「前面不是标识符字符」就替换：`"请 await 一下"` 里 await 前面是空格，
     * 宽松规则会把字符串内容改掉。这里显式带上分隔符并原样回填。
     */
    private static final Pattern AWAIT = Pattern.compile(
            "(^|[;{},:=&|?!+\\-*/<>()\\[\\]]|\\breturn\\b|\\belse\\b|\\bof\\b)(\\s*)await\\s+",
            Pattern.MULTILINE);
}
