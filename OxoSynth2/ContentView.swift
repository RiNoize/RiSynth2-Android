import SwiftUI
import UniformTypeIdentifiers

/// The Synth2 app is the library manager of the AUv3: banks imported here
/// (file picker, or "Open in Oxo Synth2" from Files / Mail / AirDrop) land in
/// the app-group library the plugin reads. The synth itself runs as an
/// Audio Unit in AUM, Cubasis, GarageBand, Loopy Pro…
struct ContentView: View {
    @State private var banks: [String] = []
    @State private var importing = false
    @State private var message = ""

    private let root = SynthLibrary.appGroupLibraryRoot
    private let name = Bundle.main.object(forInfoDictionaryKey: "CFBundleDisplayName") as? String ?? "Synth2"

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text("\(name) is an Audio Unit instrument: open it in AUM, Cubasis, GarageBand, Loopy Pro or any AUv3 host.")
                    Text("Synth1 banks (folders of .sy1 patches, zip banks, single .sy1 patches) imported here are copied into the library the plugin's bank browser shows. The plugin can import too: preset browser › Import…")
                        .foregroundStyle(.secondary)
                }
                Section("Library") {
                    if banks.isEmpty { Text("No banks yet").foregroundStyle(.secondary) }
                    ForEach(banks, id: \.self) { Text($0) }
                }
                if !message.isEmpty { Section { Text(message).foregroundStyle(.secondary) } }
            }
            .navigationTitle(name)
            .toolbar {
                Button("Import banks…") { importing = true }
            }
        }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.item], allowsMultipleSelection: true) { result in
            if case let .success(urls) = result { importURLs(urls) }
        }
        .onOpenURL { importURLs([$0]) }
        .onAppear(perform: refresh)
    }

    private func importURLs(_ urls: [URL]) {
        let added = SynthLibrary.withAccess(urls) { String(cString: OxoSynth2ImportItems(root.path, $0)) }
            .split(separator: "\n")
        message = added.isEmpty ? "Nothing imported (pick bank folders, zip banks or .sy1 patches)."
            : "Imported \(added.count) item(s)."
        refresh()
    }

    private func refresh() {
        try? FileManager.default.createDirectory(at: root.appendingPathComponent("User"), withIntermediateDirectories: true)
        banks = ((try? FileManager.default.contentsOfDirectory(atPath: root.path)) ?? [])
            .filter { !$0.hasPrefix(".") }
            .sorted { $0.localizedCaseInsensitiveCompare($1) == .orderedAscending }
    }
}
