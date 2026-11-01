// The app's "Import banks…" / "Open in": same copy / zip extraction as the
// plugin's Import (EngineCore/PatchLibrary.hpp ImportItems).
#include "EngineCore/PatchLibrary.hpp"

extern "C" const char *OxoSynth2ImportItems(const char *root, const char *paths) {
    static std::string added;
    added = oxo::s2::ImportItemsText(root ? root : "", paths ? paths : "");
    return added.c_str();
}
