# わるん会計 システム概要

この文書は、新しく参加した開発者やAIが、現在のシステム構成、データの流れ、実装済み範囲、未実装範囲を短時間で把握するための入口です。詳細な作業規約は `AGENTS.md`、今後の優先順位と完了条件は `docs/ROADMAP.md` を参照してください。

- 基準日: 2026-07-29
- 基準ブランチ: `feature/ui-foundation`
- 基準HEAD: `31fd7ce Add prepaid expense cancellation UI and audit view`
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
| DB | Room 2.6.1、KSP、`warun-accounting.db`、schema version 16 |
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

#### プリペイド台帳と取消

- `PrepaidAccountRecord`: プリペイド口座。
- `PrepaidTransactionRecord`: CHARGE、PURCHASE、REVERSALを記録する不変台帳。
- `ExpensePrepaidLinkRecord`: ExpenseRecordと現在のPURCHASEを関連付ける。
- `ExpenseEditOperationRecord`: 保存済み支出編集のoperationKey、fingerprint、完了状態を保持する。
- `ExpenseCancellationRecord`: 元Expense、取消理由と永続的冪等性情報を保持し、プリペイド取消の場合だけ元PURCHASEとREVERSALを関連付ける。

保存済みExpenseは物理削除せず、取消時も元ExpenseとEvidenceを保持して通常表示と監査表示を分離します。プリペイドではPURCHASEを物理削除・上書きせず、編集・取消をREVERSALと必要な新PURCHASEで記録します。非プリペイド取消はプリペイド台帳を操作しません。

#### 補助データ

- `SupplierCandidateRecord`: カテゴリ別の支払先候補と既定支払方法。
- `MonthlySubmission`: 既存の月別紙提出記録。過去記録として保持する。
- `ElectronicSubmissionRecord`: 月次ファイルの作成内容、MyKomon提出状態・日時、備考。キャッシュURIや実ファイルパスは保持しない。
- `AppSettings`: 店舗情報と利用する決済方法。

### Room

- `WarunDatabase`の現在versionは16。
- schema JSONはversion 6〜16を保持。
- `MIGRATION_6_7`〜`MIGRATION_15_16`を明示登録。
- 13→14は`expense_cancellations`と一意Indexだけを追加し、既存テーブルへのALTER／UPDATE／backfillは行わない。
- 14→15は既存取消行、FK、UNIQUE indexを保持し、プリペイド台帳参照をnullable化する。
- 15→16は`electronic_submission_records`と対象月・作成日時のIndexだけを追加し、既存テーブルへのUPDATE／backfill／削除は行わない。
- `fallbackToDestructiveMigration`は使用しない。
- DAOにはFlowによる監視、単体保存、削除、日報＋支出、ReceiptRecord＋支出のTransactionがある。

既存Migrationと既存schema JSONは変更せず、新しいschema変更には新version、新Migration、新schema JSON、Migrationテストが必要です。

### Repository

`AccountingRepository`が保存・取得契約を定義し、`OfflineAccountingRepository`がRoom DAOへ委譲します。

主な責務:

- 日報、支出、ReceiptRecord、支払先候補、月別提出状況、設定のFlow監視。
- 日報、支出、ReceiptRecord、設定等の保存・削除。
- 日報＋支出、ReceiptRecord＋支出のTransaction経路の維持。
- プリペイド支出の新規保存と保存済み支出編集、および全支払方法の論理取消を、必要な台帳・Link・Evidence・operation記録とともにTransaction処理する。
- 取消済みExpenseの監査取得と、active Expenseの通常取得を分離する。
- 電子提出の月次Snapshotと履歴は専用Repositoryを通し、UIから直接集計しない。

ComposeからDAOを直接呼ばず、ViewModelからRepositoryを経由します。

### ViewModel

- `DashboardViewModel`: RoomのFlowを集約し、日報、支出、ReceiptRecord、設定等をUI状態へ提供する。保存要求をRepositoryへ渡す。
- `InputStateViewModel`: 日報入力、支出下書き、Receipt入力、OCR反映後のcapture参照をSavedStateHandleで保持する。
- `ReceiptCameraViewModel`: Idle、Initializing、Previewing、Capturing、Captured、Errorの純粋なカメラUI状態を管理する。
- `ReceiptOcrViewModel`: OCRの実行、成功・空結果・失敗、多重実行防止、Parser結果、編集レビュー、状態復元を管理する。
- `PrepaidViewModel`: 口座、残高、チャージ、プリペイド支出入力を管理する。
- `ExpenseCancellationViewModel`: 取消snapshot、operationKey、理由、保存中状態、失敗分類、再送をSavedStateHandleとともに管理する。
- `ExpenseCancellationAuditViewModel`: 日付別の取消済み支出と台帳・Evidenceの監査表示を提供する。

Activity、Context、View、PreviewView、LifecycleOwnerはViewModelへ保持しません。

### CameraX

`ReceiptCameraController`がPreviewとImageCaptureのbind、撮影、unbindを担当します。`ReceiptCameraScreen`が権限、ライフサイクル、戻る、キャンセル、再撮影、画像採用を調整します。

撮影結果は `ReceiptCaptureResult` の `captureId`、`localUri`、`capturedAt`だけをNavigationへ返します。Bitmap、ByteArray、画像本体は渡しません。

`ReceiptImageStore`はアプリ内部の `receipt-images/pending` を管理します。pending画像は一時ファイルであり、正式証憑ではありません。

支出保存時はPhase 4Aのジャーナルと`EvidenceFileStore`がpending JPEGを正式領域へ昇格させ、`EvidenceRecord`へ保存先、サイズ、SHA-256、状態を記録します。`ExpenseEvidenceLinkRecord`がExpenseRecordとEvidenceを関連付けます。

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
正式Evidence保存
  ↓
ExpenseEvidenceLinkRecordによる関連付け
```

OCR反映時点で保持されるのはcapture参照だけです。「支出入力へ反映」はExpenseRecord保存でも証憑保存でもありません。ユーザーが支出を保存した後に正式化と永続リンクを行い、`receiptId`は変更しません。

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
- pending JPEGの失敗安全な正式化、SHA-256検証、ジャーナルによる再起動復旧。
- `EvidenceRecord`と`ExpenseEvidenceLinkRecord`によるExpenseRecordとの永続リンク。
- 保存済み支出からのレシート件数表示、画像表示、ピンチ拡大・移動。
- 今日・今月の売上、支出、差額、現金関連値、客単価等の基本表示。
- 新規プリペイド支出のExpense／PURCHASE／Link／EvidenceのTransaction保存。
- 保存済み支出の金額・口座・プリペイド属性変更、REVERSALと新PURCHASE、Link遷移、Evidence追加、永続的冪等性、SavedState復元。
- 全支払方法の保存済み支出の論理取消、通常一覧・集計からの除外、legacy fallback再計上防止、日報詳細の監査表示。プリペイドだけREVERSALで残高を復元する。
- 月次の日報Excel、支出明細Excel、stored EvidenceのレシートPDF作成、SAF保存、Android共有、MyKomon手動提出の履歴記録。既存の紙提出記録も過去記録として表示する。
- Room version 16とschema 6〜16のMigration経路。v15バックアップはstaging候補内でEvidence URIを再割当してから15→16 Migrationと同じSQLでv16へ更新・再検証し、新規バックアップはv16で作成する。
- JVMテストとAndroidテストによる計算、状態保持、OCR、Parser、Transaction、Migrationの検証基盤。

## Phase C-3 完了記録

### C-3A 新規プリペイド支出

- 新規ExpenseRecord、PURCHASE、ExpensePrepaidLinkを作成する。
- Expense、Evidence、プリペイド台帳を同一の保存経路でTransaction処理する。

### C-3B 保存済み支出編集

- 金額変更、プリペイド口座変更、プリペイドから非プリペイド、非プリペイドからプリペイドへの変更に対応する。
- 元PURCHASEを不変に保ち、REVERSALと必要な新PURCHASEを追加してExpensePrepaidLinkを遷移させる。
- Evidence追加、operationKey／request fingerprintによる永続的冪等性、SavedState復元に対応する。

### C-3C 保存済みプリペイド支出の論理取消

- `ExpenseCancellationRecord`で元Expense、元PURCHASE、REVERSALを追跡する。
- 元Expense、元PURCHASE、ExpensePrepaidLink、Evidenceを保持し、REVERSALで残高を復元する。
- 同一operationKey・同一fingerprintの再送を冪等化し、Conflict、AlreadyCancelled、StaleState、台帳不整合、DB失敗を区別する。
- Expense取消記録、REVERSAL、operation完了を同一Room Transactionで保存し、失敗時はrollbackする。
- 取消済みExpenseの編集・再取消を拒否し、通常一覧・日次・月次・期間・現金・税理士提出・経営分析から除外する。
- active Expenseがなく取消済みExpenseだけがある日付・カテゴリでは0円とし、DailyReportのlegacy値を再計上しない。
- 取消Dialogと日報詳細の読取専用監査表示を実装し、Evidenceサムネイル、stored画像、ズーム、パンを再利用する。

### C-3完了時点の検証

- C-3完了時点ではJVMテスト328件PASS。
- Android Roomテストは各対象フェーズで、C-3B 10件、C-3C1 5件、C-3C2 11件、C-3C3A 2件、C-3C3B 3件、C-3C3D 14件PASS。対象が重複するため総数として単純合算しない。
- Debug APKとinstrumented test APKの生成PASS。
- A90ではInstrumentationを実行せず、通常版Debug APKの`adb install -r`と手動受入だけを実施した。
- A90の既存データを保持し、Room version 14、`integrity_check`の`ok`を確認した。
- A90 1200×1920でDialog表示、スクロール、IME、回転、取消、集計、監査、Evidence、再起動後の保持を確認した。
- 受入用プリペイド支出300円の取消で、au PAY残高4,700円から5,000円、majica残高5,400円不変、REVERSAL 1件、Cancellation 1件、EvidenceとSHA-256一致、未完了編集operation 0件、クラッシュなしを確認した。

### 既存pending 1件の読取専用調査

- 対象は`com.warun.accounting`の`/data/user/0/com.warun.accounting/files/receipt-images/pending/receipt_bacb947b-63a9-4d77-bfa5-044ec54af6c7.jpg`。3,053,403 bytes、2026-07-27 00:32:01 +09:00更新、2448×3264の正常なJPEGで、SHA-256は`ccd5f2d1dfb4bac0e583b825e27af5a1762a08337ee39f7c26ab6667eef8f759`。内容は2026-07-24、合計1,846円の受入前に撮影されたレシート。
- `warun-accounting.db`はuser_version 14。captureId、SHA、サイズが一致するEvidenceRecord、ExpenseEvidenceLink、Journalはなく、Journalディレクトリは空でquarantineもなかった。
- 同日・同店・同額のactive Expenseは別captureIdのEvidenceへ正常にLinkされ、同一レシートの別撮影がstoredへ正式保存済みだった。pendingとstoredはcaptureId、サイズ、SHAが異なり、stored側のDB、Link、ファイルは整合していた。
- 正式分類はE「判定不能」。永続データ上はC「孤立pending」が最有力で、約3分後に別captureIdで同じレシートが正式保存された旧撮影の後片付け漏れである可能性が高い。一方、SavedStateHandleまたはプロセス内の未保存入力が旧captureIdを所有していないことは読取専用調査では完全に証明できないため、孤立確定または削除可能とは扱わない。
- PC側query-only snapshotの`integrity_check`は`ok`。調査前後でpendingの件数、名前、サイズ、更新日時、SHA、DB／WAL／SHM、Journal 0件は不変で、A90上のデータは変更していない。
- 現在は保持し、手動削除、自動復旧の強制、Journal／EvidenceRecord／Linkの人工的な作成を行わない。このLow優先度の保守事項はPhase C-3完了判定へ影響しない。

## 未実装

詳細と優先順位は `docs/ROADMAP.md` を参照してください。現在の主な未実装範囲は次のとおりです。

- 正式Evidenceの保持期間、安全な削除、差し替え、複数ページ対応。
- 1支出へ複数画像を追加するUIとReceiptRecordへのEvidence関連付け。
- Phase 4Aで過去に保存された孤立画像の自動関連付け。
- OCR経路からのReceiptRecord保存とExpenseRecord自動保存。
- OCRによるカテゴリ・支払方法の自動判定。
- 原価率、固定費回収率、損益分岐、目標残額等の統一計算基盤。
- 5秒で経営状態を把握するための完成版経営ダッシュボード。
- MyKomonへの自動ログイン、自動アップロード、API連携（実装対象外。手動アップロード運用）。
- 通帳画像管理。
- クラウドバックアップ、同期、復元。
- 外部AI API連携。
- REFUND、部分返金、複数返金。
- プリペイド口座削除。
- ExpenseRecordの物理削除を伴う運用。
- Journal v2とスマホ最適化。

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
- PURCHASEを物理削除・上書きせず、編集・取消はREVERSALで履歴を残す。
- プリペイド取消でExpense、ExpensePrepaidLink、Evidenceを削除しない。
- 取消済みExpenseは通常集計から除外し、編集・再取消を許可しない。
- プリペイド残高はPURCHASE／REVERSALを含む台帳合計を正とし、複数レコード更新はRoom Transactionで扱う。
- 永続的冪等性はRoom上のoperation記録とrequest fingerprintを正とする。

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

pending画像の正式化、`EvidenceRecord`、ExpenseRecordとのリンク、再起動後のレシート再表示までは実装済みです。今後は削除・差し替え、複数画像追加、領収書・通帳・請求書等の種別と複数ページ構造を設計します。

### 経営ダッシュボード

今日と今月の売上、推定利益、原価率、固定費回収、損益分岐、客単価、目標残額等を、推定／確定区分と算出根拠付きで表示する構想です。計算ロジックはUIから分離し、0除算、欠損、棚卸未反映を安全に扱います。

### 税理士提出

月次単位で日報Excel、支出明細Excel、stored EvidenceのレシートPDFを別ファイルとして生成します。日報の売上合計は正式なBusinessMetric経路と照合し、支出明細は取消済みを除外した現在有効な`ExpenseRecord`だけを使用します。Evidenceが0件なら空のPDFは作りません。ファイルはSAFまたはAndroid共有で取り出し、利用者がMyKomonへ手動アップロードします。アプリは自動ログイン、自動送信、認証情報保存を行いません。

電子提出の作成・提出履歴は`ElectronicSubmissionRecord`へ追加保存し、同じ月の再作成も上書きしません。既存の`MonthlySubmission`は過去の紙提出記録として変更せず表示します。銀行明細PDFはアプリ外で用意します。

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
