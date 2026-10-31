# Python calls these through Chaquopy's reflection.
-keep class com.ichi2.anki.ankrimento.AnkrimentoHost { public *; }

# AnkrimentoHost reaches these reflectively.
-keepclassmembers class net.ankiweb.rsdroid.Backend {
    byte[] runMethodRaw(int, int, byte[]);
}
-keepclassmembers class net.ankiweb.rsdroid.BackendException {
    private anki.backend.BackendError error;
}
