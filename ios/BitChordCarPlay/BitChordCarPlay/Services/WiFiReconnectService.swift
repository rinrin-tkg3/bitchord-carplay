import Foundation
import NetworkExtension
import UIKit

#if canImport(SystemConfiguration.CaptiveNetwork)
import SystemConfiguration.CaptiveNetwork
#endif

public class WiFiReconnectService: ObservableObject {
    public static let shared = WiFiReconnectService()

    @Published public var targetSsid: String {
        didSet {
            UserDefaults.standard.set(targetSsid, forKey: "bitchord_target_ssid")
        }
    }
    @Published public var targetPassword: String {
        didSet {
            UserDefaults.standard.set(targetPassword, forKey: "bitchord_target_pass")
        }
    }
    @Published public var autoReconnectOnCarPlay: Bool {
        didSet {
            UserDefaults.standard.set(autoReconnectOnCarPlay, forKey: "bitchord_auto_reconnect_wifi")
        }
    }

    @Published public var statusMessage: String = ""
    @Published public var isReconnecting: Bool = false
    @Published public var lastAttemptTime: Date? = nil

    private init() {
        self.targetSsid = UserDefaults.standard.string(forKey: "bitchord_target_ssid") ?? ""
        self.targetPassword = UserDefaults.standard.string(forKey: "bitchord_target_pass") ?? ""
        self.autoReconnectOnCarPlay = UserDefaults.standard.object(forKey: "bitchord_auto_reconnect_wifi") as? Bool ?? true
    }

    /// Try all available methods to connect to the configured Galaxy Hotspot
    public func triggerReconnect(force: Bool = false) {
        let cleanSsid = targetSsid.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !cleanSsid.isEmpty else {
            statusMessage = "SSIDが未設定です"
            return
        }

        if isReconnecting && !force {
            return
        }
        isReconnecting = true
        statusMessage = "\(cleanSsid) へ接続要求中..."
        lastAttemptTime = Date()

        // Method 1: MobileWiFi.framework private API (TrollStore / unsandboxed privilege)
        tryPrivateMobileWiFiConnect(ssid: cleanSsid)

        // Method 2: Standard NEHotspotConfigurationManager (iOS official system hotspot API)
        let pass = targetPassword.trimmingCharacters(in: .whitespacesAndNewlines)
        let config: NEHotspotConfiguration
        if !pass.isEmpty {
            config = NEHotspotConfiguration(ssid: cleanSsid, passphrase: pass, isWEP: false)
        } else {
            config = NEHotspotConfiguration(ssid: cleanSsid)
        }
        config.joinOnce = false // Persist in system so iOS aggressively associates

        NEHotspotConfigurationManager.shared.apply(config) { [weak self] error in
            DispatchQueue.main.async {
                guard let self = self else { return }
                self.isReconnecting = false
                if let error = error {
                    let desc = error.localizedDescription
                    if desc.contains("already associated") {
                        self.statusMessage = "接続中 (\(cleanSsid))"
                    } else {
                        self.statusMessage = "要請完了: \(desc)"
                    }
                } else {
                    self.statusMessage = "接続成功 (\(cleanSsid))"
                }
            }
        }
    }

    /// Open iOS Settings > Wi-Fi directly
    public func openSystemWiFiSettings() {
        // App-Prefs:root=WIFI opens the Wi-Fi settings page directly
        if let url = URL(string: "App-Prefs:root=WIFI"), UIApplication.shared.canOpenURL(url) {
            UIApplication.shared.open(url, options: [:], completionHandler: nil)
        } else if let url = URL(string: UIApplication.openSettingsURLString) {
            UIApplication.shared.open(url, options: [:], completionHandler: nil)
        }
    }

    // MARK: - Private MobileWiFi framework integration for TrollStore
    private func tryPrivateMobileWiFiConnect(ssid: String) {
        // Dynamically load MobileWiFi to avoid compile-time dependency
        guard let handle = dlopen("/System/Library/PrivateFrameworks/MobileWiFi.framework/MobileWiFi", RTLD_NOW) else {
            return
        }
        defer { dlclose(handle) }

        typealias WiFiManagerClientCreateFunc = @convention(c) (CFAllocator?, Int32) -> UnsafeMutableRawPointer?
        typealias WiFiManagerClientCopyDevicesFunc = @convention(c) (UnsafeMutableRawPointer?) -> CFArray?

        guard let createSym = dlsym(handle, "WiFiManagerClientCreate"),
              let copyDevicesSym = dlsym(handle, "WiFiManagerClientCopyDevices") else {
            return
        }

        let createClient = unsafeBitCast(createSym, to: WiFiManagerClientCreateFunc.self)
        let copyDevices = unsafeBitCast(copyDevicesSym, to: WiFiManagerClientCopyDevicesFunc.self)

        guard let manager = createClient(kCFAllocatorDefault, 0) else { return }
        if let devices = copyDevices(manager) as? [AnyObject], let firstDevice = devices.first {
            // Found Wi-Fi interface device - trigger system Wi-Fi scan and link request
            typealias WiFiDeviceClientScanAsyncFunc = @convention(c) (AnyObject, CFDictionary?, UnsafeRawPointer?, UnsafeMutableRawPointer?) -> Int32
            if let scanSym = dlsym(handle, "WiFiDeviceClientScanAsync") {
                let scanAsync = unsafeBitCast(scanSym, to: WiFiDeviceClientScanAsyncFunc.self)
                let _ = scanAsync(firstDevice, nil, nil, nil)
            }
        }
    }
}
