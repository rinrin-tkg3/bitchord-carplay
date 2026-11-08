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
        if isReconnecting && !force {
            return
        }
        isReconnecting = true
        let cleanSsid = targetSsid.trimmingCharacters(in: .whitespacesAndNewlines)
        statusMessage = cleanSsid.isEmpty ? "テザリング親機を探索・接続中..." : "\(cleanSsid) へ強制接続中..."
        lastAttemptTime = Date()

        // Method 1: MobileWiFi.framework private API (Direct associate for TrollStore)
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            guard let self = self else { return }
            let connected = self.tryPrivateMobileWiFiAssociate(targetSsid: cleanSsid, password: self.targetPassword)

            DispatchQueue.main.async {
                if connected {
                    self.isReconnecting = false
                    self.statusMessage = cleanSsid.isEmpty ? "Wi-Fi接続コマンド送信完了" : "\(cleanSsid) へ接続コマンド送信完了"
                } else if !cleanSsid.isEmpty {
                    // Fallback Method 2: Standard NEHotspotConfigurationManager
                    self.applySystemHotspotConfig(ssid: cleanSsid, password: self.targetPassword)
                } else {
                    self.isReconnecting = false
                    self.statusMessage = "SSIDが一覧で見つかりませんでした (設定画面でSSIDをご指定ください)"
                }
            }
        }
    }

    private func applySystemHotspotConfig(ssid: String, password: String) {
        let pass = password.trimmingCharacters(in: .whitespacesAndNewlines)
        let config: NEHotspotConfiguration
        if !pass.isEmpty {
            config = NEHotspotConfiguration(ssid: ssid, passphrase: pass, isWEP: false)
        } else {
            config = NEHotspotConfiguration(ssid: ssid)
        }
        config.joinOnce = false

        NEHotspotConfigurationManager.shared.apply(config) { [weak self] error in
            DispatchQueue.main.async {
                guard let self = self else { return }
                self.isReconnecting = false
                if let error = error {
                    let desc = error.localizedDescription
                    if desc.contains("already associated") {
                        self.statusMessage = "接続中 (\(ssid))"
                    } else {
                        self.statusMessage = "接続要求完了: \(desc)"
                    }
                } else {
                    self.statusMessage = "接続成功 (\(ssid))"
                }
            }
        }
    }

    /// Open iOS Settings > Wi-Fi directly
    public func openSystemWiFiSettings() {
        if let url = URL(string: "App-Prefs:root=WIFI"), UIApplication.shared.canOpenURL(url) {
            UIApplication.shared.open(url, options: [:], completionHandler: nil)
        } else if let url = URL(string: UIApplication.openSettingsURLString) {
            UIApplication.shared.open(url, options: [:], completionHandler: nil)
        }
    }

    // MARK: - Private MobileWiFi framework integration for TrollStore
    private func tryPrivateMobileWiFiAssociate(targetSsid: String, password: String) -> Bool {
        guard let handle = dlopen("/System/Library/PrivateFrameworks/MobileWiFi.framework/MobileWiFi", RTLD_NOW) else {
            return false
        }
        defer { dlclose(handle) }

        typealias WiFiManagerClientCreateFunc = @convention(c) (CFAllocator?, Int32) -> UnsafeMutableRawPointer?
        typealias WiFiManagerClientCopyDevicesFunc = @convention(c) (UnsafeMutableRawPointer?) -> CFArray?
        typealias WiFiDeviceClientCopyScanListFunc = @convention(c) (UnsafeMutableRawPointer?) -> CFArray?
        typealias WiFiNetworkGetSSIDFunc = @convention(c) (UnsafeMutableRawPointer?) -> CFString?
        typealias WiFiNetworkSetPasswordFunc = @convention(c) (UnsafeMutableRawPointer?, CFString?) -> Void
        typealias WiFiDeviceClientAssociateAsyncFunc = @convention(c) (UnsafeMutableRawPointer?, UnsafeMutableRawPointer?, UnsafeRawPointer?, CFDictionary?) -> Int32
        typealias WiFiDeviceClientScanAsyncFunc = @convention(c) (UnsafeMutableRawPointer?, CFDictionary?, UnsafeRawPointer?, UnsafeMutableRawPointer?) -> Int32

        guard let createSym = dlsym(handle, "WiFiManagerClientCreate"),
              let copyDevicesSym = dlsym(handle, "WiFiManagerClientCopyDevices") else {
            return false
        }

        let createClient = unsafeBitCast(createSym, to: WiFiManagerClientCreateFunc.self)
        let copyDevices = unsafeBitCast(copyDevicesSym, to: WiFiManagerClientCopyDevicesFunc.self)

        guard let manager = createClient(kCFAllocatorDefault, 0) else { return false }
        guard let devices = copyDevices(manager) as? [AnyObject], let firstDeviceObj = devices.first else { return false }
        let firstDevice = Unmanaged.passUnretained(firstDeviceObj).toOpaque()

        let copyScanList: WiFiDeviceClientCopyScanListFunc? = dlsym(handle, "WiFiDeviceClientCopyScanList").map { unsafeBitCast($0, to: WiFiDeviceClientCopyScanListFunc.self) }
        let getSSID: WiFiNetworkGetSSIDFunc? = dlsym(handle, "WiFiNetworkGetSSID").map { unsafeBitCast($0, to: WiFiNetworkGetSSIDFunc.self) }
        let setPassword: WiFiNetworkSetPasswordFunc? = dlsym(handle, "WiFiNetworkSetPassword").map { unsafeBitCast($0, to: WiFiNetworkSetPasswordFunc.self) }
        let associateAsync: WiFiDeviceClientAssociateAsyncFunc? = dlsym(handle, "WiFiDeviceClientAssociateAsync").map { unsafeBitCast($0, to: WiFiDeviceClientAssociateAsyncFunc.self) }
        let scanAsync: WiFiDeviceClientScanAsyncFunc? = dlsym(handle, "WiFiDeviceClientScanAsync").map { unsafeBitCast($0, to: WiFiDeviceClientScanAsyncFunc.self) }

        // Trigger active scan in the background so fresh APs are populated
        _ = scanAsync?(firstDevice, nil, nil, nil)

        // Try matching from the scan list
        func findAndAssociate() -> Bool {
            guard let copyScanList = copyScanList,
                  let getSSID = getSSID,
                  let associateAsync = associateAsync else { return false }

            guard let scanList = copyScanList(firstDevice) as? [AnyObject] else { return false }

            for netObj in scanList {
                let netPtr = Unmanaged.passUnretained(netObj).toOpaque()
                guard let cfSsid = getSSID(netPtr) else { continue }
                let ssid = (cfSsid as String).trimmingCharacters(in: .whitespacesAndNewlines)
                if ssid.isEmpty { continue }

                let isMatch: Bool
                if !targetSsid.isEmpty {
                    isMatch = (ssid == targetSsid) || ssid.lowercased() == targetSsid.lowercased() || ssid.contains(targetSsid)
                } else {
                    // Auto-detect common hotspot names for Galaxy / BitChord
                    let lower = ssid.lowercased()
                    isMatch = lower.contains("galaxy") || lower.contains("direct-bc-") || lower.contains("bitchord") || lower.contains("hotspot")
                }

                if isMatch {
                    print("[WiFiReconnectService] Found matching Wi-Fi SSID: \(ssid). Forcing association...")
                    let pass = password.trimmingCharacters(in: .whitespacesAndNewlines)
                    if !pass.isEmpty {
                        setPassword?(netPtr, pass as CFString)
                    }
                    let res = associateAsync(firstDevice, netPtr, nil, nil)
                    print("[WiFiReconnectService] WiFiDeviceClientAssociateAsync returned: \(res)")
                    if self.targetSsid.isEmpty {
                        DispatchQueue.main.async {
                            self.targetSsid = ssid
                        }
                    }
                    return true
                }
            }
            return false
        }

        if findAndAssociate() {
            return true
        }

        // If not found on first inspection, sleep 500ms for scan to refresh, then retry
        Thread.sleep(forTimeInterval: 0.5)
        return findAndAssociate()
    }
}
