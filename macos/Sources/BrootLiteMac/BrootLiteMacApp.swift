import AppKit
import SwiftUI

@MainActor
final class BrootLiteAppDelegate: NSObject, NSApplicationDelegate {
    weak var viewModel: AppViewModel?

    func applicationDidFinishLaunching(_ notification: Notification) {
        NSApplication.shared.setActivationPolicy(.regular)
        NSApplication.shared.appearance = NSAppearance(named: .darkAqua)
        XirtusTheme.registerBundledFonts()
        NSApplication.shared.activate(ignoringOtherApps: true)
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool {
        true
    }

    func applicationWillTerminate(_ notification: Notification) {
        viewModel?.player.shutdown()
        Task {
            await BrootLiteCoreClient.shared.shutdown()
        }
    }
}

@main
struct BrootLiteMacApp: App {
    @NSApplicationDelegateAdaptor(BrootLiteAppDelegate.self) private var appDelegate
    @State private var vm = AppViewModel()

    init() {
        XirtusTheme.registerBundledFonts()
    }

    var body: some Scene {
        WindowGroup("BrootLite") {
            MainWindowView(vm: vm)
                .frame(minWidth: 1120, minHeight: 720)
                .onAppear {
                    appDelegate.viewModel = vm
                }
        }
        .windowStyle(.hiddenTitleBar)
        .defaultSize(width: 1380, height: 860)
        .commands {
            CommandGroup(after: .textEditing) {
                Button("Search Stremio & Swarm...") {
                    vm.focusGlobalStremioSearch()
                }
                .keyboardShortcut("k", modifiers: [.command])
            }
        }
    }
}
