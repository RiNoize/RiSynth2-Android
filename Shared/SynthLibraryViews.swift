import SwiftUI
import UniformTypeIdentifiers
#if os(macOS)
import AppKit
#endif

private let sy1Type = UTType(filenameExtension: "sy1") ?? .data

/// Synth1's bottom strip: the program LCD (patch on line 1, bank on line 2,
/// transient value / MIDI learn messages over the bank), write / menu.
struct BottomBar: View {
    @ObservedObject var model: SynthModel
    @Environment(\.theme) private var theme

    var body: some View {
        ZStack(alignment: .topLeading) {
            // Synth1's "vol": master volume, not part of the patch (session only).
            KnobView(model: model, id: SynthModel.volumeID, label: "vol", size: 26).place(0, 12, 38, 46)
            // write / menu end at the Wheel / MIDI section's right edge (x 542 on the panel)
            lcd.place(44, 0, 432, 70)
            // write: opens the write dialog; a 2nd click (dialog open) writes
            // like its Write / Overwrite button; Alt-click writes straight
            // back to the loaded program (Synth1's quick save).
            PanelButton(title: "write", width: 50, height: 26, fill: Theme.red) {
                if model.showBrowser && model.writing {
                    model.writeCommits += 1
                } else if !(optionHeld() && model.writeLoadedProgram()) {
                    model.openBrowser(write: true)
                }
            }
                .place(486, 4, 50, 26)
            mainMenu.place(486, 38, 50, 26)
            if !model.learnActive {
                // Spans the Voice section above (x 548..764 on the panel),
                // centred on the gap between write and menu.
                VoiceGrid(activity: model.voices, polyphony: model.values[94])
                    .place(758 - VoiceGrid.size.width, 34 - VoiceGrid.size.height / 2, VoiceGrid.size.width, VoiceGrid.size.height)
            } else {
                Button(action: { model.exitLearn() }) {
                    Text("Exit MIDI Learn").font(.system(size: 12, weight: .semibold))
                        .foregroundColor(Color(red: 0.95, green: 0.87, blue: 0.87))
                        .frame(width: 200, height: 28)
                        .background(RoundedRectangle(cornerRadius: 4).fill(Theme.red))
                        .overlay(RoundedRectangle(cornerRadius: 4).strokeBorder(Color.black.opacity(0.6), lineWidth: 1))
                }
                .buttonStyle(.plain)
                .place(540, 4, 200, 28)
                Text("Click a control, then move a controller knob.\nDouble-click a control to clear its CC.")
                    .font(.system(size: 9.5)).foregroundColor(theme.dimInk).fixedSize()
                    .place(540, 36, 210, 30)
            }
        }
    }

    private var programPrefix: String {
        model.currentProgram.map { String(format: "%03d:", $0 + 1) } ?? "---:"
    }

    private var bankLine: String {
        guard let b = model.loadedBank ?? model.currentBank, b < model.banks.count else {
            return "click here to browse banks"
        }
        return String(format: "%02d:%@", b, model.banks[b].name)
    }

    private var lcd: some View {
        ZStack(alignment: .topLeading) {
            // Synth1: a click on the display opens the patch browser.
            RoundedRectangle(cornerRadius: 4)
                .fill(LinearGradient(colors: [theme.lcdTop, theme.lcdBottom], startPoint: .top, endPoint: .bottom))
                .overlay(RoundedRectangle(cornerRadius: 4).strokeBorder(Color.black, lineWidth: 1.5))
                .onTapGesture { toggleBrowser() }
            Text(programPrefix + model.patchName)
                .font(.system(size: 17, weight: .semibold, design: .monospaced))
                .foregroundColor(Theme.lcdText)
                .lineLimit(1)
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
                .onTapGesture { toggleBrowser() }
                .place(10, 6, 352, 26)
            HStack(spacing: 4) {
                PanelButton(title: "◀", width: 24, height: 20) { model.step(-1) }
                PanelButton(title: "▶", width: 24, height: 20) { model.step(1) }
            }
            .place(372, 9, 52, 20)
            Text(model.readout ?? bankLine)
                .font(.system(size: 13, weight: .medium, design: .monospaced))
                .foregroundColor(model.readout == nil ? Theme.lcdText.opacity(0.85) : theme.displayText)
                .lineLimit(1)
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
                .onTapGesture { toggleBrowser() }
                .place(10, 38, 412, 24)
        }
    }

    private func toggleBrowser() {
        if model.showBrowser { model.closeBrowser() } else { model.openBrowser() }
    }

    private var mainMenu: some View {
        Menu {
            // Library chores (Import…, library folder, Refresh) live in the
            // browser's header; write = the red button.
            Button("Initialize patch") { model.initializePatch() }
            Divider()
            Button("Load patch file…") { model.fileAction = .loadPatch }
            Button("Save patch file…") { model.fileAction = .savePatch }
            Divider()
            Button(model.learnActive ? "Exit MIDI Learn" : "MIDI Learn") { model.toggleLearn() }
            Button("All notes off") { model.allNotesOff() }
            Divider()
            Button("Themes…") { model.showThemes = true }
            #if os(macOS)
            if UpdateCheck.enabled {
                Divider()
                Button("Check for updates…") { model.checkForUpdates() }
            }
            #endif
        } label: {
            // .button + .plain: the label is drawn as-is (centred), not
            // re-laid out by the borderless menu style.
            Text("menu").font(.system(size: 11, weight: .semibold)).foregroundColor(Theme.buttonText)
                .frame(width: 50, height: 26)
                .background(ButtonFace())
                .contentShape(Rectangle())
        }
        .menuStyle(.button)
        .buttonStyle(.plain)
        .menuIndicator(.hidden)
        .frame(width: 50, height: 26)
    }
}

/// Synth1's voice activity squares: 2 x 16 cells = the 32 voices, black =
/// available (within the polyphony), white = held, gray = releasing, empty
/// = past the polyphony.
struct VoiceGrid: View {
    @ObservedObject var activity: VoiceActivity
    @Environment(\.theme) private var theme
    let polyphony: Int
    /// Exactly the Voice section's width (216 incl. the outer lines).
    static let columns = 16, rows = 2, cell: CGFloat = 215.0 / 16
    static let size = CGSize(width: cell * CGFloat(columns) + 1, height: cell * CGFloat(rows) + 1)

    var body: some View {
        let states = activity.states, poly = polyphony
        Canvas { ctx, _ in
            let c = Self.cell
            for i in 0..<Self.columns * Self.rows {
                let rect = CGRect(x: 0.5 + CGFloat(i % Self.columns) * c, y: 0.5 + CGFloat(i / Self.columns) * c, width: c, height: c)
                let s = states >> (2 * UInt64(i)) & 3
                // inset 1 pt: a 2 pt gap (the line + half a point each side) between filled cells
                if let fill = s == 2 ? Color.white : s == 1 ? Color(white: 0.55) : i < poly ? Color.black : nil {
                    ctx.fill(Path(rect.insetBy(dx: 1, dy: 1)), with: .color(fill))
                }
            }
            var lines = Path()
            for x in 0...Self.columns {
                lines.move(to: CGPoint(x: 0.5 + CGFloat(x) * c, y: 0))
                lines.addLine(to: CGPoint(x: 0.5 + CGFloat(x) * c, y: Self.size.height))
            }
            for y in 0...Self.rows {
                lines.move(to: CGPoint(x: 0, y: 0.5 + CGFloat(y) * c))
                lines.addLine(to: CGPoint(x: Self.size.width, y: 0.5 + CGFloat(y) * c))
            }
            ctx.stroke(lines, with: .color(theme.ink.opacity(0.12)), lineWidth: 1)
        }
        .frame(width: Self.size.width, height: Self.size.height)
        .allowsHitTesting(false)
    }
}

/// The patch browser over the panel (bank list + 128-slot grid, a click
/// loads and the browser stays open for auditioning, a double click loads
/// and closes, drag and drop moves / swaps patches) and, with
/// `model.writing`, Synth1's write dialog: pick a bank (or make a new one)
/// and a slot, name the patch, Write (an occupied slot says "Overwrite").
/// Banks are the folders of the library: Import… copies into it, the rest
/// (rename, share) happens in Finder (right click > Show in Finder); right
/// click > Delete moves a patch file to the Trash.
struct PatchBrowserView: View {
    @ObservedObject var model: SynthModel
    @Environment(\.theme) private var theme
    @State private var target: Int?
    @State private var name = ""
    @State private var newBankName = ""
    @State private var naming = false
    /// Last slot click, for the double click (a single click must load at
    /// once for auditioning, so no count: 2 gesture that would delay it).
    @State private var lastClick: (bank: Int, slot: Int, time: Date)?
    /// Slot under a drag (drop highlight).
    @State private var dropTarget: Int?
    private static let dragPrefix = SlotDrop.prefix

    private var bank: PatchBankInfo? {
        model.currentBank.flatMap { $0 < model.banks.count ? model.banks[$0] : nil }
    }

    var body: some View {
        VStack(spacing: 0) {
            header
            HStack(spacing: 0) {
                bankList.frame(width: 240).frame(maxHeight: .infinity)
                Rectangle().fill(theme.edge).frame(width: 1)
                // iOS: a ScrollView takes its ideal width otherwise
                slotGrid.frame(maxWidth: .infinity, maxHeight: .infinity)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .background(theme.panel)
        .overlay(Rectangle().strokeBorder(theme.edge, lineWidth: 1.5))
        .shadow(color: .black.opacity(0.4), radius: 6)
        .onAppear {
            name = model.patchName
            leaveReadOnlyBank()
            pickTarget()
        }
        .onChange(of: model.currentBank) { _, _ in pickTarget() }
        .onChange(of: model.writing) { _, _ in
            name = model.patchName
            leaveReadOnlyBank()
            pickTarget()
        }
        .onChange(of: model.writeCommits) { _, _ in commitWrite() }
    }

    // MARK: header

    private var header: some View {
        HStack(spacing: 6) {
            Text(model.writing ? "Write patch" : "Banks")
                .font(.system(size: 12, weight: .semibold)).foregroundColor(theme.headerText)
            Spacer(minLength: 6)
            if naming {
                field("new bank name", $newBankName, width: 180) { createBank() }
                button("Create") { createBank() }
                button("✕", width: 24) { naming = false }
            } else if model.writing {
                field("patch name", $name, width: 220) { commitWrite() }
                writeButton
                button("New bank…", width: 72) { startNaming() }
                button("Cancel") { model.closeBrowser() }
            } else {
                button("Import…") { model.fileAction = .importBanks }
                #if os(macOS)
                button("Open library folder", width: 120) { Finder.open(model.host.libraryRoot) }
                #endif
                button("Refresh") { model.loadLibrary(rescan: true) }
                PanelButton(title: "Close", width: 60, height: 20, icon: "xmark") { model.closeBrowser() }
            }
        }
        .padding(.horizontal, 8)
        .frame(height: 28)
        .background(theme.header)
    }

    private var writeButton: some View {
        let occupied = target.flatMap { t in bank.map { !$0.slots[t].isEmpty } } ?? false
        let label = target.map { occupied ? String(format: "Overwrite %03d", $0 + 1) : String(format: "Write to %03d", $0 + 1) }
            ?? "Write"
        let off = target == nil || bank == nil || bank?.readOnly == true
        return PanelButton(title: label, width: 110, height: 20, fill: occupied ? Theme.red : theme.button) { commitWrite() }
            .disabled(off)
            .opacity(off ? 0.5 : 1)
    }

    private func button(_ title: String, width: CGFloat = 60, action: @escaping () -> Void) -> some View {
        PanelButton(title: title, width: width, height: 20, action: action)
    }

    private func field(_ prompt: String, _ text: Binding<String>, width: CGFloat, submit: @escaping () -> Void) -> some View {
        TextField(prompt, text: text)
            .textFieldStyle(.plain)
            .font(.system(size: 12, design: .monospaced))
            .foregroundColor(Theme.lcdText)
            .padding(.horizontal, 6)
            .frame(width: width, height: 20)
            .background(RoundedRectangle(cornerRadius: 3).fill(theme.displayFill))
            .onSubmit(submit)
    }

    // MARK: banks

    private var bankList: some View {
        ScrollViewReader { proxy in
            ScrollView {
                VStack(alignment: .leading, spacing: 1) {
                    ForEach(model.banks.indices, id: \.self) { b in bankRow(b) }
                    if model.banks.isEmpty {
                        Text("No banks. Import… copies bank folders, zip banks or .sy1 patches into the library.")
                            .font(.system(size: 10)).foregroundColor(Theme.lcdText.opacity(0.7)).padding(8)
                    }
                }
                .padding(.top, 4)
            }
            .scrollDeadZone()
            // Keyboard steps roll into other banks: keep the bank in view.
            .onChange(of: model.currentBank) { _, b in if let b { proxy.scrollTo(b) } }
            .onAppear { if let b = model.currentBank { proxy.scrollTo(b) } }
        }
        .background(theme.lcdBottom)
    }

    private func bankRow(_ b: Int) -> some View {
        let info = model.banks[b]
        let selected = b == model.currentBank
        let locked = model.writing && info.readOnly // the write dialog can't pick it
        return HStack(spacing: 4) {
            Text(String(format: "%02d:", b) + info.name).lineLimit(1)
                .oxoTruncationTooltip(String(format: "%02d:", b) + info.name, size: 11, monospaced: true, optional: false)
            Spacer(minLength: 2)
            Text("\(info.count)").opacity(0.6)
        }
        .font(.system(size: 11, design: .monospaced))
        .foregroundColor(selected ? theme.lcdBottom : Theme.lcdText)
        .padding(.horizontal, 6).padding(.vertical, 3)
        .background(selected ? theme.displayText : Color.clear)
        .opacity(locked ? 0.35 : 1)
        .contentShape(Rectangle())
        .onTapGesture { if !locked { model.currentBank = b } }
        .showInFinder { info.source }
    }

    // MARK: slots

    private var slotGrid: some View {
        ScrollViewReader { proxy in
            ScrollView {
                if let b = model.currentBank, b < model.banks.count {
                    // 4 columns filled top to bottom (1-32, 33-64, ...); ids =
                    // slot numbers, so the keyboard selection can scroll in.
                    let count = model.banks[b].slots.count, rows = SynthModel.browserRows(count)
                    LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 2), count: 4), spacing: 2) {
                        ForEach((0..<rows * 4).map { $0 % 4 * rows + $0 / 4 }, id: \.self) { s in
                            if s < count { slotCell(b, s) } else { Color.clear }
                        }
                    }
                    .padding(6)
                }
            }
            .scrollDeadZone()
            .onChange(of: [model.currentBank, model.currentProgram]) { _, _ in scrollToLoaded(proxy) }
            .onAppear { scrollToLoaded(proxy) }
        }
        .background(theme.lcdBottom)
    }

    private func scrollToLoaded(_ proxy: ScrollViewProxy) {
        if let p = model.currentProgram, model.loadedBank == model.currentBank { proxy.scrollTo(p) }
    }

    private func slotCell(_ b: Int, _ s: Int) -> some View {
        let patch = model.banks[b].slots[s]
        let readOnly = model.banks[b].readOnly
        let selected = model.writing ? target == s : (b == model.loadedBank && s == model.currentProgram)
        return Text(String(format: "%03d:", s + 1) + patch)
            .font(.system(size: 11, design: .monospaced))
            .foregroundColor(selected ? theme.lcdBottom : patch.isEmpty ? Theme.lcdText.opacity(0.3) : Theme.lcdText)
            .lineLimit(1)
            // a cut name shows whole (not a help tip: always on)
            .oxoTruncationTooltip(String(format: "%03d:", s + 1) + patch, size: 11, monospaced: true, optional: false)
            .padding(.horizontal, 5).padding(.vertical, 3)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: 2).fill(selected ? theme.displayText : theme.display))
            .overlay(RoundedRectangle(cornerRadius: 2).strokeBorder(theme.displayText, lineWidth: dropTarget == s ? 1.5 : 0))
            .contentShape(Rectangle())
            .onTapGesture {
                if model.writing {
                    target = s
                } else if !patch.isEmpty {
                    // double click = load + close the browser
                    if let c = lastClick, c.bank == b, c.slot == s, Date().timeIntervalSince(c.time) < 0.4 {
                        lastClick = nil
                        return model.closeBrowser()
                    }
                    lastClick = (b, s, Date())
                    model.load(bank: b, slot: s)
                }
            }
            .showInFinder(enabled: !patch.isEmpty, delete: readOnly ? nil : { model.deleteSlot(bank: b, slot: s) }) {
                model.host.slotPath(bank: b, slot: s)
            }
            // drag a patch onto another slot: move there (swap if taken)
            .slotDraggable(patch.isEmpty || readOnly ? nil : Self.dragPrefix + "\(b):\(s)",
                           label: String(format: "%03d:", s + 1) + patch, fill: theme.display)
            .onDrop(of: [.plainText, .utf8PlainText, .text], delegate: SlotDrop(bank: b, slot: s, target: $dropTarget) { from in
                model.movePatch(bank: b, from: from, to: s)
            })
    }

    // MARK: actions

    /// Write dialog default: the loaded slot when writing back into its bank,
    /// else the bank's first empty slot.
    private func pickTarget() {
        guard model.writing, let b = model.currentBank, b < model.banks.count else { return target = nil }
        if b == model.loadedBank, let p = model.currentProgram { return target = p }
        target = model.banks[b].slots.firstIndex(where: \.isEmpty) ?? 0
    }

    /// The write dialog never targets the read-only factory bank: it opens
    /// on the first writable one.
    private func leaveReadOnlyBank() {
        guard model.writing, let b = model.currentBank, b < model.banks.count, model.banks[b].readOnly else { return }
        model.currentBank = model.banks.firstIndex { !$0.readOnly }
    }

    private func commitWrite() {
        guard let b = model.currentBank, b < model.banks.count, !model.banks[b].readOnly, let t = target else { return }
        if model.write(bank: b, slot: t, name: name) { model.closeBrowser() }
    }

    private func startNaming() {
        newBankName = ""
        naming = true
    }

    private func createBank() {
        model.newBank(newBankName)
        naming = false
    }
}

/// Drop on a browser slot: proposes a MOVE (no "+" copy badge on the
/// cursor), highlights the slot, reads the "synth2-slot:bank:slot" payload.
private struct SlotDrop: DropDelegate {
    static let prefix = "synth2-slot:"
    let bank: Int, slot: Int
    @Binding var target: Int?
    let move: (Int) -> Void

    func dropEntered(info: DropInfo) { target = slot }
    func dropExited(info: DropInfo) { if target == slot { target = nil } }
    func dropUpdated(info: DropInfo) -> DropProposal? { DropProposal(operation: .move) }

    func performDrop(info: DropInfo) -> Bool {
        target = nil
        guard let provider = info.itemProviders(for: [.plainText, .utf8PlainText, .text]).first else { return false }
        let (b, s) = (bank, slot)
        _ = provider.loadObject(ofClass: NSString.self) { object, _ in
            guard let item = object as? String, item.hasPrefix(Self.prefix) else { return }
            let parts = item.dropFirst(Self.prefix.count).split(separator: ":").compactMap { Int($0) }
            guard parts.count == 2, parts[0] == b, parts[1] != s else { return }
            DispatchQueue.main.async { move(parts[1]) }
        }
        return true
    }
}

extension View {
    /// Draggable slot (payload = "synth2-slot:bank:slot"), nothing for an empty one.
    @ViewBuilder
    func slotDraggable(_ payload: String?, label: String, fill: Color) -> some View {
        if let payload {
            draggable(payload) {
                Text(label).font(.system(size: 11, design: .monospaced)).foregroundColor(Theme.lcdText)
                    .padding(.horizontal, 5).padding(.vertical, 3)
                    .background(RoundedRectangle(cornerRadius: 2).fill(fill))
            }
        } else {
            self
        }
    }
}

private func optionHeld() -> Bool {
    #if os(macOS)
    return NSEvent.modifierFlags.contains(.option)
    #else
    return false
    #endif
}

#if os(macOS)
/// Finder actions (mac only: the AUv3 can't open the Files app).
enum Finder {
    static func open(_ path: String) { NSWorkspace.shared.open(URL(fileURLWithPath: path)) }
    static func reveal(_ path: String) { NSWorkspace.shared.activateFileViewerSelecting([URL(fileURLWithPath: path)]) }
}
#endif

extension View {
    /// Right click > [Delete,] Show in Finder (mac; nothing on iPad).
    @ViewBuilder
    func showInFinder(enabled: Bool = true, delete: (() -> Void)? = nil, _ path: @escaping () -> String) -> some View {
        #if os(macOS)
        if enabled {
            contextMenu {
                if let delete { Button("Delete") { delete() } }
                Button("Show in Finder") { Finder.reveal(path()) }
            }
        } else {
            self
        }
        #else
        self
        #endif
    }
}

/// Plain data file for the iPad exporter (.sy1).
struct DataDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.data] }
    var data: Data
    init(data: Data) { self.data = data }
    init(configuration: ReadConfiguration) throws { data = configuration.file.regularFileContents ?? Data() }
    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper { FileWrapper(regularFileWithContents: data) }
}

/// Runs `model.fileAction`: NSOpenPanel / NSSavePanel on mac, the system
/// file importer / exporter on iPad (security-scoped URLs).
struct FileActions: ViewModifier {
    @ObservedObject var model: SynthModel
    #if !os(macOS)
    @State private var importing = false
    @State private var importAction: FileAction = .importBanks
    @State private var exporting = false
    @State private var exportDoc = DataDocument(data: Data())
    @State private var exportName = ""
    #endif

    func body(content: Content) -> some View {
        #if os(macOS)
        content.onChange(of: model.fileAction) { _, action in
            guard let action else { return }
            model.fileAction = nil
            DispatchQueue.main.async { run(action) }
        }
        #else
        content
            .onChange(of: model.fileAction) { _, action in
                guard let action else { return }
                model.fileAction = nil
                present(action)
            }
            .fileImporter(isPresented: $importing,
                          allowedContentTypes: importAction == .loadPatch ? [sy1Type, .data] : [.item],
                          allowsMultipleSelection: importAction == .importBanks) { result in
                guard case let .success(urls) = result else { return }
                if importAction == .loadPatch, let url = urls.first {
                    let scoped = url.startAccessingSecurityScopedResource()
                    model.loadFile(url.path)
                    if scoped { url.stopAccessingSecurityScopedResource() }
                } else {
                    model.importItems(urls)
                }
            }
            .fileExporter(isPresented: $exporting, document: exportDoc, contentType: .data,
                          defaultFilename: exportName) { _ in }
        #endif
    }

    #if os(macOS)
    private func run(_ action: FileAction) {
        switch action {
        case .importBanks:
            let panel = NSOpenPanel()
            panel.message = "Import: bank folders, zip banks or .sy1 patches — copied into the library"
            panel.canChooseDirectories = true
            panel.allowsMultipleSelection = true
            panel.allowedContentTypes = [.zip, sy1Type, .folder]
            if panel.runModal() == .OK { model.importItems(panel.urls) }
        case .loadPatch:
            let panel = NSOpenPanel()
            panel.allowedContentTypes = [sy1Type]
            if panel.runModal() == .OK, let url = panel.url { model.loadFile(url.path) }
        case .savePatch:
            let panel = NSSavePanel()
            panel.allowedContentTypes = [sy1Type]
            panel.directoryURL = URL(fileURLWithPath: model.host.libraryRoot)
            panel.nameFieldStringValue = (model.patchName.isEmpty ? "patch" : model.patchName) + ".sy1"
            if panel.runModal() == .OK, let url = panel.url {
                _ = model.saveFile(url.path)
                model.loadLibrary(rescan: true)
            }
        }
    }
    #else
    private func present(_ action: FileAction) {
        switch action {
        case .importBanks, .loadPatch:
            importAction = action
            importing = true
        case .savePatch:
            exportDoc = DataDocument(data: Data(model.host.patchText.utf8))
            exportName = (model.patchName.isEmpty ? "patch" : model.patchName) + ".sy1"
            exporting = true
        }
    }
    #endif
}
