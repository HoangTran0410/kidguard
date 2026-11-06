-keep class dev.kidguard.Admin { *; }
-keepattributes SourceFile,LineNumberTable
# self-setup over Wireless debugging (libadb-android, conscrypt, spake2, sun-security): reflection / JNI
-keep class io.github.muntashirakon.** { *; }
-keep class org.conscrypt.** { *; }
-keep class android.sun.security.** { *; }
-keep class com.github.muntashirakon.** { *; }
-dontwarn com.android.org.conscrypt.SSLParametersImpl
-dontwarn org.apache.harmony.xnet.provider.jsse.SSLParametersImpl
-dontwarn org.conscrypt.**
