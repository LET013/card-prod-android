-keepattributes *Annotation*
-keepattributes Signature
-keepattributes SourceFile,LineNumberTable

-keep public class com.xingyao.card.JsBridge {
    public *;
}

-keep public class com.xingyao.card.Jt808Service {
    public *;
}

-keep public class com.xingyao.card.BootReceiver {
    public *;
}

-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

-keepclassmembers class com.xingyao.card.JsBridge {
    public void *();
}

-keep class com.xingyao.card.** { *; }
