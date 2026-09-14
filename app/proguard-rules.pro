# gomobile 生成的 Java 绑定：原生代码按名字回调，一个都不能被混淆或删掉
-keep class go.** { *; }
-keep class com.wpe64.wpc.wpccore.** { *; }
-keep class * implements com.wpe64.wpc.wpccore.LogSink { *; }

# WebView 的消息监听由系统回调
-keep class * implements androidx.webkit.WebViewCompat$WebMessageListener { *; }
