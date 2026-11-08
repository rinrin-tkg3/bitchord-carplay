# BitChord CarPlay (iPhone 専用ネイティブアプリ)

車載 Apple CarPlay および iPhone 7（iOS 15.0+）で、Galaxy (Android) の BitChord と連動して音楽を流すための専用ネイティブアプリです。

---

## 🌟 主な機能

1. **車載 Apple CarPlay ネイティブ連携**
   - 車載ディスプレイに標準の CarPlay Now Playing 画面とキュー一覧（`CPListTemplate`）を表示
   - ハンドルのステアリングリモコン（スキップ・一時停止・音量）に完全連動
2. **2つの再生モード切替**
   - **モード①：Androidからリアルタイム受信（ストリーム配信モード）**
     - Galaxy がデコード・ストリーミングした M4A 音声を iPhone がリアルタイム受信して車載スピーカーから流します。
   - **モード②：曲情報を基にiPhone側で曲データを持ってくる（ダイレクトモード）**
     - Galaxy からは曲情報（曲名、アーティスト、videoId、キュー）のみを受信し、音声ストリームは iPhone 自身が直接取得して再生します。ローカル Wi-Fi 帯域を消費せず高音質・低遅延です。
3. **洗練された iPhone 画面**
   - アートワーク表示、曲名、アーティスト、シークバー、操作ボタン
   - キュー一覧表示（タップした曲へ即座にスキップ）
   - 設定画面（Galaxy の IP アドレスとポート、再生モード切替、接続テスト）

---

## 🚀 Windows からのビルド & インストール方法（Mac不要）

GitHub Actions を使ってクラウド上の Mac で `.ipa` を自動ビルドできます。

### 手順
1. 本リポジトリを自身の GitHub（公開または非公開リポジトリ）にプッシュします。
2. GitHub の **「Actions」** タブを開くと、`Build BitChord CarPlay iOS (.ipa)` ワークフローが自動実行されます。
3. ビルド完了後、**「Artifacts」** から `BitChordCarPlay-ipa`（ZIP）をダウンロードします。
4. 解凍して出てきた `BitChordCarPlay.ipa` を以下のいずれかのツールで iPhone 7 にインストールします：
   - **Sideloadly（Windows対応・推奨）**: PC に iPhone 7 を USB 接続し、IPA ファイルをドラッグ＆ドロップして無料の Apple ID で署名・インストール。
   - **AltStore（Windows対応）**: PC 上の AltServer 経由でインストール。
   - **TrollStore（iOS 15.0〜15.4.1 の場合）**: 7日間の再署名不要で永続インストール。

---

## 📱 使い方
1. Galaxy S25 側でテザリング（モバイルホットスポット）をオンにします。
2. iPhone 7 を Galaxy の Wi-Fi に接続します。
3. iPhone 7 で **BitChord** アプリを開き、歯車アイコン（設定）から Galaxy の IP（通常 `10.207.211.165` または `192.168.43.1`）を確認・保存します。
4. 車の CarPlay ケーブルを iPhone 7 に挿せば、車載ディスプレイで BitChord が自動起動して音楽が流れます！
