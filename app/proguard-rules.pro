-keep class com.kongye.ledger.LedgerDb_Impl { *; }

# Room creates WorkManager's database through its no-argument constructor.
# Keep the generated implementation and constructor in optimized release builds.
-keep class androidx.work.impl.WorkDatabase_Impl { *; }

# WorkManager recreates scheduled workers by class name and reflection.
-keep class com.kongye.ledger.WatchWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class com.kongye.ledger.ReviewWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# ML Kit discovers these registrars by class name and calls their no-argument constructors.
-keep class com.google.mlkit.** implements com.google.firebase.components.ComponentRegistrar {
    public <init>();
    public java.util.List getComponents();
}
