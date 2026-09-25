# Nordic mesh lib uses Gson + reflection on model classes
-keep class no.nordicsemi.android.mesh.** { *; }
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.TypeAdapter
-keepattributes Signature, *Annotation*
-dontwarn org.spongycastle.**
-dontwarn javax.lang.model.element.Modifier

# local keyed builds: BakedKeys is only reachable via reflection
-keep class com.example.bulb.BakedKeys { *; }
