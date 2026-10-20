# ===== R8 压缩（不混淆）=====
# 只做「剥离未使用代码 + 资源压缩 + 优化」，不做任何改名/重打包。
# 类名、方法名、字段名保持原样，崩溃栈可读。
# 与 proguard-rules.pro 的区别：这里没有 -repackageclasses / -overloadaggressively /
# -allowaccessmodification / -renamesourcefileattribute，并显式 -dontobfuscate。

-dontobfuscate
# 只做「裁剪（去掉未使用的类/方法/字段）+ 资源压缩」，不做 R8 优化与混淆。
# 优化步骤最耗时，关掉后构建快很多；体积仍能显著减小。
-dontoptimize

# --- 保留调试信息（行号/源文件），崩溃日志可定位 ---
-keepattributes SourceFile,LineNumberTable,LocalVariableTable,LocalVariableTypeTable

# --- 去掉发布版日志与断言（纯体积优化；想去掉日志就留着，想留日志就删掉这两段）---
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
-keepclassmembers class * {
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

# ===== 反射相关保留 =====
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
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,AnnotationDefault,InnerClasses,EnclosingMethod,Signature

# ===== 内网穿透：sshj + BouncyCastle =====
# BouncyCastle 内部按算法名字符串反射加载实现类，必须整体保留。
-keep class org.bouncycastle.** { *; }
-keep class net.i2p.crypto.eddsa.** { *; }
-keep class net.schmizz.** { *; }
-keep class com.hierynomus.** { *; }

-dontwarn net.schmizz.**
-dontwarn org.bouncycastle.**
-dontwarn net.i2p.crypto.**
-dontwarn org.slf4j.**
-dontwarn javax.naming.**
-dontwarn javax.security.**
-dontwarn org.ietf.jgss.**
-dontwarn javax.annotation.**
-dontwarn java.awt.**
