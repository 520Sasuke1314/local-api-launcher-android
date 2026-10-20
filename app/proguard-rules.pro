# ===== R8 强混淆 + 剥离调试信息 =====
# 目标：源码不可读、行号/源文件不可查、类名全部重排。

# --- 移除调试信息：不保留行号、源文件名强制改名 ---
-renamesourcefileattribute SourceFile
-keepattributes !SourceFile,!LineNumberTable,!LocalVariableTable,!LocalVariableTypeTable

# --- 激进混淆：允许改访问修饰符、重载、把所有类压到单一包 ---
-allowaccessmodification
-overloadaggressively
-repackageclasses 'o'

# --- 去掉发布版日志与断言（含 android.util.Log 与 kotlin 断言）---
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
    public static *** wtf(...);
}
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    public static void checkNotNullParameter(...);
    public static void checkExpressionValueIsNotNull(...);
    public static void checkNotNullExpressionValue(...);
    public static void checkNotNull(...);
    public static void throwUninitializedPropertyAccessException(...);
    public static void checkFieldIsNotNull(...);
}

# ===== 需要保留的成员 =====

# --- Gson：数据类靠反射读写，字段名即 JSON key ---
-keep class com.dslauncher.data.remote.model.** { *; }
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-dontwarn com.google.gson.**

# --- OkHttp / Okio ---
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# --- Kotlin 协程 ---
-dontwarn kotlinx.coroutines.**

# --- org.json：官网客户端大量动态使用 ---
-keep class org.json.** { *; }

# --- 应用入口与清单中声明的组件 ---
-keep class com.dslauncher.App { *; }
-keep class com.dslauncher.MainActivity { *; }
-keep class com.dslauncher.service.ApiService { *; }

# --- 本地 API 服务：SSE 帧用 JSONObject 动态构造 ---
-keep class com.dslauncher.data.localapi.** { *; }

# --- 签名校验器：通过 BuildConfig 与自身反射调用，必须保留 ---
-keep class com.dslauncher.security.SignatureVerifier { *; }

# ===== 加固补充：收紧反射面 =====
# 保留自定义 View/Composable 入口方法名（Compose 反射查找 @Composable）
-keepclassmembers class * {
    @androidx.compose.runtime.Composable <methods>;
}
# 保留枚举 valueOf/values（Gson、反射序列化需要）
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
# 保留 Parcelable CREATOR
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}
# 保留 R 资源字段访问
-keepclassmembers class **.R$* {
    public static <fields>;
}
# 保留 Native 方法绑定
-keepclasseswithmembernames class * {
    native <methods>;
}
# 不再保留注解（缩小体积+减少信息泄露），仅保留运行时可见的必要注解
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,AnnotationDefault,InnerClasses,EnclosingMethod,Signature

# ===== 加固：保持强混淆（不要加 -dontobfuscate）=====
# 说明：类名/方法名混淆由 R8 默认开启，配合上方 -repackageclasses 'o' 已最激进。
# 目标：崩溃栈帧里不出现可读方法名、类名全部压到 o.* 包。

# ===== 内网穿透：sshj + BouncyCastle =====
# BouncyCastle 内部是「按算法名字符串反射加载实现类」的，R8 看不出来，
# 一旦被裁剪，运行期就是 NoSuchAlgorithmException / NoClassDefFoundError。
# 所以 BC 必须整体保留（代价是体积 +几 MB，值得）。
-keep class org.bouncycastle.** { *; }
-keep class net.i2p.crypto.eddsa.** { *; }
-keep class net.schmizz.** { *; }
-keep class com.hierynomus.** { *; }

# sshj 会碰一些 Android 上没有的 JDK 类（GSSAPI / JNDI / NIO 可选件），静默忽略即可
-dontwarn net.schmizz.**
-dontwarn org.bouncycastle.**
-dontwarn net.i2p.crypto.**
-dontwarn org.slf4j.**
-dontwarn javax.naming.**
-dontwarn javax.security.**
-dontwarn org.ietf.jgss.**
-dontwarn javax.annotation.**
-dontwarn java.awt.**
