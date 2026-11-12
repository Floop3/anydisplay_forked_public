import AppKit
import ApplicationServices
import AnyDisplayHost
import AnyDisplayProtocol
import Foundation
import Network

/// Menu bar app: lists Wi-Fi and USB clients, connects, shows the pairing code, and manages
/// permissions and pairings.
final class AppController: NSObject, NSMenuDelegate {
    private struct Active {
        let session: HostSession
        var title: String
        var state: SessionState
        var cleanup: (() -> Void)?
    }

    private let store = PairingStore()
    private let browser = BonjourBrowser()
    private let adb = Adb()
    private var usbMonitor: UsbMonitor?
    private var statusItem: NSStatusItem!
    private var networkDevices: [NetworkDevice] = []
    private var usbDevices: [UsbDevice] = []
    /// Keyed by "usb:<serial>", "net:<service name>" or "ip:<host:port>".
    private var sessions: [String: Active] = [:]
    private var usbRetryAfter: [String: Date] = [:]
    private var pairingAlert: (key: String, alert: NSAlert)?
    private var signalSources: [DispatchSourceSignal] = []

    private var autoConnectUSB: Bool {
        get { UserDefaults.standard.object(forKey: "autoConnectUSB") as? Bool ?? true }
        set { UserDefaults.standard.set(newValue, forKey: "autoConnectUSB") }
    }

    func start() {
        statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        statusItem.button?.image = NSImage(systemSymbolName: "rectangle.on.rectangle", accessibilityDescription: "AnyDisplay")
        let menu = NSMenu()
        menu.delegate = self
        statusItem.menu = menu

        browser.onChange = { [weak self] devices in self?.networkDevices = devices }
        browser.start()
        if let adb {
            let monitor = UsbMonitor(adb: adb)
            monitor.onChange = { [weak self] devices in
                self?.usbDevices = devices
                self?.autoConnect()
            }
            monitor.start()
            usbMonitor = monitor
            // Retry attached devices whose client was not listening yet.
            Timer.scheduledTimer(withTimeInterval: 3, repeats: true) { [weak self] _ in self?.autoConnect() }
        }
        // Quit cleanly (BYE to every client) on SIGINT / SIGTERM, e.g. logout or `killall`.
        for sig in [SIGINT, SIGTERM] {
            signal(sig, SIG_IGN)
            let src = DispatchSource.makeSignalSource(signal: sig, queue: .main)
            src.setEventHandler { [weak self] in self?.quit() }
            src.resume()
            signalSources.append(src)
        }
        Log.info("AnyDisplay host running; adb: \(adb?.path ?? "not found")")
    }

    // MARK: - Connecting

    private func autoConnect() {
        guard autoConnectUSB else { return }
        for d in usbDevices where sessions["usb:\(d.serial)"] == nil {
            if let t = usbRetryAfter[d.serial], t > Date() { continue }
            connectUSB(d)
        }
    }

    private func connectUSB(_ device: UsbDevice) {
        guard let adb else { return }
        let key = "usb:\(device.serial)"
        guard sessions[key] == nil else { return }
        DispatchQueue.global().async {
            guard let port = adb.forward(serial: device.serial) else {
                DispatchQueue.main.async { self.usbRetryAfter[device.serial] = Date().addingTimeInterval(5) }
                return
            }
            DispatchQueue.main.async {
                let ep = NWEndpoint.hostPort(host: "127.0.0.1", port: NWEndpoint.Port(rawValue: port)!)
                self.begin(key: key, title: device.model ?? device.serial, endpoint: ep) {
                    DispatchQueue.global().async { adb.removeForward(serial: device.serial, localPort: port) }
                }
            }
        }
    }

    private func begin(key: String, title: String, endpoint: NWEndpoint, cleanup: (() -> Void)? = nil) {
        guard sessions[key] == nil else { return }
        let s = HostSession(endpoint: endpoint, store: store)
        sessions[key] = Active(session: s, title: title, state: .connecting, cleanup: cleanup)
        s.onStateChange = { [weak self] state in self?.update(key: key, session: s, state: state) }
        s.onConfirmCode = { [weak self] code, reply in self?.confirm(key: key, code: code, reply: reply) }
        s.start()
    }

    private func update(key: String, session: HostSession, state: SessionState) {
        guard var a = sessions[key], a.session === session else { return }
        if let name = session.deviceName { a.title = name }
        a.state = state
        sessions[key] = a
        if case .closed(let error) = state {
            if let p = pairingAlert, p.key == key {
                NSApp.abortModal()
                pairingAlert = nil
            }
            a.cleanup?()
            sessions[key] = nil
            if key.hasPrefix("usb:") {
                // Wait before reconnecting an attached device that refused or dropped the session.
                usbRetryAfter[String(key.dropFirst(4))] = Date().addingTimeInterval(error == nil ? 15 : 5)
            }
        }
        refreshIcon()
    }

    private func confirm(key: String, code: String, reply: @escaping (Bool) -> Void) {
        let name = sessions[key]?.session.deviceName ?? sessions[key]?.title ?? NSLocalizedString("the device", comment: "Fallback name in the pairing alert")
        let grouped = "\(code.prefix(3)) \(code.suffix(3))"
        let alert = NSAlert()
        alert.messageText = String(format: NSLocalizedString("Pair with %@?", comment: "Pairing alert title"), name)
        alert.informativeText = String(format: NSLocalizedString("Check that %@ shows this code:\n\n%@", comment: "Pairing alert body"), name, grouped)
        alert.addButton(withTitle: NSLocalizedString("Codes Match", comment: "Pairing alert button"))
        alert.addButton(withTitle: NSLocalizedString("Cancel", comment: ""))
        pairingAlert = (key, alert)
        NSApp.activate(ignoringOtherApps: true)
        let response = alert.runModal()
        pairingAlert = nil
        reply(response == .alertFirstButtonReturn)
    }

    private func refreshIcon() {
        let streaming = sessions.values.contains { if case .streaming = $0.state { return true } else { return false } }
        statusItem.button?.image = NSImage(systemSymbolName: streaming ? "rectangle.on.rectangle.circle.fill" : "rectangle.on.rectangle",
                                           accessibilityDescription: "AnyDisplay")
    }

    // MARK: - Menu

    func menuNeedsUpdate(_ menu: NSMenu) {
        menu.removeAllItems()

        if !sessions.isEmpty {
            menu.addItem(header(NSLocalizedString("Connected", comment: "Menu section")))
            for (key, a) in sessions.sorted(by: { $0.key < $1.key }) {
                let item = NSMenuItem(title: "\(a.title) — \(describe(a.state))", action: nil, keyEquivalent: "")
                let sub = NSMenu()
                sub.addItem(action(NSLocalizedString("Disconnect", comment: "")) { [weak self] in self?.sessions[key]?.session.stop() })
                item.submenu = sub
                menu.addItem(item)
            }
            menu.addItem(.separator())
        }

        menu.addItem(header(NSLocalizedString("Wi-Fi", comment: "Menu section")))
        if networkDevices.isEmpty { menu.addItem(disabled(NSLocalizedString("No devices found", comment: ""))) }
        for d in networkDevices {
            let key = "net:\(d.name)"
            let paired = d.deviceID.flatMap { Data(hex: $0) }.flatMap { store.secret(for: $0) } != nil
            let title = paired ? d.name : String(format: NSLocalizedString("%@ (not paired)", comment: ""), d.name)
            let item = action(title) { [weak self] in
                self?.begin(key: key, title: d.name, endpoint: d.endpoint)
            }
            item.state = sessions[key] != nil ? .on : .off
            item.isEnabled = sessions[key] == nil
            menu.addItem(item)
        }

        menu.addItem(header(NSLocalizedString("USB", comment: "Menu section")))
        if adb == nil {
            menu.addItem(disabled(NSLocalizedString("USB needs Android Platform-Tools (adb)", comment: "")))
        } else if usbDevices.isEmpty {
            menu.addItem(disabled(NSLocalizedString("Unlock device and allow USB debugging", comment: "")))
        }
        for d in usbDevices {
            let key = "usb:\(d.serial)"
            let item = action(d.model ?? d.serial) { [weak self] in
                self?.usbRetryAfter[d.serial] = nil
                self?.connectUSB(d)
            }
            item.state = sessions[key] != nil ? .on : .off
            item.isEnabled = sessions[key] == nil
            menu.addItem(item)
        }

        menu.addItem(.separator())
        menu.addItem(action(NSLocalizedString("Connect to Address…", comment: "")) { [weak self] in self?.askAddress() })
        let auto = action(NSLocalizedString("Connect USB Devices Automatically", comment: "")) { [weak self] in
            guard let self else { return }
            self.autoConnectUSB.toggle()
            self.autoConnect()
        }
        auto.state = autoConnectUSB ? .on : .off
        menu.addItem(auto)

        if !CGPreflightScreenCaptureAccess() || !AXIsProcessTrusted() {
            menu.addItem(.separator())
            if !CGPreflightScreenCaptureAccess() {
                menu.addItem(action(NSLocalizedString("Allow Screen Recording…", comment: "Permission menu item")) { _ = CGRequestScreenCaptureAccess() })
            }
            if !AXIsProcessTrusted() {
                menu.addItem(action(NSLocalizedString("Allow Accessibility (touch, pen, keys)…", comment: "Permission menu item")) {
                    let opts = [kAXTrustedCheckOptionPrompt.takeUnretainedValue() as String: true] as CFDictionary
                    _ = AXIsProcessTrustedWithOptions(opts)
                })
            }
        }

        let paired = store.devices
        if !paired.isEmpty {
            menu.addItem(.separator())
            let item = NSMenuItem(title: NSLocalizedString("Paired Devices", comment: ""), action: nil, keyEquivalent: "")
            let sub = NSMenu()
            for (id, d) in paired.sorted(by: { $0.value.name < $1.value.name }) {
                sub.addItem(action(String(format: NSLocalizedString("Forget %@", comment: ""), d.name)) { [weak self] in
                    if let data = Data(hex: id) { self?.store.forget(deviceID: data) }
                })
            }
            sub.addItem(.separator())
            sub.addItem(action(NSLocalizedString("Forget All", comment: "")) { [weak self] in self?.store.forgetAll() })
            item.submenu = sub
            menu.addItem(item)
        }

        menu.addItem(.separator())
        menu.addItem(action(NSLocalizedString("Setup & Troubleshooting…", comment: "")) {
            NSWorkspace.shared.open(URL(string: "https://efekurucay.com/anydisplay/#setup")!)
        })
        menu.addItem(action(NSLocalizedString("Quit AnyDisplay", comment: "")) { [weak self] in self?.quit() })
    }

    private func describe(_ s: SessionState) -> String {
        switch s {
        case .connecting: return NSLocalizedString("connecting", comment: "Session state")
        case .handshaking: return NSLocalizedString("authenticating", comment: "Session state")
        case .confirmingCode(let c):
            return String(format: NSLocalizedString("pairing code %@", comment: "Session state"), c)
        case .streaming(let i):
            // The rate shown here is the granted stream rate; localization of the format string only.
            return "\(i.width)×\(i.height) \(i.refreshHz) Hz \(i.codec == .hevc ? "HEVC" : "H.264")\(i.mirroring ? NSLocalizedString(", mirroring", comment: "") : "")"
        case .closed: return NSLocalizedString("closed", comment: "Session state")
        }
    }

    private func askAddress() {
        let alert = NSAlert()
        alert.messageText = NSLocalizedString("Connect to Address", comment: "")
        alert.informativeText = NSLocalizedString("IP address or host name of the device running AnyDisplay (port 47820 unless given).", comment: "")
        let field = NSTextField(frame: NSRect(x: 0, y: 0, width: 240, height: 24))
        field.placeholderString = "192.168.1.20"
        alert.accessoryView = field
        alert.addButton(withTitle: NSLocalizedString("Connect", comment: ""))
        alert.addButton(withTitle: NSLocalizedString("Cancel", comment: ""))
        NSApp.activate(ignoringOtherApps: true)
        alert.window.initialFirstResponder = field
        guard alert.runModal() == .alertFirstButtonReturn else { return }
        let text = field.stringValue.trimmingCharacters(in: .whitespaces)
        guard !text.isEmpty else { return }
        begin(key: "ip:\(text)", title: text, endpoint: endpoint(text))
    }

    private func quit() {
        let active = sessions.values.map(\.session)
        active.forEach { $0.stop(reason: .shutdown) }
        DispatchQueue.main.asyncAfter(deadline: .now() + (active.isEmpty ? 0 : 0.5)) { NSApp.terminate(nil) }
    }

    // MARK: - Menu helpers

    private var actions: [ClosureAction] = []

    private func action(_ title: String, _ run: @escaping () -> Void) -> NSMenuItem {
        let target = ClosureAction(run)
        actions.append(target)
        if actions.count > 200 { actions.removeFirst(actions.count - 200) }
        let item = NSMenuItem(title: title, action: #selector(ClosureAction.invoke), keyEquivalent: "")
        item.target = target
        return item
    }

    private func header(_ title: String) -> NSMenuItem {
        if #available(macOS 14.0, *) { return NSMenuItem.sectionHeader(title: title) }
        return disabled(title)
    }

    private func disabled(_ title: String) -> NSMenuItem {
        let item = NSMenuItem(title: title, action: nil, keyEquivalent: "")
        item.isEnabled = false
        return item
    }
}

private final class ClosureAction: NSObject {
    let run: () -> Void
    init(_ run: @escaping () -> Void) { self.run = run }
    @objc func invoke() { run() }
}
