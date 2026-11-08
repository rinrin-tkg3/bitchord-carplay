import SwiftUI

public struct SettingsView: View {
    @ObservedObject var playback = PlaybackService.shared
    @ObservedObject var wifiService = WiFiReconnectService.shared
    @Environment(\.presentationMode) var presentationMode
    @State private var inputHost: String = ""
    @State private var inputPort: String = ""
    @State private var testStatus: String? = nil

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
                                Text("CarPlay 接続中").foregroundColor(.green).font(.subheadline)
                            }
                        } else if playback.simulateCarPlayConnected {
                            HStack(spacing: 4) {
                                Circle().fill(Color.orange).frame(width: 8, height: 8)
                                Text("擬似接続中 (デバッグ)").foregroundColor(.orange).font(.subheadline)
                            }
                        } else {
                            HStack(spacing: 4) {
                                Circle().fill(Color.red).frame(width: 8, height: 8)
                                Text("未接続 (再生停止)").foregroundColor(.red).font(.subheadline)
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
}
