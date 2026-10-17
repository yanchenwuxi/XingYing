# 星影（XingYing）

TVBox 兼容的安卓手机影音聚合播放器。

- 应用名：**星影**
- 包名：`com.xing.tv`
- 版本：1.0.0（versionCode 1）
- 最低系统：Android 7.0（API 24）
- 架构：arm64-v8a

---

## 一、功能

### 界面

单 Activity + 三页签的主结构（首页 / 直播 / 我的），底部导航切换，
Fragment 用 `show`/`hide` 保留各自状态；搜索、详情、播放、历史、设置为独立 Activity，
接口源管理收进首页与我的页共用的底部弹窗（`SourceSheet`），不再占独立页面。
搜索不在导航里：它与首页搜索条重复，统一从首页搜索条进独立搜索页。

Material3 深色主题（primary `#7C5CFF`，渐变 `#7C5CFF→#B44BFF→#FF5C8A`），
圆角海报、胶囊 chip、卡片分组，图标全部为自绘矢量。

| 模块 | 说明 |
| --- | --- |
| 首页 | 搜索条 + **接口行**（当前接口名 + 换接口入口，进底部接口源弹窗）+ 继续观看横滑 + 站点**文字页签**（选中项高亮加粗 + 主色指示线，行尾附「全部站点」弹窗直达）+ 分类胶囊 + **筛选**按钮（仅 macCMS 站点露出，按年份/地区/类型筛）+ 3 列圆角海报网格 + 回到顶部按钮；站点未声明分类时分类行整行隐藏 |
| 继续观看 | 首页顶部横滑块，取最近观看记录，展示集数与已看百分比，点按直接续播 |
| 详情 | 头图（海报铺底渐隐沉入背景）+ 海报层叠信息卡（年份·地区·备注、导演主演）+ 播放/收藏双按钮（有进度时变为**续播入口**并提示上次看到哪）+ 简介展开收起 + 线路胶囊（附条数）+ 选集网格（附集数 + **倒序切换**）+ **同类推荐**横滑（同站同类型，无类型时退回本站推荐） |
| 直播 | **内嵌播放器常驻顶部**（边看边选台，切 tab/退后台自动暂停、回来继续播）+ 当前频道卡（台标/首字、频道名、状态、**换源**、全屏入口）+ **左分组 / 右频道两列**（分组竖排常驻，频道为序号 + 频道名纵向列表，正在播的行高亮并标「直播中」）；按**内容嗅探**解析 m3u / `#genre#` 文本 / JSON 三种格式，台标取 m3u `tvg-logo` / JSON `logo`（缺失回退为频道首字） |
| 搜索 | 独立搜索页（从首页搜索条进入）：**全部站点聚合检索**——并发请求所有可搜索站点，跨站点**轮流取**结果（避免首个返回几十条的站点把其余站点挤没），按片名去重并在副标题标注来源站点；也可退回单站点；搜索历史胶囊（点按复用、可清空） |
| 我的 | 用户卡（只留应用名与图标）+ 数据看板（历史/收藏/接口源）+ 功能列表（**历史与收藏**/接口源/播放设置/**清理缓存**/**关于**，每行图标 + 标题 + 摘要 + 箭头；清理与关于直接在本页完成） |
| 历史与收藏 | 同一页面的两个页签（入口已合并为一个），进度条与已看百分比、长按删除（二次确认）、一键清空（二次确认） |
| 接口源管理 | 首页接口行与我的页共用的**底部弹窗**：接口列表（点按切换、使用中标记、删除）+ 添加接口（名称 + 地址输入）+ **本地文件导入**（系统文件选择器读取，复制进应用私有目录，无需存储权限，删除源时一并清理）；添加与导入时立即拉取并报告可用性 |
| 设置 | 播放偏好（自动换源 / 续播 / 倍速 / **跳过片头片尾**）、缓存清理（显示真实占用）、历史收藏导出导入 |

> 公开配置里的站点名与直播源名常写成 `🐬xxx影视永久免费如有收费的都是骗子` 这种
> 「表情 + 一整句推广语」，配上宽度截断会留下一个占位的豆腐块，且一行只放得下一个。
> 因此首页站点页签、搜索站点胶囊、直播源名（换源弹窗）统一先**去 emoji**
> （`Chips.stripEmoji`）；胶囊类标签另按最大宽度截断（`newChip(maxWidthDp)`），
> 首页站点页签走横滑则不设上限。

### 数据与播放

| 模块 | 说明 |
| --- | --- |
| 配置加载 | TVBox 单仓 JSON、多仓 `urls[]` / `stores`、顶层站点数组、内置演示配置兜底；兼容中文域名（IDN）与按 UA 分流的配置站；UTF-8 BOM、整行注释、尾逗号、JSONP 包裹容错 |
| 站点能力分级 | 按 `type` / `api` 识别站点类型，只把本应用能驱动的站点交给界面（macCMS 0/1、T4 4、Python `py_*`）；jar 爬虫（`csp_*`）与 js 源在入口就挡掉，不再被伪装成采集接口去空请求 |
| 配置可用性诊断 | 统计可用/不可用站点数与原因、直播源是否指向客户端内置代理；首页空态、接口源弹窗、添加/导入结果三处直接说明「为什么用不了、还剩什么能用」 |
| JSON 采集（type=1） | macCMS JSON 接口：`?ac=detail&t=&pg=&wd=&ids=`，分类下筛选拼 `area` / `year` / `class` |
| XML 采集（type=0） | macCMS XML 接口：`?ac=videolist&...`，含 `<class>` 分类解析，筛选同 JSON |
| T4 聚合（type=4） | `?ac=detail&filter=true&t=&pg=`、`?wd=&ac=detail&quick=false`、播放走 `?play=&flag=` |
| Python 源（type=3, `api=py_*`） | 内嵌 CPython 3.10 运行时，加载 `ext` 指向的 `.py` 脚本 |
| 播放 | Media3 ExoPlayer，支持 http/https 的 mp4 / m3u8 / flv / mkv / ts |
| 选集 | 多线路 `线路A$$$线路B`、剧集 `名称$url#名称$url` 播放串 |
| 播放器 | 自定义底栏（播放暂停/时间/进度/**下一集**/**选集**）+ 顶栏（返回/片名·选集双行/倍速/**音轨字幕**/**在线字幕**/**播放设置**/小窗/锁定）+ **设置弹窗**（倍速/画面比例/跳过片头片尾/重播/刷新）+ **选集面板**（按站点取回详情填充，当前集高亮）+ 错误面板（换源重试/返回）+ MediaSession 通知栏控制、横竖屏手势 |
| 在线字幕 | ASSRT 免费 API 按片名搜索（需自填个人 Token，走用户自己配额）+ 字幕直链 + 本地字幕文件（SAF）；srt/vtt 按当前进度挂载，可在音轨菜单切换/关闭 |
| 起播行为 | **自动跳过片头 / 片尾**（0/5/10/15/30/60/90 秒，0 表示不跳，可在播放器设置里快捷修改）；片尾每次进入只跳一次，避免用户往回拖时又被拽回去 |
| 手势 | 左右快进快退、左侧亮度、右侧音量、双击暂停 |
| 直播模式 | 走 `isLive` 参数复用播放器：隐藏进度条、不续播、不写历史 |
| 换源重试 | 播放失败时按同名影片在其余站点检索；可开关「自动换源」 |
| 历史/收藏 | 本地保存，记录含播放进度，支持续播与分页浏览 |
| 本地代理 + 嗅探 | 播放地址无法直连时走内置 `LocalProxyServer`（默认 9978，被占用自动回落），`Sniffer` 从页面/JSON/base64 中还原 m3u8·mp4·flv 直链 |
| 数据迁移 | 历史与收藏可导出到剪贴板、从剪贴板导入，无需文件权限 |
| 配置容错 | 配置落盘缓存，断网冷启动仍可加载；站点 `header` / Cookie 持久化 |

---

## 二、直播源格式

`LiveRepository` 不信任配置里 `lives` 的 `type` 字段，改为**按响应内容嗅探**，
因此同一个地址无论声明成什么类型都能正确解析：

| 格式 | 识别依据 | 示例 |
| --- | --- | --- |
| m3u | 含 `#EXTM3U` | `#EXTINF:-1 group-title="央视",CCTV1` |
| 分组文本 | 含 `#genre#` | `央视,#genre#` 换行 `CCTV1,http://...` |
| JSON | 可解析为 JSON 对象 | `{"lives":[{"name":"地方","list":[...]}]}` |

解析规则：

- m3u 的 `group-title` 与 `#EXTGRP` 都会归组，两者同时出现时 `group-title` 优先；
  没有分组信息的条目归入「未分组」。
- 分组文本中的**相对地址**会基于源地址补全，不会被丢弃。
- JSON 支持 `lives[]` 直接展开，也支持 `{name, list}` 的两层包装。
- JSON 解析失败时自动回退到文本解析。

---

## 三、配置兼容边界

### 站点类型

按 `type` 与 `api` 判定站点能否被驱动，只把可用的交给界面：

| 类型 | 判定 | 状态 |
| --- | --- | --- |
| XML 采集 | `type=0` | 支持 |
| JSON 采集 | `type=1`；未声明 `type` 但 `api` 是 http(s) 地址时按此试探 | 支持 |
| T4 聚合 | `type=4` | 支持 |
| Python 源 | `type=3` 且 `api` 以 `py_` 开头 | 支持 |
| jar 爬虫 | `type=3` 且 `api` 是 `csp_*` 或声明了 `jar` | **不支持** |
| js 源 | `type=2` | **不支持** |

jar 爬虫与 js 源需要在客户端里加载并执行第三方字节码，与本项目「从零原创、
不含第三方反编译代码」的前提冲突，因此不做。这类站点以前会被误当成 macCMS 接口
去请求，界面上表现为一批永远转圈的站点、首页看起来像「解析失败」；现在在入口就
挡掉，并由配置诊断说明原因。

### 会按 UA 分流的配置站

相当一部分公开配置站与采集站按 `User-Agent` 区分客户端：浏览器 UA 返回一个 HTML
落地页，UA 中带 `okhttp` 才返回真正的 JSON/XML（TVBox 客户端本身就是用 okhttp 发
请求的）。`Http` 因此区分两种 UA：

- 配置拉取、采集接口请求、直播源拉取 → `api = true`，用 `okhttp/4.12.0`；
- 嗅探播放地址时抓网页、下载 `.py` 脚本 → 保留浏览器 UA。

配置入口还会把「返回的是网页」单独报成可读错误，而不是抛一个裸的 JSON 解析异常。

### 中文域名（IDN）

`http://中文域名/xx` 这类地址可以正常使用。部分 CDN 只认 punycode 的 `Host` 头，
收到原始 UTF-8 域名会直接返回 400；OkHttp 解析 URL 时会把 host 规范化为 punycode，
请求头里因此不会出现非 ASCII 字节（见 `HttpUrlIdnTest`）。

### 直播源指向本机

不少公开配置把 `lives` 写成 `http://127.0.0.1:xxxx/iptv.m3u`，那是 TVBox 客户端自己
起的内置代理。本应用不提供该服务，必然连不上，因此 `TvConfig.Live.isLoopback` 会
提前识别，直播页与配置诊断给出明确说明而不是抛一个连接异常。

---

## 四、Python 源说明

Python 源脚本需实现以下模块级函数（返回 `dict` 或 JSON 字符串）：

```python
def init(ext): ...
def homeContent(filter): ...
def homeVideoContent(): ...
def categoryContent(tid, pg, filter, ext): ...
def detailContent(ids): ...        # ids: list[str]
def searchContent(wd, quick): ...
def playerContent(flag, id, ext): ...
def localProxy(params): ...
```

`src/main/python/app.py` 是桥接层，负责加载脚本、建立命名空间、按上述约定分发调用、
把返回值序列化成 JSON 字符串交回 Java 侧。

已内置的 pip 依赖（打进 APK）：`requests`、`lxml`、`pyquery`。
如需更多库，在 `app/build.gradle` 的 `chaquopy { pip { ... } }` 中追加。

---

## 五、构建

### 环境要求

- JDK 17+（本项目用 `C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot`）
- Android SDK（本机 `C:\Users\LGC\AppData\Local\Android\Sdk`）
- 构建机 Python 3.10.11（Chaquopy 打包 pip 依赖需要）：
  `C:\Users\LGC\AppData\Local\Programs\Python\Python310\python.exe`
- Gradle 8.13（已带 wrapper）

### 构建命令

```powershell
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot"
.\gradlew.bat :app:assembleDebug     # 调试包
.\gradlew.bat :app:assembleRelease   # 签名正式包
.\gradlew.bat :app:testDebugUnitTest :app:testReleaseUnitTest  # 单元测试（100 个用例）
```

产物：

- `app\build\outputs\apk\debug\app-debug.apk`
- `app\build\outputs\apk\release\app-release.apk`

### 首次构建注意

1. `local.properties` 中的 `sdk.dir` 需指向本机 Android SDK。
2. `gradle.properties` 中的 `android.overridePathCheck=true` 用于容忍路径中的中文
   （本项目位于 `...\默认项目\...`）。
3. 签名信息在 `app\build.gradle` 的 `signingConfigs.release` 中，
   keystore 为 `keystore\xingying.jks`（alias `xingying`）。

### 构建期已知问题

| 现象 | 处理 |
| --- | --- |
| `minifyReleaseWithR8` 报 dex 被锁 | `gradlew --stop` 后删除 `app\build\intermediates\dex` 重跑 |
| `lintVitalRelease` 退出码非 0 但输出 `BUILD SUCCESSFUL` | 已知误报，可忽略（最终判定以 `BUILD SUCCESSFUL` 为准） |
| 单测报 `ClassNotFoundException` / `initializationError` | 删除 `%TEMP%\xingying-test-cp` 后重跑，见第五节 |

### 关于 debug 构建的测试探针

`app/src/debug/` 下有一个仅参与 debug 构建的测试探针页 `ProbeActivity`，
用于在无法注入 UI 事件的设备上（如 MIUI 未开启「USB调试（安全设置）」时）
用 `adb shell am start` 直接驱动各数据源做端到端验证：

```powershell
# 需先建立端口回连，让手机能访问本机的 macCMS 模拟接口
adb reverse tcp:8099 tcp:8099
adb shell am start -n com.xing.tv/.ProbeActivity `
  --es config http://127.0.0.1:8099/config.json `
  --es site t1json --es action home
# action 可选: load / home / cat / detail / search / play
# 读取结果：adb shell uiautomator dump /sdcard/p.xml && adb pull /sdcard/p.xml
```

debug 清单同时把 `PlayerActivity` 临时设为可导出，以便直接验证真实播放链路。
**这两处改动只影响 debug 构建，release 包中 `PlayerActivity` 仍为 `exported=false`，
也不包含 `ProbeActivity`**（已用 dexdump 与 aapt2 校验确认）。

---

## 六、源码结构

```
XingYing/
├── app/src/main/
│   ├── java/com/xing/tv/
│   │   ├── App.kt                    # 入口，初始化网络/存储
│   │   ├── api/
│   │   │   ├── ConfigLoader.kt       # TVBox 配置解析（单仓/多仓/本地/非标准 JSON）+ 可用性诊断
│   │   │   ├── SubtitleApi.kt        # 在线字幕搜索与下载（ASSRT 协议）
│   │   │   ├── ConfigHolder.kt       # 当前配置持有
│   │   │   ├── ConfigProvider.kt     # 配置获取唯一入口（asset:// 与 http）
│   │   │   ├── Source.kt             # 数据源抽象 + 工厂
│   │   │   ├── JsonCmsSource.kt      # type=1 macCMS JSON
│   │   │   ├── XmlCmsSource.kt       # type=0 macCMS XML
│   │   │   ├── T4Source.kt           # type=4 T4 聚合
│   │   │   └── LiveRepository.kt     # 直播源三格式解析（内容嗅探）
│   │   ├── py/
│   │   │   ├── PyBridge.kt           # Chaquopy 运行时桥接
│   │   │   └── PySource.kt           # type=3 py 源实现
│   │   ├── player/
│   │   │   └── TrackLabels.kt        # 音轨/字幕轨显示标签：名字 > 语种码翻译 > 序号
│   │   ├── model/
│   │   │   ├── TvConfig.kt           # sites/parses/lives 模型 + 站点分级 + Diag
│   │   │   ├── Vod.kt                # 列表/详情/线路/播放串解析
│   │   │   └── Live.kt               # 直播分组与频道模型
│   │   │   └── Filter.kt             # 分类筛选条件 + 候选项
│   │   ├── data/
│   │   │   ├── Prefs.kt              # 配置地址与播放偏好
│   │   │   ├── HistoryStore.kt       # 历史与收藏（含进度、导出导入）
│   │   │   ├── SourceStore.kt        # 多接口源管理
│   │   │   ├── LocalConfigStore.kt   # 本地配置导入（SAF → 私有目录 → local://）
│   │   │   └── SearchHistory.kt      # 搜索历史
│   │   ├── net/Http.kt               # OkHttp 封装 + 双 UA（接口/网页）+ 编码 + Cookie
│   │   ├── proxy/
│   │   │   ├── LocalProxyServer.kt   # 本地代理（端口回落 / MIME 推断）
│   │   │   └── Sniffer.kt            # 播放地址嗅探与还原
│   │   └── ui/
│   │       ├── MainActivity.kt       # Fragment 宿主 + 底部导航 + 返回键退回首页
│   │       ├── MainViewModel.kt      # 首页状态与取数（配置/站点/分类/筛选/翻页）
│   │       ├── HomeFragment.kt       # 首页（接口行 + 站点页签/全部站点 + 分类/筛选 + 网格）
│   │       ├── SourceSheet.kt        # 接口源底部弹窗（列表切换 + 添加 + 本地导入 + 即时验证）
│   │       ├── LiveFragment.kt       # 直播（内嵌播放 + 频道卡 + 左分组/右频道两列）
│   │       ├── GroupAdapter.kt       # 直播分组列（竖排：组名 + 频道数）
│   │       ├── SearchActivity.kt     # 搜索页（承载 SearchFragment 的薄壳）
│   │       ├── SearchFragment.kt     # 搜索（全部站点聚合 / 单站点 + 历史 + 结果网格）
│   │       ├── MineFragment.kt       # 我的（用户卡 + 数据看板 + 功能列表 + 清理/关于）
│   │       ├── DetailActivity.kt     # 详情（头图 + 续播 + 简介展开 + 线路/选集倒序 + 同类推荐）
│   │       ├── PlayerActivity.kt     # 播放器（自定义底栏/设置弹窗/选集面板/下一集/在线字幕/手势/续播/换源/倍速/音轨字幕/跳片头片尾/锁定/画中画/直播）
│   │       ├── HistoryActivity.kt    # 历史与收藏（两页签 + 删除确认）
│   │       ├── SettingsActivity.kt   # 播放偏好/起播跳过/缓存/数据迁移
│   │       ├── Chips.kt              # 统一的胶囊 chip 构建器 + 去 emoji
│   │       └── VodAdapter / ContinueAdapter / RelatedAdapter / ChannelAdapter / GroupAdapter / RecordAdapter
│   ├── python/app.py                 # Python 源桥接模块
│   ├── assets/demo.json              # 内置演示配置
│   ├── res/                          # 布局、主题、自绘矢量图标、底部导航菜单
│   └── test/java/com/xing/tv/        # 单元测试
├── app/build.gradle                  # 含 chaquopy pip 配置与单测配置
├── gradle/wrapper/                   # Gradle 8.13 wrapper
└── keystore/xingying.jks             # 签名密钥
```

---

## 七、单元测试

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:testReleaseUnitTest
# 报告：app\build\reports\tests\testDebugUnitTest\index.html
```

覆盖 100 个用例，分十一类：

| 测试类 | 覆盖点 |
| --- | --- |
| `PlayStringTest` | macCMS `线路A$$$线路B` + `第01集$url#第02集$url` 播放串解析，含畸形片段兜底 |
| `ConfigLoaderTest` | 单仓解析、同 `key` 覆盖、`header`→请求头映射、非法 JSON 兜底、`isPy` 判定、注释/尾逗号/JSONP/顶层数组容错 |
| `VodFilterTest` | 筛选参数名与采集协议一致、空筛选、摘要、候选项 |
| `SubtitleApiTest` | 字幕搜索/详情字段提取、可播放格式判定、错误状态翻译 |
| `SnifferTest` | 直链识别、伪装后缀排除、查询串/锚点不被截断 |
| `LiveParseTest` | m3u 的 `group-title`/`#EXTGRP`/未分组回退、`#genre#` 分组文本与相对地址补全、JSON 两层包装与自动展开、JSON 失败回退文本、m3u `tvg-logo` / JSON `logo` 台标解析（缺失时为空串，不误把源地址当台标） |
| `SiteSupportTest` | 站点类型分级（jar 爬虫 / js 源 / 未知类型判为不可用）、配置可用性统计、网页返回与 BOM 的兜底文案、直播源回环地址识别 |
| `HttpUrlIdnTest` | 中文域名规范化为 punycode——部分 CDN 只认 punycode 的 `Host` 头，收到原始 UTF-8 域名会返回 400 |
| `LocalConfigStoreTest` | 本地配置协议前缀、内容校验（非 JSON / 缺字段 / BOM / stores 多仓 / 顶层数组 / 注释）、文件命名的中文保留与危险字符剔除 |
| `StripEmojiTest` | 直播源/站点名去表情：代理对与 ZWJ 序列、变体选择符、孤立代理不吞掉后续正常字符、中文与全角标点不受影响 |
| `TrackLabelsTest` | 音轨/字幕轨标签的取值次序（名字 > 语种码翻译 > 序号）、多分组时补分组序号、语种码的地区子标签与大小写归一 |

> **工程路径含中文时的已知规避**
> Gradle 8.x 把测试 worker 的 classpath 写入 `@argfile` 时固定用 UTF-8，而 JVM 按系统
> ANSI 代码页（本机 GBK）解析该文件，中文路径会被解成乱码，导致测试类
> `ClassNotFoundException`（上游 [gradle#30304](https://github.com/gradle/gradle/issues/30304)，9.7.0 才修复）。
> `app/build.gradle` 末尾的 `tasks.withType(Test)` 片段会把 classpath 中的非 ASCII 条目
> 镜像到系统临时目录后再注入；镜像目录按**源路径哈希**命名（不能按列表下标——
> debug/release 两个变体都有名为 `kotlin-classes` 的目录，按下标会互相覆盖），
> 且每次构建整体重建。工程路径本身为纯 ASCII 时该逻辑不产生任何改动。
> 除此之外不要改动 `build` 目录位置——APK 构建不受此问题影响。

### targetSdk 保持 34

`compileSdk` / `targetSdk` 均为 **34**，是有意为之：

- Android 15+ 对 `targetSdk ≥ 35` 强制 edge-to-edge，本应用 6 个 Activity 都需补
  WindowInsets 处理，否则工具栏会被状态栏遮挡；侧载分发不受 Play Store target API
  强制约束，该改动只有成本没有收益。
- 可用验证机为 API 33，`targetSdk 35` 的行为无法在本机验证。
- Android 15/16 以兼容模式运行 `targetSdk 34` 完全正常。

如确需升级：先装 SDK 平台 35，再把 `compileSdk`/`targetSdk` 提为 35，
并为 `Main` / `Detail` / `Player` / `History` / `Settings` / `Search`
逐个处理 `WindowInsetsCompat`，之后需重新走一遍全量真机回归。

---

## 八、免责与边界

- 本项目为**独立原创实现**，仅参考公开的 TVBox / macCMS 接口协议格式，
  未包含任何第三方播放器的反编译代码。
- 内置 `assets/demo.json` 中的站点地址均为**占位示例**，不代表可用资源；
  请从首页接口行或「我的 → 接口源」填入你自备且有权访问的接口地址。
- 本工具仅供学习与技术研究，内容获取与使用责任由使用者自行承担。
- 点播仅支持声明 macCMS（`type` 0/1）、T4（`type=4`）或 Python（`type=3` + `py_`）
  的站点。**jar 爬虫（`csp_*`）与 js 源不支持**——它们需要在客户端内加载并执行
  第三方字节码，与本项目不含第三方代码的前提冲突；这类站点在配置加载时即被过滤，
  并由配置诊断说明原因。纯 jar 爬虫的配置仍可正常使用直播模块（除非其直播地址
  指向 TVBox 客户端自身的 `127.0.0.1` 内置代理）。
- 通过「接口源 → 从本地文件导入」的配置会复制到应用私有目录，仅保存在本机，
  不上传任何服务器；删除该接口源时副本一并清理。
