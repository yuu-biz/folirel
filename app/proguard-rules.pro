# App-specific R8 rules for the release build. There are none yet: every library on the runtime classpath ships the rules
# its own reflection needs, and the app itself instantiates nothing by name.
# - Room: room-runtime keeps `* extends RoomDatabase` with its no-argument constructor (AppDatabase_Impl is found by name).
# - WorkManager: work-runtime keeps the names and public constructors of ListenableWorker subclasses (RefreshWorker,
#   created by the default WorkerFactory) and WorkerParameters.
# - DataStore: datastore-preferences-core keeps the fields of its shaded protobuf messages; datastore-core keeps its JNI class.
# - OkHttp, Coil, jsoup, kotlinx-coroutines: their rules only silence optional platform classes, which the app does not use.
# - kotlinx-serialization: the app only reads and writes JsonElement trees, no @Serializable classes, so nothing is looked up.
# - Enum valueOf() on stored names is covered by proguard-android-optimize.txt.
# R8 reported no missing classes (no build/outputs/mapping/release/missing_rules.txt). Add a rule here only with the reason.
