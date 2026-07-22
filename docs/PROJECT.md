# わるん会計 システム概要

この文書は、新しく参加した開発者やAIが、現在のシステム構成、データの流れ、実装済み範囲、未実装範囲を短時間で把握するための入口です。詳細な作業規約は `AGENTS.md`、今後の優先順位と完了条件は `docs/ROADMAP.md` を参照してください。

- 基準日: 2026-07-22
- 基準ブランチ: `feature/ui-foundation`
- 基準HEAD: `79e2e24 docs: add development roadmap`
- Android本体: `app/`
- ルートの `index.html`、`styles.css`、`app.js` は参考用Webプロトタイプ

現状判断はコード、テスト、Room schema、Git履歴を優先します。`README.md` のCameraX・ML Kit OCRを未実装とする記述は現在の実装と一致しません。

## プロジェクト概要

### アプリの目的

「わるん会計」は、小規模な個人経営の飲食店向けAndroidアプリです。売上、支出、現金残高、レシート情報を端末内で整理し、日々の経営状態を分かりやすく把握しながら、月末に税理士へ渡す資料を準備できる状態を目指します。

確定申告書を自動作成するアプリではありません。単なる帳簿入力にとどまらず、店主が5秒で経営状態を把握できる経営支援アプリを将来像としています。

### 想定利用者

- 日々の記帳へ多くの時間を割けない、小規模飲食店の店主。
- 現金、クレジット、電子マネー、掛け等の支出を整理したい利用者。
- 月次資料と証憑を税理士へ安全に渡したい利用者。
- 現在の実装を保守・拡張する開発者、Codex、その他のAIエージェント。

### 開発方針

- 入力負担をOCR、候補提示、入力保持、安全な自動化で減らす。
- OCR候補や計算上の推定値を、ユーザー確認なしに確定しない。
- 入力途中のデータ、保存済み会計データ、証憑画像を失わない。
- 支出を重複保存・重複集計しない。
- Room Migrationを既存ユーザーデータとの互換契約として扱う。
- 将来のレシート、領収書、通帳、請求書、その他資料を共通管理できる余地を残す。

## システム構成

### 技術スタック

| 領域 | 現在の構成 |
| --- | --- |
| 言語・UI | Kotlin 2.0.21、Jetpack Compose、Material 3 |
| Android | AGP 8.7.3、compileSdk/targetSdk 35、minSdk 26、Java/JVM 17 |
| DI・状態 | Hilt 2.52、ViewModel、Coroutines/Flow、SavedStateHandle |
| DB | Room 2.6.1、KSP、`warun-accounting.db`、schema version 10 |
| カメラ | CameraX 1.5.3 |
| OCR | ML Kit Japanese Text Recognition 16.0.1 |

### レイヤー

```text
Compose UI / Navigation
        ↓
ViewModel / UI state
        ↓
Repository interface
        ↓
OfflineRepository
        ↓
Room DAO / Transaction
        ↓
Room database
```

CameraX、ML Kit、ReceiptParserはこの保存レイヤーと責務を分離しています。UIからRoomやML Kitを直接呼ばず、カメラ制御、OCR、候補解析、確認、永続化をそれぞれ独立した責務として扱います。

### 主要データ

#### DailyReport

1日の日報を表すRoom Entityです。主に次を保持します。

- 日報日、下書き／完了ステータス、記入者。
- 現金、カード、QR、売掛、その他の売上。
- 水道光熱費、通信費、家賃、税理士顧問料等の日報内支出。
- 営業開始時現金、実際の終了時現金、来客数、組数、メモ。
- 作成・更新時刻。

詳細支出へ移行したカテゴリでは `ExpenseRecord` を優先し、対応レコードがない場合だけDailyReportのlegacy金額をfallbackとして使います。両方を加算して二重計上しません。

#### ExpenseRecord

1件の支出明細を表すRoom Entityです。

- 支出日 `expenseDate`。
- カテゴリ、支払先、金額、支払方法、メモ。
- 任意の `receiptId`。
- 手入力、レシート、legacy移行等を表す `sourceType`。
- 作成・更新時刻。

支出は個別保存でき、日報と同日であれば日報とのTransaction保存もできます。`expenseDate` と `DailyReport.reportDate` は別の意味を持ちます。

#### ReceiptRecord

レシートの確認情報を表すRoom Entityです。

- 購入日、撮影日相当の日付、登録時刻。
- 店舗名、合計金額、税額、登録番号。
- 支出カテゴリ、確認済み状態、メモ。

通常のReceiptRecord保存、およびReceiptRecordとExpenseRecordのTransaction保存経路は存在します。ただし、現在のOCRレビュー経路はReceiptRecordを保存しません。また、ReceiptRecord自体は正式画像のパスや共通証憑参照を持っていません。

#### 補助データ

- `SupplierCandidateRecord`: カテゴリ別の支払先候補と既定支払方法。
- `MonthlySubmission`: 月別の提出状況。
- `AppSettings`: 店舗情報と利用する決済方法。

### Room

- `WarunDatabase`の現在versionは10。
- schema JSONはversion 6、7、8、9、10を保持。
- `MIGRATION_6_7`、`MIGRATION_7_8`、`MIGRATION_8_9`、`MIGRATION_9_10`を明示登録。
- destructive migrationは使用しない。
- DAOにはFlowによる監視、単体保存、削除、日報＋支出、ReceiptRecord＋支出のTransactionがある。

既存Migrationと既存schema JSONは変更せず、新しいschema変更には新version、新Migration、新schema JSON、Migrationテストが必要です。

### Repository

`AccountingRepository`が保存・取得契約を定義し、`OfflineAccountingRepository`がRoom DAOへ委譲します。

主な責務:

- 日報、支出、ReceiptRecord、支払先候補、月別提出状況、設定のFlow監視。
- 日報、支出、ReceiptRecord、設定等の保存・削除。
- 日報＋支出、ReceiptRecord＋支出のTransaction経路の維持。

ComposeからDAOを直接呼ばず、ViewModelからRepositoryを経由します。

### ViewModel

- `DashboardViewModel`: RoomのFlowを集約し、日報、支出、ReceiptRecord、設定等をUI状態へ提供する。保存要求をRepositoryへ渡す。
- `InputStateViewModel`: 日報入力、支出下書き、Receipt入力、OCR反映後のcapture参照をSavedStateHandleで保持する。
- `ReceiptCameraViewModel`: Idle、Initializing、Previewing、Capturing、Captured、Errorの純粋なカメラUI状態を管理する。
- `ReceiptOcrViewModel`: OCRの実行、成功・空結果・失敗、多重実行防止、Parser結果、編集レビュー、状態復元を管理する。

Activity、Context、View、PreviewView、LifecycleOwnerはViewModelへ保持しません。

### CameraX

`ReceiptCameraController`がPreviewとImageCaptureのbind、撮影、unbindを担当します。`ReceiptCameraScreen`が権限、ライフサイクル、戻る、キャンセル、再撮影、画像採用を調整します。

撮影結果は `ReceiptCaptureResult` の `captureId`、`localUri`、`capturedAt`だけをNavigationへ返します。Bitmap、ByteArray、画像本体は渡しません。

`ReceiptImageStore`はアプリ内部の `receipt-images/pending` を管理します。pending画像は一時ファイルであり、正式証憑ではありません。

### ML KitとOCR処理

`MlKitReceiptOcrGateway`がpending JPEGを `InputImage`として読み込み、日本語モデルでrawTextを取得します。

`ReceiptParser`はAndroid APIに依存しないKotlinクラスで、OCR全文から次の候補を抽出します。

- 店舗名と支店名。
- 購入日時。
- 合計金額。
- confidence、根拠行、抽出理由、代替候補。

`ReceiptOcrReviewState`と`ReceiptOcrPanel`で候補を編集・確認します。低confidence候補は明示確認を必要とし、未検出項目は手入力できます。

## データの流れ

### 日報・支出

```text
日報・支出入力
  ↓
InputStateViewModelで入力途中の状態を保持
  ↓
DashboardViewModel
  ↓
AccountingRepository
  ↓
WarunDao / @Transaction
  ↓
Roomへ保存
  ↓
Flowで再読込
  ↓
日次・月次・期間集計
  ↓
基本サマリー表示
  ↓
経営指標基盤（予定）
  ↓
税理士向けファイル出力（予定）
```

日報と同時に未保存支出を保存する場合、支出日と日報日が一致する支出だけをTransaction対象にします。日付不一致の未保存支出がある場合は一括保存を拒否し、個別保存へ誘導して下書きを保持します。

### レシートOCR

```text
CameraX撮影
  ↓
pending JPEG / ReceiptCaptureResult
  ↓
ML Kit日本語OCR
  ↓
rawText
  ↓
ReceiptParser
  ↓
店舗名・購入日時・合計金額の候補
  ↓
ユーザーがレビュー・編集・確認
  ↓
支払先・支出日・金額をExpenseInputへ反映
  ↓
ユーザーが個別保存
  ↓
ExpenseRecord
  ↓
正式証憑保存・関連付け（予定）
```

OCR反映時に保持されるのはcapture参照であり、画像は正式領域へ移動せず、ExpenseRecordの `receiptId`にも自動設定されません。「支出入力へ反映」はExpenseRecord保存でも証憑保存でもありません。

## 現在実装済み

コードとテストから、次を確認できます。

- ホーム、日報入力、レシート、収支確認、月別整理、日報一覧、税理士提出、設定の画面。
- 任意日の日報入力、下書き／完了保存、売上内訳、現金管理、営業情報。
- ExpenseRecordによる支出明細、支払先候補、支払方法、個別保存・編集・削除。
- 現金支出だけを現金残高から差し引く計算。
- ExpenseRecord優先とlegacy fallbackによる支出の二重計上防止。
- 未保存入力の画面遷移ガードとSavedStateHandleによる入力保持。
- 支出日と日報日が異なる未保存支出の一括保存ブロック。
- CameraXによる背面カメラのプレビュー、JPEG撮影、再撮影、キャンセル、権限状態、多重撮影防止。
- pending画像の生成・削除と、7日より古い未確定画像の清掃。
- ML Kitによる日本語OCR rawText取得。
- ReceiptParserによる店舗名、購入日時、合計金額の候補抽出。
- OCR候補の確認・編集、代替候補、根拠、confidence、rawText表示。
- 支払先、支出日、金額だけのExpenseInput反映と二重反映防止。
- 今日・今月の売上、支出、差額、現金関連値、客単価等の基本表示。
- Room version 10とschema 6〜10のMigration経路。
- JVMテストとAndroidテストによる計算、状態保持、OCR、Parser、Transaction、Migrationの検証基盤。

## 未実装

詳細と優先順位は `docs/ROADMAP.md` を参照してください。現在の主な未実装範囲は次のとおりです。

- pending画像の正式証憑領域への安全な移動・再読込。
- 正式証憑の共通モデル、保持期間、安全な削除、複数ページ対応。
- OCR画像、ReceiptRecord、ExpenseRecordの正式な関連付け。
- OCR経路からのReceiptRecord保存とExpenseRecord自動保存。
- OCRによるカテゴリ・支払方法の自動判定。
- 原価率、固定費回収率、損益分岐、目標残額等の統一計算基盤。
- 5秒で経営状態を把握するための完成版経営ダッシュボード。
- CSV、PDF、証憑ZIP、メール共有等の税理士向け実ファイル出力。
- 通帳画像管理。
- クラウドバックアップ、同期、復元。
- 外部AI API連携。

`FutureFeatureContracts.kt`に契約や候補がある機能も、Hilt bindingや実装がなければ実装済みとは扱いません。

## 設計原則

### 最小差分

- ユーザーが指定した範囲だけを変更する。
- 無関係なリファクタリング、依存更新、UI変更を混ぜない。
- dirty worktreeの既存差分をユーザーの変更として保護する。

### Migration重視

- Room schema変更には、新version、新Migration、新schema JSON、Migrationテストを揃える。
- 既存Migrationや既存schema JSONを書き換えない。
- `fallbackToDestructiveMigration`を導入しない。

### 会計データ保護

- 金額は円単位の `Long`で扱う。
- Transactionが必要な保存を分割しない。
- 保存失敗時に入力を消さない。
- ExpenseRecordとlegacy金額を二重計上しない。
- `expenseDate`と`reportDate`を無断で揃えない。

### 推定値と確定値

- 理論値、概算値、OCR候補、低confidence候補を確定値と区別する。
- 棚卸や不足データを反映していない差額を確定利益と表現しない。
- ユーザー入力への反映とRoom保存を別の操作として扱う。

### OCR結果

- OCR rawTextとParser候補は確定会計データではない。
- ユーザーがレビューして明示的に反映するまで確定しない。
- pending画像を正式保存済み証憑と表示・報告しない。

### 既存データを失わない

- 未保存ガード、SavedStateHandle、pending画像の削除条件を維持する。
- 保存・Migration・削除の変更では失敗時と再試行時を検証する。
- データ消失、二重保存、後方互換性破壊の可能性がある場合は実装を止め、影響と選択肢を確認する。

## 将来構想

### 正式証憑管理

pending画像を失敗安全に正式化し、再起動後も参照できるようにします。その後、レシート、領収書、通帳、請求書、その他資料を扱える共通証憑モデルとExpenseRecordとの関連付けを検討します。モデル名とRoom変更は未確定です。

### 経営ダッシュボード

今日と今月の売上、推定利益、原価率、固定費回収、損益分岐、客単価、目標残額等を、推定／確定区分と算出根拠付きで表示する構想です。計算ロジックはUIから分離し、0除算、欠損、棚卸未反映を安全に扱います。

### 税理士提出

月次単位でCSVと証憑ZIP等を生成し、出力前に期間、件数、金額、未確認データ、証憑不足を検証する構想です。現在は月別整理と提出状況のUI基盤のみで、実ファイル出力はありません。

### クラウドバックアップ

ローカル保存とバックアップを分離し、ネットワーク障害でも入力を継続できる構成を目指します。Google Drive等は候補であり、特定サービス、正本の位置、認証、競合解決、保持方針は未確定です。

## 主な参照先

- 作業規約: `AGENTS.md`
- 開発ロードマップ: `docs/ROADMAP.md`
- 既存概要: `README.md`
- Room Entity: `app/src/main/java/com/warun/accounting/data/local/Entities.kt`
- Database・DAO・Migration: `WarunDatabase.kt`、`WarunDao.kt`、`AppModule.kt`
- Repository: `AccountingRepository.kt`、`OfflineAccountingRepository.kt`
- 入力・保存状態: `DashboardViewModel.kt`、`InputStateViewModel.kt`
- UI・Navigation: `WarunApp.kt`
- Camera: `camera/`、`ui/receipt/ReceiptCameraScreen.kt`
- OCR・Parser・レビュー: `ocr/`、`ocr/parser/`、`ui/receipt/`

この文書は現在地の概要であり、未確定事項の仕様承認や将来Phaseの実装承認を代替しません。
