# R8 rules for the release build (PRD 20.5: production is compiled with R8 and resource shrinking).
#
# There is deliberately no keep rule here for Room, DataStore or Compose. Each ships consumer
# rules inside its own AAR — Room's `-keep class * extends androidx.room.RoomDatabase` is what
# lets `Room.databaseBuilder` find the generated `_Impl` by reflection — and Mue adds no
# reflection of its own: the dependency container is hand written, the ViewModel factories are
# explicit, and nothing is deserialised by field name. The navigation enum does travel through
# a saved-state Bundle as a Serializable, which R8 cannot see, but the default
# `proguard-android-optimize.txt` already carries `-keepclassmembers enum *`, and that keeps it
# from being unboxed or renamed out from under the restore.
#
# The minified build was installed and exercised end to end before this file was settled —
# save, tab changes, edit, delete, profile save, CSV export and restore after a real process
# kill — and needed nothing beyond the rule below.

# R8 strips these two attributes by default, which leaves a release stack trace as a bare
# obfuscated method with no line number and gives `mapping.txt` nothing to restore. Keeping
# them, while renaming the file attribute to a constant, buys a readable crash report without
# also shipping the source layout.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
