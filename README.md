# SVGpro — Android向け高機能SVGエディター

Androidタブレット／スマートフォンで動作する、ネイティブ実装のベクターグラフィック（SVG）エディターです。
パス（アンカーポイント／ベジェハンドル）を直接精密に編集できることを最優先に設計されています。

- 完全オフライン動作（アカウント登録・ネットワーク接続不要）
- SVGの読み込み・編集・保存を端末内で完結（Storage Access Framework）
- タッチ・スタイラス（筆圧／ホバー）・Bluetoothキーボード対応
- ダークテーマ対応（システム設定に追従）

---

## ビルド

要件: JDK 21、Android SDK（compileSdk 34）

```bash
# 全テスト実行
./gradlew :core:test :app:testDebugUnitTest

# デバッグAPK
./gradlew :app:assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk

# リリースAPK（CI配布用にdebug鍵で署名）
./gradlew :app:assembleRelease
# → app/build/outputs/apk/release/app-release.apk
```

`local.properties` に `sdk.dir=<Android SDKのパス>` を設定してください（CIでは不要）。

### GitHub Actions

`.github/workflows/android.yml` により、push / PRでテスト＋APKビルドが実行され、
Artifactとして `svgpro-debug` / `svgpro-release` がアップロードされます。
`v*` タグをpushするとGitHub ReleaseにAPKが添付されます。

```
git tag v1.0.0 && git push origin v1.0.0
```

---

## プロジェクト構成

```
core/   純Kotlinモジュール（Android非依存・JVMテスト可能）
  ├ geom/    Matrix / Vec / Bounds（SVG transform解析含む）
  ├ path/    path data解析・生成・正規化・ノード編集モデル・ベジェ/アーク演算
  ├ svg/     Elementツリー / TokenSource抽象 / パーサ・ライタ
  ├ model/   SvgDocument / スタイル・図形ユーティリティ
  ├ edit/    UndoStack / ChangeSet / EditBatch
  └ ops/     JTSによるブーリアン・オフセット・単純化

app/    Androidアプリ
  ├ editor/  Editor（状態機械・選択・Undo統合ドラッグ） / Trace（絶対座標キャッシュ・ヒットテスト）
  ├ canvas/  CanvasView（入力処理・描画） / SvgRenderer（Document→android.graphics） / Tools（ツール状態機械）
  └ ui/      Compose UI（トップバー・ツールレール・レイヤー/プロパティパネル・ダイアログ）
```

表示（レンダリング）と編集データ（Elementツリー／PathData）は完全に分離されています。
エディタは常にベクターデータ（アンカー／ハンドル）を直接操作し、画面は都度 `PathData → android.graphics.Path` 変換で再描画します。
キャンバスの拡縮・パンはビュー変換行列のみを変更し、**編集データの座標は一切変化しません**。

## 実装済み機能

詳細は [docs/FEATURES.md](docs/FEATURES.md)、既知の制約は [docs/LIMITATIONS.md](docs/LIMITATIONS.md) を参照してください。

主要項目:

- **パス編集**: ペンツール（タップ=直線 / ドラッグ=ベジェ / 始点クリックで閉じる）、ノード編集（選択・移動・複数選択・範囲選択・追加[ダブルタップ]・削除・スムーズ/対称/コーナー変換・ハンドル連動[既定] / 独立[Shift]・整列）、パスの閉/開・方向反転・結合
- **図形**: 矩形・角丸矩形・楕円・線・ポリライン・多角形・星・フリーハンド（Catmull-Romでスムーズ化）・テキスト。プリミティブは属性のまま編集され、「パスへ変換」で昇格
- **変形**: 移動・拡縮・回転（8ハンドル＋回転ハンドル）・反転・整列（6方向）・間隔均等化・前面/背面・複製・削除・グループ化/解除・ロック・表示/非表示
- **塗り/線**: 単色（HSV+αピッカー・HEX入力・スウォッチ）・なし・線形/放射グラデーション・不透明度・線幅・線端（butt/round/square）・接続（miter/round/bevel）・破線プリセット
- **レイヤー**: レイヤー追加・オブジェクトツリー表示・名前変更・表示/非表示・ロック・順序変更
- **キャンバス**: ピンチズーム・2本指パン・全体表示・選択部分にフィット・1:1・グリッド表示/スナップ・オブジェクトスナップ・市松模様・拡大率表示・ドキュメントサイズ設定
- **スタイラス**: ペン/指の区別・「ペンのみ」モード（パームリジェクション）・ホバーカーソル＋ノードハイライト・ホイールズーム
- **SVG I/O**: SAF経由の読み書き・別名保存・未保存警告・新規作成。未知要素・コメント・`<style>`・`<metadata>`・filter等は**可逆保持**
- **Undo/Redo**: 全操作横断・ドラッグは1履歴に統合・400段
- **高度なパス操作**: ブーリアン（和・差・交差・排他 / JTS）・オフセット・単純化・ストロークのパス化・テキストのパス化

## 操作方法

| 操作 | 動作 |
|---|---|
| 1本指ドラッグ | ツール操作（選択/作図/ノード編集） |
| 2本指 | **常に**パン＋ピンチズーム |
| ペンツール: タップ | 直線セグメント追加 |
| ペンツール: ドラッグ | ベジェハンドルを引き出してカーブ追加 |
| ペンツール: 始点タップ | パスを閉じて確定 |
| ノードツール: セグメント ダブルタップ | ノード挿入 |
| ノードツール: ハンドルドラッグ | 対称連動（既定）。`ペンのみ`中のスタイラスボタンやShiftで独立 |
| ホイール | ズーム |

キーボード: `Ctrl+Z / Ctrl+Shift+Z / Ctrl+Y` Undo/Redo、`Ctrl+S` 保存、`Delete` 削除、`Ctrl+D` 複製、`Ctrl+G / Ctrl+Shift+G` グループ化/解除、`Ctrl+A` 全選択、矢印キー ナッジ（Shiftで10倍）

## アーキテクチャ上の要点

- **ドキュメントモデル**: SVG全体を汎用 `Element` ツリーとして保持。編集は属性値の書き換えのみで、非対応要素を壊さない
- **Undo**: `ChangeSet`（逆操作つきコマンド）＋ `EditBatch`（開始状態スナップショットdiff）。ドラッグ中は履歴に追加せず、確定時に1件だけpush
- **Trace**: ドキュメント走査で各要素の絶対CTM・幾何・表示/ロック状態をキャッシュ。ヒットテスト・選択bbox・描画に共用
- **座標系**: 編集はすべてviewBox単位系。ヒット判定は画面距離基準（指≈22px、ペン≈6-10px）で拡大率非依存の操作性を確保

## ライセンス

- 本プロジェクトのコード: MIT License（[LICENSE](LICENSE)）
- サードパーティ: [THIRD_PARTY.md](THIRD_PARTY.md)（JTS Topology Suite — EPL-2.0 / BSD-3）

## テスト

```
core: 40テスト中31テスト（SVG roundtrip / パス解析 / 行列 / ベジェ分割 / ブーリアン・オフセット・単純化 / Undo統合 / rect→path）
app:  9テスト（Editor統合フロー: 作図→選択→移動→スケール→グループ→ブーリアン→ノード編集→Undo/Redo→表示/ロック）
```
