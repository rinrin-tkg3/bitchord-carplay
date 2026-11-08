import SwiftUI

public struct SettingsView: View {
    @ObservedObject var playback = PlaybackService.shared
    @Environment(\.presentationMode) var presentationMode
    @State private var inputHost: String = ""
    @State private var inputPort: String = ""
    @State private var testStatus: String? = nil

    public init() {}

    public var body: some View {
        NavigationView {
            Form {
                Section(header: Text("接続先 Galaxy (Android)")) {
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
