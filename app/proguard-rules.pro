# Release shrinking (R8). Keep rules for everything reached by name from outside the code.

# Torque Pro plugin AIDL: Torque binds org.prowl.torque.remote.ITorqueService; the Stub/Proxy
# classes, the interface descriptor and all method names/signatures must survive.
-keep class org.prowl.torque.remote.** { *; }
-keep interface org.prowl.torque.remote.** { *; }

# Manifest entry points (AAPT also generates keep rules for these; explicit for safety, incl. the
# names Torque's intents and our broadcasts use).
-keep class com.davidlang.torque.fuelsmoothing.PluginService { <init>(); }
-keep class com.davidlang.torque.fuelsmoothing.PluginReceiver { <init>(); }
-keep class com.davidlang.torque.fuelsmoothing.PluginActivity { <init>(); }

# Enum names are persisted in prefs/profile.json (FeedMode, MapAlgo, VolUnit...): keep the constant names.
-keepclassmembers,allowoptimization enum com.davidlang.torque.fuelsmoothing.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    <fields>;
}

# JSON is hand-written with org.json/regex (no reflection, no Gson/Moshi): nothing else to keep.
# Google Play Billing ships its own consumer ProGuard rules inside the AAR.

# Readable crash traces.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
