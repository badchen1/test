# gomobile 生成的类通过 JNI 反射调用，需保留其公开成员。
-keep class gocore.** { *; }
-keepclassmembers class gocore.** { *; }

# gomobile 运行时的 JNI 桥接
-keep class go.** { *; }

# Kotlin 数据类用于 Compose 状态持有
-keep class com.kugo.mobile.** { *; }
