# Retrofit / Gson 泛型解析规则
-keepattributes Signature
-keepattributes *Annotation*

# Gson 使用的模型类
-keep class com.ashchan.cloudsaver.drive.** { *; }
-keep class com.ashchan.cloudsaver.core.model.** { *; }

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
