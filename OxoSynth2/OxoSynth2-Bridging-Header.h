// The app compiles Shared/ too (HooksSynthHost needs the hooks type).
#import "../EditorCore/OxoSynth2Bridge.h"

// LibraryImport.cpp: copies folders / extracts zips / loose .sy1 into the
// library; newline-separated paths in, new top-level folders out.
const char *OxoSynth2ImportItems(const char *root, const char *paths);
