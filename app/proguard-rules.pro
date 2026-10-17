# ============================================================
# 星影 R8 混淆规则
#
# 两类必须保留的反射面：
#  1. Chaquopy：Python <-> Java 桥接全靠反射，混淆会直接导致 py 源崩溃
#  2. Gson：按字段名反射序列化，字段被改名会静默得到空对象
# ============================================================

-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-keepattributes SourceFile,LineNumberTable

# ---------- Chaquopy（Python 运行时桥接，必须完整保留） ----------
# 官方要求：开启 minify 时须保留 com.chaquo.python 全部类与成员，
# 否则 Python.getInstance()/PyObject.callAttr 等反射调用会失败。
-keep class com.chaquo.python.** { *; }
-keep class com.chaquo.python.android.AndroidPlatform { *; }
-dontwarn com.chaquo.python.**
# 脚本经 Java 反射调用的接口实现须保留
-keepclassmembers class * {
    @com.chaquo.python.PyObject$... <methods>;
}

# ---------- Gson（字段名反射序列化） ----------
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
# 应用自身的数据模型按字段名序列化，不能改名
-keep class com.xing.tv.model.** { *; }
-keep class com.xing.tv.data.HistoryStore$Record { *; }
# 解析 JSON 用到的 JsonObject/JsonArray 属于库内部，无需额外处理
-dontwarn com.google.gson.**

# ---------- OkHttp / Okio ----------
-dontwarn okhttp3.**
-dontwarn okio.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# ---------- Media3 / ExoPlayer ----------
-dontwarn androidx.media3.**
# 播放器回调经反射进 JNI，保留 Player.Listener 实现
-keep class androidx.media3.exoplayer.ExoPlayer { *; }
-keep class * implements androidx.media3.common.Player$Listener { *; }

# ---------- Glide ----------
-dontwarn com.bumptech.glide.**
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule { *; }

# ---------- Kotlin 协程 ----------
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# ---------- 本应用被外部反射/序列化触达的入口 ----------
# Application 子类、Activity 由 manifest 显式引用，无需保留；
# 自定义 View 若后续新增，需在此补充。


# ---------- jar 爬虫（DexClassLoader + 反射） ----------
-keep class com.xing.tv.spider.** { *; }
-keep class com.github.catvod.** { *; }
-dontwarn com.github.catvod.**
