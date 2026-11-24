# Retrofit / Gson 泛型解析规则
-keepattributes Signature
-keepattributes *Annotation*

# Gson 使用的模型类
-keep class com.ashchan.cloudsaver.drive.** { *; }
-keep class com.ashchan.cloudsaver.core.model.** { *; }
# TMDB 响应模型（TmdbTvInfo / TmdbEpisode）：字段名就是 JSON key（last_episode_to_air 等），
# 被混淆后 Gson 找不到对应字段，最新集会静默变 0 —— 必须 keep
-keep class com.ashchan.cloudsaver.core.tmdb.** { *; }

# security-crypto -> tink 引用的可选注解，运行时不需要
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**

# Gson + R8 full mode：必须保留 TypeToken 及其子类的泛型签名，
# 否则运行时报 "TypeToken must be created with a type argument"（光鸭 adapter 初始化崩溃）
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-keepattributes Signature, InnerClasses, EnclosingMethod, RuntimeVisibleAnnotations, AnnotationDefault

# Retrofit：保留接口方法上的注解（GET/POST/Path/Body 等）
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.codehaus.mojo.animal_sniffer.*

# 自动刷新：Worker 由 WorkManager 按类名反射实例化，R8 改名后可能找不到
-keep class com.ashchan.cloudsaver.sync.AutoSyncWorker { *; }
