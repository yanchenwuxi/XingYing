package com.xing.tv.spider.js;

/**
 * JS 爬虫源的宿主能力接口。
 *
 * 所有方法一律用字符串收发：JS 侧负责 JSON.parse / JSON.stringify，Java 侧
 * 只搬字符串。跨语言只走一种类型，避免被数字/布尔/对象的转换规则绊住
 * （JS 的数字到 Java 一律是 double，用 int 形参接会直接抛异常）。
 *
 * 方法名与 TVBox 生态的约定保持一致，脚本里怎么调，这里就怎么实现。
 */
public interface JsHost {

    /** 同步请求，返回 {code, headers, content} 的 JSON 文本。 */
    String req(String url, String optionsJson);

    /** 异步请求，完成后宿主回调 __fireHttp(callbackId, result)。 */
    String http(String url, String optionsJson, String callbackId);

    /** HTML 选择器：取单值（支持 选择器;属性 的多级下钻）。 */
    String pdfh(String html, String rule);

    /** HTML 选择器：取列表，返回 JSON 数组文本。 */
    String pdfa(String html, String rule);

    /** 取单值并把相对地址补全成绝对地址。 */
    String pd(String html, String rule, String base);

    /** 在列表规则命中的每个节点下取值。 */
    String pdfl(String html, String rule, String listRule, String base);

    /** 相对地址转绝对地址。 */
    String joinUrl(String base, String rel);

    String md5(String text);

    /** encode = "1" 编码，"0" 解码。 */
    String base64(String text, String encode);

    String s2t(String text);

    String t2s(String text);

    /** 把 JS 数组（JSON 文本）里的字节按 GBK 解码成字符串。 */
    String gbkDecode(String jsonArray);

    String localGet(String key);

    String localSet(String key, String value);

    String localDel(String key);

    /** 取本机代理前缀，脚本拼出来的播放地址走它。 */
    String getProxy(String vip);

    /** js 源自己的代理地址（用于 parse 类接口）。 */
    String js2Proxy(String vip, String siteType, String siteKey, String headerJson, String url);

    String setTimeout(String callbackId, String ms);

    String clearTimeout(String callbackId);

    String log(String text);

    String toast(String text);

    String getAppVersion();

    String isVideoFormat(String url);

    String getCtx();
}
