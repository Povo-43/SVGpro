# SVGpro 設計書

## 1. 調査と技術選定（フェーズ1）

### 1.1 候補と評価

| 方式 | 長所 | 短所 | 判定 |
|---|---|---|---|
| WebView + JS（SVG-Edit等のフォーク） | 実装コスト小 | タッチ／スタイラス／筆圧の品質がブラウザ依存、デスクトップUI移植になりがち | 不採用 |
| 既存ネイティブAndroid SVGエディタOSS | — | 完成度・保守状態が要件に達するものが無い | 不採用 |
| ネイティブKotlin + 自前パスエンジン + JTS | 精度・操作性・完全オフライン。スタイラス／筆圧／hoverなど低レベル入力を直接制御できる | 実装コスト大 | **採用** |

### 1.2 採用ライブラリとライセンス

| ライブラリ | 用途 | ライセンス |
|---|---|---|
| Jetpack Compose / Material3 | UI | Apache-2.0 |
| AndroidX | 基盤 | Apache-2.0 |
| org.locationtech.jts:jts-core 1.20.0 | ブーリアン演算・オフセット・単純化 | EPL-2.0 / BSD-3 (EDL) |

SVGパース／生成、パスデータ（M/L/C/S/Q/T/A/Z）の解析と生成、ベジェ演算（分割・反転・スムージング）、座標変換はコアモジュールで自前実装する（JVMで単体テスト可能にするため）。パス編集に直接使える安定したライブラリは存在しない。

### 1.3 アーキテクチャ構成

```
┌────────────────────────── app (Android) ─────────────────────────┐
│  MainActivity (Compose scaffold)                                 │
│   ├ CanvasView（custom View: 描画・入力・スタイラス/hover/筆圧）   │
│   ├ ToolRail / TopBar / パネル(Compose): レイヤー/プロパティ/パス  │
│   └ EditorController: ツール状態機械・選択・スナップ              │
│        └ AndroidSvgRenderer: Document → android.graphics.Path    │
└─────────────────────────────┬────────────────────────────────────┘
                              │ (pure Kotlin, JVM テスト可能)
┌────────────────── core ─────▼────────────────────────────────────┐
│  model:  SvgDocument / SvgElement / Style / GradientDef           │
│  svgio:  SvgParser(XmlPullParser) / SvgWriter                     │
│  path:   PathData解析・生成・反転・分割・正規化(Anchor/Handle)     │
│  geom:   Matrix2D・Vec2・フラット化・JTS橋渡し(Bool/Offset/Simple) │
│  edit:   UndoStack（ドラッグ統合つきコマンド履歴）                 │
└──────────────────────────────────────────────────────────────────┘
```

表示（レンダリング）とデータモデル（ドキュメント）は完全に分離。エディタは常にベクターデータ（アンカー／ハンドル）を直接操作し、画面は都度 `PathData → android.Path` 変換で再描画する。

## 2. ドキュメントモデル

- 読み込み時、SVG全体（未知要素・コメント・CDATA・`<style>`・`<metadata>`を含む）を汎用ツリー `SvgElement`（tag/attrs/children）として完全保持する。
- 編集可能要素（path/rect/circle/ellipse/line/polyline/polygon/text）は元の属性値を壊さず数値編集のみ行う。不要な再生成はしない。
- rect等のプリミティブはSVGの属性（x/y/rx/ry等）として編集する。「パスへ変換」で path 要素へ昇格する。
- スタイルは `style=""` 属性と個別プレゼンテーション属性の両方を編集する。優先順位は style属性 > 個別属性。

## 3. 座標系／ビューポートの分離

- `SvgDocument` は `viewBox`（minX/minY/width/height）を編集単位系として正規化し、画面座標と厳密に分離する。
- CanvasView は screen = user × matrix の変換行列を1本のみ保持し、拡縮・パンはこの行列のみを変更する。編集データには一切触れない。
- ノード／ハンドルの当たり判定は画面距離（指で約14dp、ペンで約6dp）で行い、拡大率にかかわらず選択性を確保する。編集位置はすべて user 単位系で扱う。
- スナップ（グリッド／オブジェクト）は編集単位系で行う。

## 4. Undo / Redo

コマンドパターンによる `UndoStack`。各編集操作はドキュメント差分（逆操作つき）として記録する。

- ドラッグ等の連続操作は、開始時状態のみを記憶して履歴を1件に統合する。
- 履歴上限400件。古いエントリは破棄し、メモリ使用を安定させる。

## 5. 高度なパス操作

JTSを用いる（パスを許容誤差でフラット化してから演算）。

- union / difference / intersection / xor: OverlayOp → 結果ジオメトリを subpath 化して新規 path 要素として置換
- offset: BufferOp（round join／round cap、ストロークのオフセット近似）
- simplify: DouglasPeuckerSimplifier

自前実装の部分:

- 分割: ベジェの de Casteljau 分割
- 結合: 選択パスの subpath 合算
- 反転／方向変更
- ストロークのアウトライン化: `Paint.getFillPath`（Android API）
- テキスト→パス: `Paint.getTextPath`

## 6. 入力／操作体系

| 入力 | select | node | pen | shape | hand |
|---|---|---|---|---|---|
| 指／マウス | 選択・変形ハンドル | ノード・ハンドル操作 | 作図 | 作図 | パン |
| 2本指 | 常にパン＋ピンチ（ペン作図中を除く） | 同左 | 同左 | 同左 | 同左 |
| スタイラス | 選択・変形 | ノード・ハンドル | 作図 | 作図 | パン |
| hover（stylus） | カーソル表示 | ノードハイライト | 補助線 | 補助線 | カーソル |

- 「ペン作図モード」（デフォルトON）で、指はパン／ズームのみ、作図はペン専用に分離できる。
- 2本指ジェスチャはツール操作より常に優先してパン／ズームを処理する。

## 7. UI配置

- タブレット（大画面）: 上部バー／左縦ツールレール／右サイドパネル（レイヤー／プロパティ切替）
- スマホ（小画面）: 上部バー／下部横ツールバー／ボトムシートでパネル切替
- ダークテーマ対応、システム設定に追従

## 8. ファイル I/O

- SAF（ACTION_OPEN_DOCUMENT / CREATE_DOCUMENT）で端末内SVGを読み書き。
- 未保存変更がある場合はダイアログで明示確認を取る。
- 自動保存はメモリ上のセッション復元のみ（プロセス生存中）。プロセス死後の復元は未実装（既知制約）。

## 9. テスト（core モジュール / JVM）

- SVG round trip（parse→serialize の同一性維持）
- パスデータの解析／生成
- Matrix2D／viewBox 変換
- ベジェ分割・反転・スムージング
- ブーリアン・オフセット・単純化（JTS）
- UndoStack の統合挙動（ドラッグ）
- rect→path 変換

## 10. 既知の制約（初版）

- `<style>` 内のCSSは解析せず、テキストとして保持のみ。
- `use` は同ドキュメント内参照のみ解決。外部hrefはセキュリティ上禁止。
- clipPath は簡易描画対応、mask は保持のみで描画はしない。
- filter／SMILアニメーションは保持のみで描画しない。
- 外部画像は data URI／埋め込みのみ描画。ネットワーク参照は行わない。
