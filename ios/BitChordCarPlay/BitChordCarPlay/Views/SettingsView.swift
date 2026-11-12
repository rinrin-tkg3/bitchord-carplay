import SwiftUI

public struct SettingsView: View {
    @ObservedObject var playback = PlaybackService.shared
    @ObservedObject var wifiService = WiFiReconnectService.shared
    @Environment(\.presentationMode) var presentationMode
    @State private var inputHost: String = ""
    @State private var inputPort: String = ""
    @State private var testStatus: String? = nil
    @State private var showScanner: Bool = false

    public init() {}

    public var body: some View {
        NavigationView {
            Form {
                Section(
                    header: Text("CarPlay 接続制御"),
                    footer: Text("CarPlayに接続されている時のみ再生します。未接続や切断時は自動で再生が一時停止します。")
                        .font(.footnote)
                        .foregroundColor(.gray)
                ) {
                    HStack {
                        Text("CarPlay 接続状態")
                        Spacer()
                        if playback.isCarPlayConnected {
                            HStack(spacing: 4) {
                                Circle().fill(Color.green).frame(width: 8, height: 8)
                                Text(playback.isCharging ? "接続中 (給電/充電中)" : "CarPlay 接続中").foregroundColor(.green).font(.subheadline)
                            }
                        } else if playback.simulateCarPlayConnected {
                            HStack(spacing: 4) {
                                Circle().fill(Color.orange).frame(width: 8, height: 8)
                                Text("擬似接続中 (デバッグ)").foregroundColor(.orange).font(.subheadline)
                            }
                        } else {
                            HStack(spacing: 4) {
                                Circle().fill(Color.red).frame(width: 8, height: 8)
                                Text("未接続 (バッテリー駆動)").foregroundColor(.red).font(.subheadline)
                            }
                        }
                    }

                    Toggle(isOn: $playback.simulateCarPlayConnected) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("擬似CarPlayモード")
                            Text("実車/ディスプレイなしでデバッグ再生を許可")
                                .font(.caption)
                                .foregroundColor(.gray)
                        }
                    }
                }

                Section(
                    header: Text("接続先 Galaxy (Android)"),
                    footer: Text("テザリング接続時、端末IPが変更されても自動探索でGalaxyを発見できます。")
                        .font(.footnote)
                        .foregroundColor(.gray)
                ) {
                    Button(action: {
                        playback.triggerAutoDiscovery()
                    }) {
                        HStack {
                            Image(systemName: "antenna.radiowaves.left.and.right")
                            Text("Galaxy を自動探索")
                            Spacer()
                            if playback.isDiscovering {
                                ProgressView()
                                    .scaleEffect(0.8)
                            }
                        }
                    }

                    if !playback.discoveryStatusText.isEmpty {
                        Text(playback.discoveryStatusText)
                            .font(.caption)
                            .foregroundColor(.gray)
                    }

                    Button(action: {
                        showScanner = true
                    }) {
                        HStack {
                            Image(systemName: "camera.viewfinder")
                                .foregroundColor(.blue)
                            Text("カメラでQRコードを読み取り")
                            Spacer()
                            Image(systemName: "chevron.right")
                                .font(.caption)
                                .foregroundColor(.gray)
                        }
                    }

                    HStack {
                        Text("IP / ホスト名")
                            .frame(width: 100, alignment: .leading)
                        TextField("例: 10.207.211.165", text: $inputHost)
                            .keyboardType(.numbersAndPunctuation)
                            .autocapitalization(.none)
                            .disableAutocorrection(true)
                    }

                    HStack {
                        Text("ポート番号")
                            .frame(width: 100, alignment: .leading)
                        TextField("8765", text: $inputPort)
                            .keyboardType(.numberPad)
                    }

                    Button(action: testConnection) {
                        HStack {
                            Text("接続テスト")
                            Spacer()
                            if let status = testStatus {
                                Text(status)
                                    .font(.caption)
                                    .foregroundColor(status.contains("成功") ? .green : .red)
                            }
                        }
                    }
                }

                Section(
                    header: Text("テザリング Wi-Fi 再接続支援"),
                    footer: Text("iPhoneのWi-Fi一覧にあるのに自動で繋がらない問題を解決します。SSIDを登録しておくと、CarPlay接続時や切断時にアプリ側から自動で接続を要求します。")
                        .font(.footnote)
                        .foregroundColor(.gray)
                ) {
                    HStack {
                        Text("SSID")
                            .frame(width: 100, alignment: .leading)
                        TextField("例: Galaxy S25 / ホットスポット名", text: $wifiService.targetSsid)
                            .autocapitalization(.none)
                            .disableAutocorrection(true)
                    }

                    HStack {
                        Text("パスワード")
                            .frame(width: 100, alignment: .leading)
                        SecureField("未設定または空欄可", text: $wifiService.targetPassword)
                    }

                    Toggle("CarPlay接続時に自動再接続", isOn: $wifiService.autoReconnectOnCarPlay)

                    Button(action: {
                        wifiService.triggerReconnect(force: true)
                    }) {
                        HStack {
                            Image(systemName: "wifi")
                            Text("Wi-Fi へ今すぐ再接続要求")
                            Spacer()
                            if wifiService.isReconnecting {
                                ProgressView()
                                    .scaleEffect(0.8)
                            }
                        }
                    }

                    if !wifiService.statusMessage.isEmpty {
                        Text(wifiService.statusMessage)
                            .font(.caption)
                            .foregroundColor(.gray)
                    }

                    Button(action: {
                        wifiService.openSystemWiFiSettings()
                    }) {
                        HStack {
                            Image(systemName: "arrow.up.forward.app")
                            Text("iOS の Wi-Fi 設定画面を開く")
                            Spacer()
                        }
                    }
                }

                Section(
                    header: Text("再生モード"),
                    footer: Text(playback.playbackMode.subtitle)
                        .font(.footnote)
                        .foregroundColor(.gray)
                ) {
                    Picker("再生方式", selection: $playback.playbackMode) {
                        ForEach(AudioPlaybackMode.allCases) { mode in
                            Text(mode.rawValue).tag(mode)
                        }
                    }
                    .pickerStyle(InlinePickerStyle())
                }

                Section(header: Text("接続状態")) {
                    HStack {
                        Text("現在のURL")
                        Spacer()
                        Text(playback.baseUrl)
                            .font(.system(.footnote, design: .monospaced))
                            .foregroundColor(.gray)
                    }

                    HStack {
                        Text("ステータス")
                        Spacer()
                        HStack(spacing: 6) {
                            Circle()
                                .fill(playback.isConnected ? Color.green : Color.red)
                                .frame(width: 8, height: 8)
                            Text(playback.isConnected ? "接続中" : "切断")
                                .foregroundColor(playback.isConnected ? .green : .red)
                        }
                    }
                }
            }
            .navigationBarTitle("設定", displayMode: .inline)
            .sheet(isPresented: $showScanner) {
                QRCodeScannerSheet(
                    onScanned: { code in
                        showScanner = false
                        handleScannedQr(code)
                    },
                    onCancel: {
                        showScanner = false
                    }
                )
            }
            .navigationBarItems(
                leading: Button("キャンセル") {
                    presentationMode.wrappedValue.dismiss()
                },
                trailing: Button("保存") {
                    saveSettings()
                    presentationMode.wrappedValue.dismiss()
                }
            )
            .onAppear {
                inputHost = playback.serverHost
                inputPort = String(playback.serverPort)
            }
            .onChange(of: playback.serverHost) { newHost in
                inputHost = newHost
            }
            .onChange(of: playback.serverPort) { newPort in
                inputPort = String(newPort)
            }
        }
    }

    private func saveSettings() {
        let cleanHost = inputHost.trimmingCharacters(in: .whitespacesAndNewlines)
        if !cleanHost.isEmpty {
            playback.serverHost = cleanHost
        }
        if let port = Int(inputPort.trimmingCharacters(in: .whitespacesAndNewlines)), port > 0 {
            playback.serverPort = port
        }
        playback.fetchState()
    }

    private func testConnection() {
        testStatus = "テスト中..."
        saveSettings()
        guard let url = URL(string: "\(playback.baseUrl)/api/state?cid=test") else {
            testStatus = "URL不正"
            return
        }
        var req = URLRequest(url: url)
        req.timeoutInterval = 3.0
        URLSession.shared.dataTask(with: req) { _, response, error in
            DispatchQueue.main.async {
                if let http = response as? HTTPURLResponse, http.statusCode == 200 {
                    testStatus = "接続成功 (200 OK)"
                } else {
                    testStatus = "接続失敗 (\(error?.localizedDescription ?? "エラー"))"
                }
            }
        }.resume()
    }

    private func handleScannedQr(_ raw: String) {
        var str = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if str.hasPrefix("http://") {
            str = String(str.dropFirst("http://".count))
        } else if str.hasPrefix("https://") {
            str = String(str.dropFirst("https://".count))
        } else if str.hasPrefix("bitchord://") {
            str = String(str.dropFirst("bitchord://".count))
        }
        if let slashIndex = str.firstIndex(of: "/") {
            str = String(str[..<slashIndex])
        }
        if let qIndex = str.firstIndex(of: "?") {
            str = String(str[..<qIndex])
        }
        if str.contains(":") {
            let parts = str.components(separatedBy: ":")
            let host = parts[0].trimmingCharacters(in: .whitespacesAndNewlines)
            if !host.isEmpty {
                inputHost = host
                playback.serverHost = host
            }
            if parts.count >= 2, let port = Int(parts[1].trimmingCharacters(in: .whitespacesAndNewlines)), port > 0 {
                inputPort = String(port)
                playback.serverPort = port
            }
        } else if !str.isEmpty {
            inputHost = str
            playback.serverHost = str
        }
        saveSettings()
        testConnection()
    }
}

// MARK: - Camera QR Scanner Components

import AVFoundation
import AudioToolbox

public struct QRCodeScannerSheet: View {
    var onScanned: (String) -> Void
    var onCancel: () -> Void

    public var body: some View {
        NavigationView {
            ZStack {
                QRCodeScannerView(onScanned: onScanned)
                    .edgesIgnoringSafeArea(.all)

                VStack {
                    Spacer()
                    ZStack {
                        RoundedRectangle(cornerRadius: 16)
                            .stroke(Color.white.opacity(0.85), lineWidth: 3)
                            .frame(width: 240, height: 240)
                            .background(Color.black.opacity(0.12))
                    }
                    Text("Galaxy の画面にある QR コードを枠内に写してください")
                        .font(.footnote)
                        .foregroundColor(.white)
                        .padding(.horizontal, 16)
                        .padding(.vertical, 8)
                        .background(Color.black.opacity(0.70))
                        .cornerRadius(8)
                        .padding(.top, 24)
                    Spacer()
                }
            }
            .navigationBarTitle("QRコードをスキャン", displayMode: .inline)
            .navigationBarItems(
                leading: Button("キャンセル") {
                    onCancel()
                }
            )
        }
    }
}

public protocol ScannerViewControllerDelegate: AnyObject {
    func didScanCode(_ code: String)
}

public struct QRCodeScannerView: UIViewControllerRepresentable {
    var onScanned: (String) -> Void

    public func makeUIViewController(context: Context) -> ScannerViewController {
        let vc = ScannerViewController()
        vc.delegate = context.coordinator
        return vc
    }

    public func updateUIViewController(_ uiViewController: ScannerViewController, context: Context) {}

    public func makeCoordinator() -> Coordinator {
        Coordinator(onScanned: onScanned)
    }

    public class Coordinator: NSObject, ScannerViewControllerDelegate {
        var onScanned: (String) -> Void
        init(onScanned: @escaping (String) -> Void) {
            self.onScanned = onScanned
        }
        public func didScanCode(_ code: String) {
            onScanned(code)
        }
    }
}

public class ScannerViewController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
    public weak var delegate: ScannerViewControllerDelegate?
    private var captureSession: AVCaptureSession?
    private var previewLayer: AVCaptureVideoPreviewLayer?
    private var hasScanned = false

    public override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        checkCameraPermission()
    }

    private func checkCameraPermission() {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            setupCamera()
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
                DispatchQueue.main.async {
                    if granted {
                        self?.setupCamera()
                    } else {
                        self?.showPermissionDenied()
                    }
                }
            }
        default:
            showPermissionDenied()
        }
    }

    private func setupCamera() {
        let session = AVCaptureSession()
        guard let device = AVCaptureDevice.default(for: .video) else {
            showNoCamera()
            return
        }
        guard let input = try? AVCaptureDeviceInput(device: device) else {
            showNoCamera()
            return
        }
        if session.canAddInput(input) {
            session.addInput(input)
        }
        let output = AVCaptureMetadataOutput()
        if session.canAddOutput(output) {
            session.addOutput(output)
            output.setMetadataObjectsDelegate(self, queue: DispatchQueue.main)
            output.metadataObjectTypes = [.qr]
        }
        let preview = AVCaptureVideoPreviewLayer(session: session)
        preview.frame = view.bounds
        preview.videoGravity = .resizeAspectFill
        view.layer.addSublayer(preview)
        self.previewLayer = preview
        self.captureSession = session

        DispatchQueue.global(qos: .userInitiated).async {
            session.startRunning()
        }
    }

    public override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        previewLayer?.frame = view.bounds
    }

    public override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        if captureSession?.isRunning == true {
            captureSession?.stopRunning()
        }
    }

    public func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput metadataObjects: [AVMetadataObject], from connection: AVCaptureConnection) {
        guard !hasScanned else { return }
        if let meta = metadataObjects.first as? AVMetadataMachineReadableCodeObject,
           meta.type == .qr,
           let str = meta.stringValue,
           !str.isEmpty {
            hasScanned = true
            AudioServicesPlaySystemSound(SystemSoundID(kSystemSoundID_Vibrate))
            delegate?.didScanCode(str)
        }
    }

    private func showPermissionDenied() {
        let label = UILabel()
        label.text = "カメラへのアクセスが許可されていません。\n「設定」アプリからカメラの使用を許可してください。"
        label.textColor = .white
        label.textAlignment = .center
        label.numberOfLines = 0
        label.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(label)

        let btn = UIButton(type: .system)
        btn.setTitle("設定を開く", for: .normal)
        btn.translatesAutoresizingMaskIntoConstraints = false
        btn.addTarget(self, action: #selector(openSettings), for: .touchUpInside)
        view.addSubview(btn)

        NSLayoutConstraint.activate([
            label.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            label.centerYAnchor.constraint(equalTo: view.centerYAnchor, constant: -20),
            label.leadingAnchor.constraint(greaterThanOrEqualTo: view.leadingAnchor, constant: 20),
            label.trailingAnchor.constraint(lessThanOrEqualTo: view.trailingAnchor, constant: -20),
            btn.topAnchor.constraint(equalTo: label.bottomAnchor, constant: 16),
            btn.centerXAnchor.constraint(equalTo: view.centerXAnchor),
        ])
    }

    private func showNoCamera() {
        let label = UILabel()
        label.text = "カメラが見つかりません"
        label.textColor = .white
        label.textAlignment = .center
        label.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(label)
        NSLayoutConstraint.activate([
            label.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            label.centerYAnchor.constraint(equalTo: view.centerYAnchor),
        ])
    }

    @objc private func openSettings() {
        if let url = URL(string: UIApplication.openSettingsURLString) {
            UIApplication.shared.open(url)
        }
    }
}
