-keepattributes *Annotation*
-keepattributes Signature
-keepattributes SourceFile,LineNumberTable

-keep public class com.xingyao.card.Jt808Service {
    public *;
}

-keep public class com.xingyao.card.BootReceiver {
    public *;
}

-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# libFaceAIModel.so registers FaceAI methods by their original Java names in JNI_OnLoad.
-keep class com.ndk.face.FaceAIModelInterface { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# Paho instantiates this logger reflectively during MQTT client startup.
-keep class org.eclipse.paho.client.mqttv3.logging.JSR47Logger { *; }

-keep class com.xingyao.card.** { *; }
