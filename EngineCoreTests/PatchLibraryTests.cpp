#include "TestFramework.hpp"

#include "../EngineCore/PatchLibrary.hpp"

#include <cstdlib>

using namespace oxo::s2;
namespace fs = std::filesystem;

namespace {
void WriteFile(const fs::path &p, const std::string &text) { std::ofstream(p, std::ios::binary) << text; }

fs::path FreshDir(const char *name) {
    const fs::path root = fs::temp_directory_path() / name;
    fs::remove_all(root);
    fs::create_directories(root);
    return root;
}
} // namespace

OXO_TEST(SlotNumbersFollowSynth1FileNames) {
    OXO_EXPECT_EQ(SlotFromFileName("001.sy1"), 0);
    OXO_EXPECT_EQ(SlotFromFileName("128.sy1"), 127);
    OXO_EXPECT_EQ(SlotFromFileName("012_Lead.sy1"), 11);
    OXO_EXPECT_EQ(SlotFromFileName("129.sy1"), -1);
    OXO_EXPECT_EQ(SlotFromFileName("1234.sy1"), -1);
    OXO_EXPECT_EQ(SlotFromFileName("Lead.sy1"), -1);
    OXO_EXPECT_EQ(SlotFileName(4), std::string("005.sy1"));
}

// Folder banks, a container folder, loose files in the root, an empty bank;
// zips are not read in place.
OXO_TEST(PatchLibraryFoldersContainers) {
    const fs::path root = FreshDir("oxo_s2_patchlib_test");
    fs::create_directories(root / "MyBank");
    fs::create_directories(root / "Sets" / "A");
    fs::create_directories(root / "Sets" / "B");
    fs::create_directories(root / "Empty");
    WriteFile(root / "MyBank" / "005.sy1", "Fifth\nver=112\n19,10\n");
    WriteFile(root / "MyBank" / "Named.sy1", "Named\nver=112\n19,99\n");
    WriteFile(root / "Sets" / "A" / "001.sy1", "Set A\nver=112\n0,3\n");
    WriteFile(root / "Sets" / "B" / "001.sy1", "Set B\nver=112\n");
    WriteFile(root / "Loose.sy1", "Loose\nver=112\n");
    WriteFile(root / "Bank.zip", "not read");

    PatchLibrary lib;
    lib.Scan(root);
    const auto &banks = lib.Banks();
    std::vector<std::string> names;
    for (const auto &b : banks) names.push_back(b.name);
    OXO_EXPECT_EQ(banks.size(), size_t(5));
    if (banks.size() != 5) return;
    OXO_EXPECT_EQ(names[0], root.filename().string()); // loose root files
    OXO_EXPECT_EQ(names[1], std::string("Empty"));
    OXO_EXPECT_EQ(names[2], std::string("MyBank"));
    OXO_EXPECT_EQ(names[3], std::string("Sets / A"));
    OXO_EXPECT_EQ(names[4], std::string("Sets / B"));
    OXO_EXPECT_EQ(banks[1].Count(), 0);
    // numbered file keeps its slot, others fill the first free slot
    OXO_EXPECT_EQ(banks[2].slots[4].name, std::string("Fifth"));
    OXO_EXPECT_EQ(banks[2].slots[0].name, std::string("Named"));
    OXO_EXPECT_EQ(lib.SlotFile(2, 4), (root / "MyBank" / "005.sy1").string());
    OXO_EXPECT(lib.SlotFile(2, 5).empty());

    Sy1Patch p;
    OXO_EXPECT(lib.Load(3, 0, p));
    OXO_EXPECT_EQ(p.name, std::string("Set A"));
    OXO_EXPECT_EQ(p.values[kOsc1Shape], 3);
    OXO_EXPECT(!lib.Load(3, 1, p)); // empty slot
    fs::remove_all(root);
}

OXO_TEST(PatchManagerWriteNewBank) {
    const fs::path base = FreshDir("oxo_s2_patchmgr_test");
    const fs::path root = base / "Synth2";
    PatchManager m;
    m.Configure(root);
    m.Rescan();
    const auto &banks = m.Library().Banks();
    OXO_EXPECT_EQ(banks.size(), size_t(1));
    if (banks.size() != 1) return;
    OXO_EXPECT_EQ(banks[0].name, std::string("User")); // created
    WriteFile(root / "User" / "Old.sy1", "Old\nver=112\n");
    m.Rescan();

    // write = slot NNN.sy1; overwriting a differently named file replaces it
    Sy1Patch p;
    p.name = "Written";
    p.values[kFilterFreq] = 42;
    OXO_EXPECT(m.Write(0, 0, p));
    OXO_EXPECT(fs::exists(root / "User" / "001.sy1"));
    OXO_EXPECT(!fs::exists(root / "User" / "Old.sy1"));
    OXO_EXPECT(m.Write(0, 9, p));
    Sy1Patch back;
    OXO_EXPECT(m.Load(0, 9, back));
    OXO_EXPECT_EQ(back.name, std::string("Written"));
    OXO_EXPECT_EQ(back.values[kFilterFreq], 42);
    OXO_EXPECT_EQ(m.SlotFile(0, 9), (root / "User" / "010.sy1").string());

    const int fresh = m.NewBank("Pads");
    OXO_EXPECT(fresh >= 0 && m.Library().Banks()[size_t(fresh)].name == "Pads");
    OXO_EXPECT_EQ(m.NewBank("Pads"), fresh + 1); // "Pads 2"
    OXO_EXPECT(m.Json().find("\"Pads 2\"") != std::string::npos);
    fs::remove_all(base);
}

// Import copies: a Synth1 bank folder (bank folders + zipbank/ zips, one
// zip stored, one deflated with a wrapper folder and __MACOSX junk), a lone
// zip with two inner folders, loose .sy1 files into "Imported".
OXO_TEST(ImportCopiesFoldersExtractsZips) {
    const fs::path base = FreshDir("oxo_s2_import_test");
    const fs::path root = base / "Synth2", src = base / "src", s1 = src / "Synth1Patches";
    fs::create_directories(s1 / "Dark" / "sub");
    fs::create_directories(s1 / "zipbank");
    fs::create_directories(s1 / "Nothing");
    fs::create_directories(src / "zip" / "Wrap" / "__MACOSX");
    fs::create_directories(src / "zip2" / "A");
    fs::create_directories(src / "zip2" / "B");
    WriteFile(s1 / "Dark" / "001.sy1", "Dark 1\nver=112\n");
    WriteFile(s1 / "Dark" / "readme.txt", "memo");
    WriteFile(s1 / "Dark" / "sub" / "001.sy1", "Sub 1\nver=112\n");
    WriteFile(src / "zip" / "Wrap" / "001.sy1", "Zip 1\nver=112\n");
    WriteFile(src / "zip" / "Wrap" / "002.sy1", "Zip 2\nver=112\n");
    WriteFile(src / "zip" / "Wrap" / "__MACOSX" / "._001.sy1", "junk");
    WriteFile(src / "zip2" / "A" / "001.sy1", "A 1\nver=112\n");
    WriteFile(src / "zip2" / "B" / "003.sy1", "B 3\nver=112\n");
    WriteFile(src / "Loose.sy1", "Loose\nver=112\n");
    WriteFile(src / "Other.sy1", "Other\nver=112\n");
    const std::string cmd = "cd '" + (src / "zip").string() + "' && zip -q -r '" + (s1 / "zipbank" / "Dark.zip").string() +
                            "' Wrap && cd '" + (src / "zip2").string() + "' && zip -q -0 -r ../Two.zip A B";
    OXO_EXPECT(std::system(cmd.c_str()) == 0);

    PatchManager m;
    m.Configure(root);
    m.Write(0, 0, Sy1Patch{}); // Imported must not clobber anything
    const std::string added = m.Import(s1.string() + "\n" + (src / "Two.zip").string() + "\n" + (src / "Loose.sy1").string() +
                                       "\n" + (src / "Other.sy1").string() + "\n" + (src / "zip2" / "A").string() + "\n" +
                                       root.string() + "\n" + (src / "zip2").string() + "/");
    OXO_EXPECT_EQ(added, (root / "Synth1Patches").string() + "\n" + (root / "Two").string() + "\n" + (root / "A").string() +
                             "\n" + (root / "zip2").string() + "\n" + (root / "Imported").string());
    OXO_EXPECT(fs::exists(root / "Synth1Patches" / "Dark" / "001.sy1"));
    OXO_EXPECT(!fs::exists(root / "Synth1Patches" / "Dark" / "readme.txt"));
    OXO_EXPECT(!fs::exists(root / "Synth1Patches" / "Nothing"));
    OXO_EXPECT(!fs::exists(root / "Synth1Patches" / "zipbank"));
    OXO_EXPECT(fs::exists(root / "Synth1Patches" / "Dark 2" / "002.sy1")); // zipbank zip, wrapper dropped
    OXO_EXPECT(!fs::exists(root / "Synth1Patches" / "Dark 2" / "__MACOSX"));
    OXO_EXPECT(fs::exists(root / "Two" / "B" / "003.sy1"));
    OXO_EXPECT(fs::exists(root / "Imported" / "001.sy1") && fs::exists(root / "Imported" / "002.sy1"));

    std::vector<std::string> names;
    for (const auto &b : m.Library().Banks()) names.push_back(b.name + ":" + std::to_string(b.Count()));
    const std::vector<std::string> want = {"A:1", "Imported:2", "Synth1Patches / Dark:1", "Synth1Patches / Dark / sub:1",
                                           "Synth1Patches / Dark 2:2", "Two / A:1", "Two / B:1", "User:1",
                                           "zip2 / A:1", "zip2 / B:1"};
    OXO_EXPECT(names == want);

    // a second import of loose files fills the next free slots
    m.Import((src / "Loose.sy1").string());
    OXO_EXPECT(fs::exists(root / "Imported" / "003.sy1"));
    OXO_EXPECT(m.Import((src / "Nothing.zip").string()).empty());
    fs::remove_all(base);
}

// A zip put into the library (root or a bank folder) is extracted next to
// itself on the next rescan, then trashed; a zip without patches stays.
OXO_TEST(RescanExtractsDroppedZips) {
    const fs::path base = FreshDir("oxo_s2_dropzip_test");
    const fs::path root = base / "Synth2", src = base / "src";
    fs::create_directories(src / "Wrap");
    WriteFile(src / "Wrap" / "001.sy1", "Zip 1\nver=112\n");
    const std::string cmd = "cd '" + src.string() + "' && zip -q -r Bank.zip Wrap";
    OXO_EXPECT(std::system(cmd.c_str()) == 0);
    PatchManager m;
    m.Configure(root);
    std::vector<fs::path> trashed;
    m.SetTrash([&](const fs::path &p) {
        trashed.push_back(p);
        return fs::remove(p);
    });
    fs::copy_file(src / "Bank.zip", root / "Bank.zip");
    fs::copy_file(src / "Bank.zip", root / "User" / "Extra.zip");
    WriteFile(root / "Junk.zip", "not a zip");
    m.Rescan();
    OXO_EXPECT_EQ(trashed.size(), size_t(2));
    OXO_EXPECT(fs::exists(root / "Bank" / "001.sy1") && fs::exists(root / "User" / "Extra" / "001.sy1"));
    OXO_EXPECT(!fs::exists(root / "Bank.zip") && fs::exists(root / "Junk.zip"));
    OXO_EXPECT_EQ(m.TakeNotice(), std::string("Bank.zip, Extra.zip imported and moved to Trash"));
    OXO_EXPECT(m.TakeNotice().empty());
    std::vector<std::string> names;
    for (const auto &b : m.Library().Banks()) names.push_back(b.name);
    OXO_EXPECT(names == (std::vector<std::string>{"Bank", "User / Extra"})); // User = container now
    // no Trash: the zip is deleted
    m.SetTrash(nullptr);
    fs::copy_file(src / "Bank.zip", root / "Bank.zip");
    m.Rescan();
    OXO_EXPECT(fs::exists(root / "Bank 2" / "001.sy1") && !fs::exists(root / "Bank.zip"));
    OXO_EXPECT_EQ(m.TakeNotice(), std::string("Bank.zip imported (zip removed)"));
    fs::remove_all(base);
}

OXO_TEST(JsonStringKeepsUtf8ReplacesLegacyBytes) {
    OXO_EXPECT_EQ(JsonString("Pad \xC3\xA9t\xC3\xA9"), std::string("\"Pad \xC3\xA9t\xC3\xA9\""));
    OXO_EXPECT_EQ(JsonString("Pad \xE9t\xE9"), std::string("\"Pad ?t?\""));
    OXO_EXPECT_EQ(JsonString("a\"b\\c"), std::string("\"a\\\"b\\\\c\""));
}

// A fresh instance's patch: the first patch of the first bank holding one
// (empty banks skipped), at the same bank index as a full scan.
OXO_TEST(FirstProgramIsTheFirstPatchOfTheFirstBank) {
    const fs::path root = FreshDir("oxo_s2_first_program");
    fs::create_directories(root / "Empty");
    fs::create_directories(root / "Leads");
    fs::create_directories(root / "Sets" / "A");
    WriteFile(root / "Leads" / "003.sy1", "Third\nver=112\n19,10\n");
    WriteFile(root / "Leads" / "007.sy1", "Seventh\nver=112\n");
    WriteFile(root / "Sets" / "A" / "001.sy1", "Set A\nver=112\n");
    Sy1Patch patch;
    LibraryProgram where;
    OXO_EXPECT(FirstProgram(root, {}, patch, where));
    OXO_EXPECT_EQ(patch.name, std::string("Third"));
    OXO_EXPECT_EQ(patch.values[19], 10);
    OXO_EXPECT_EQ(where.bank, std::string("Leads"));
    OXO_EXPECT_EQ(where.slot, 2);
    PatchLibrary lib;
    lib.Scan(root);
    OXO_EXPECT_EQ(lib.FindBySource(BankAbsolute(root, where.bank)), 1);
    // nested bank, relative path round trip; root's loose patches = ""
    OXO_EXPECT_EQ(BankRelative(root, (root / "Sets" / "A").string()), std::string("Sets/A"));
    OXO_EXPECT_EQ(BankRelative(root, root.string()), std::string());
    OXO_EXPECT_EQ(BankAbsolute(root, ""), root.string());
    // empty library: nothing
    const fs::path empty = FreshDir("oxo_s2_first_program_empty");
    fs::create_directories(empty / "User");
    OXO_EXPECT(!FirstProgram(empty, {}, patch, where));
    OXO_EXPECT(!FirstProgram(root / "missing", {}, patch, where));
}

// The factory bank (plugin bundle): listed first, read-only, the startup
// program, a path-free program bank; a missing factory folder = no bank.
OXO_TEST(FactoryBankIsFirstAndReadOnly) {
    const fs::path base = FreshDir("oxo_s2_factory_test");
    const fs::path root = base / "Synth2", factory = base / "Bundle" / kFactoryBankName;
    fs::create_directories(factory);
    WriteFile(factory / "001.sy1", "INIT\nver=112\n");
    WriteFile(factory / "002.sy1", "Pad\nver=112\n");
    PatchManager m;
    m.Configure(root, factory);
    m.Rescan();
    const auto &banks = m.Library().Banks();
    OXO_EXPECT_EQ(banks.size(), size_t(2));
    if (banks.size() != 2) return;
    OXO_EXPECT_EQ(banks[0].name, std::string(kFactoryBankName));
    OXO_EXPECT(banks[0].readOnly && !banks[1].readOnly);
    OXO_EXPECT(!m.Write(0, 5, Sy1Patch{}));
    OXO_EXPECT(!fs::exists(factory / "006.sy1"));
    OXO_EXPECT(!m.Move(0, 0, 5));
    OXO_EXPECT(fs::exists(factory / "001.sy1"));
    OXO_EXPECT(m.Json().find("\"readOnly\":true") != std::string::npos);
    OXO_EXPECT_EQ(ProgramBank(m.Library(), root, 0), std::string(kFactoryProgramBank));
    OXO_EXPECT_EQ(FindProgramBank(m.Library(), root, kFactoryProgramBank), 0);
    OXO_EXPECT_EQ(FindProgramBank(m.Library(), root, "User"), 1);

    Sy1Patch patch;
    LibraryProgram where;
    OXO_EXPECT(FirstProgram(root, factory, patch, where));
    OXO_EXPECT_EQ(patch.name, std::string("INIT"));
    OXO_EXPECT(where == (LibraryProgram{kFactoryProgramBank, 0}));

    PatchManager none;
    none.Configure(root, base / "missing");
    none.Rescan();
    OXO_EXPECT_EQ(none.Library().Banks().size(), size_t(1));
    OXO_EXPECT_EQ(FindProgramBank(none.Library(), root, kFactoryProgramBank), -1);
    fs::remove_all(base);
}

// Starter banks (next to the factory bank) are copied into the library once:
// a deleted / edited copy stays so, a new starter bank is added later, a
// folder of the same name already in the library is left alone.
OXO_TEST(StarterBanksAreSeededOnce) {
    const fs::path base = FreshDir("oxo_s2_starter_test");
    const fs::path root = base / "Synth2", factory = base / "Bundle" / kFactoryBankName,
                   starter = base / "Bundle" / kStarterBanksFolder;
    for (const fs::path &d : {factory, starter / "Empty", starter / "A", starter / "B", starter / "C", starter / "D", root / "D"})
        fs::create_directories(d);
    WriteFile(starter / "A" / "001.sy1", "A1\nver=112\n");
    WriteFile(starter / "A" / "002.sy1", "A2\nver=112\n");
    WriteFile(starter / "A" / "notes.txt", "x");
    WriteFile(starter / "B" / "001.sy1", "B1\nver=112\n");
    WriteFile(starter / "D" / "001.sy1", "D1\nver=112\n");
    WriteFile(root / "D" / "001.sy1", "Mine\nver=112\n");
    PatchManager m;
    m.Configure(root, factory);
    OXO_EXPECT(fs::exists(root / "A" / "002.sy1") && fs::exists(root / "B" / "001.sy1"));
    OXO_EXPECT(!fs::exists(root / "A" / "notes.txt") && !fs::exists(root / "Empty"));
    std::string text;
    ReadTextFile(root / "D" / "001.sy1", text);
    OXO_EXPECT_EQ(text, std::string("Mine\nver=112\n"));
    m.Rescan();
    OXO_EXPECT_EQ(m.Library().Banks().size(), size_t(4)); // factory, A, B, D (C comes later)

    fs::remove_all(root / "A");
    WriteFile(root / "B" / "001.sy1", "Edited\nver=112\n");
    WriteFile(starter / "C" / "001.sy1", "C1\nver=112\n");
    PatchManager again;
    again.Configure(root, factory);
    OXO_EXPECT(!fs::exists(root / "A"));
    ReadTextFile(root / "B" / "001.sy1", text);
    OXO_EXPECT_EQ(text, std::string("Edited\nver=112\n"));
    OXO_EXPECT(fs::exists(root / "C" / "001.sy1"));
    for (const auto &e : fs::directory_iterator(root))
        OXO_EXPECT(e.path().filename().string().rfind(".oxo_seed_", 0) != 0);
    OXO_EXPECT_EQ(SeedStarterBanks(root, starter), 0);
    fs::remove_all(base);
}

// Browser drag and drop: move to an empty slot, swap with an occupied one;
// files are renamed NNN.sy1 so an unnumbered one can't drift into a hole.
OXO_TEST(PatchManagerMoveRenamesAndSwaps) {
    const fs::path root = FreshDir("oxo_s2_move_test");
    fs::create_directories(root / "Bank");
    WriteFile(root / "Bank" / "003.sy1", "Third\nver=112\n");
    WriteFile(root / "Bank" / "Named.sy1", "Named\nver=112\n"); // fills slot 0
    PatchManager m;
    m.Configure(root);
    const auto names = [&] {
        std::vector<std::string> n;
        for (const auto &p : m.Library().Banks()[0].slots) n.push_back(p.name);
        return n;
    };
    OXO_EXPECT(m.Move(0, 2, 5)); // 003 -> 006 (empty)
    auto n = names();
    OXO_EXPECT_EQ(n[0], std::string("Named"));
    OXO_EXPECT_EQ(n[2], std::string());
    OXO_EXPECT_EQ(n[5], std::string("Third"));
    OXO_EXPECT(fs::exists(root / "Bank" / "006.sy1"));
    OXO_EXPECT(fs::exists(root / "Bank" / "001.sy1")); // the unnumbered one pinned to its slot
    OXO_EXPECT(m.Move(0, 0, 5)); // swap
    n = names();
    OXO_EXPECT_EQ(n[0], std::string("Third"));
    OXO_EXPECT_EQ(n[5], std::string("Named"));
    OXO_EXPECT(!m.Move(0, 3, 4)); // empty source
    int files = 0;
    for ([[maybe_unused]] const auto &e : fs::directory_iterator(root / "Bank")) ++files;
    OXO_EXPECT_EQ(files, 2); // no temp left behind
}
