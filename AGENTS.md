# AGENTS.md

このファイルはリポジトリ全体に適用する Codex 向け作業規約です。下位ディレクトリに、より具体的な `AGENTS.md` が追加された場合は、その配下では下位規約を優先してください。

## 基準時点と情報源

- 基準時点: 2026-07-29
- 基準ブランチ: `feature/ui-foundation`
- 作業開始時に `git rev-parse HEAD`、`git status --short`、`DEV_STATE.md`、実装コードを確認し、調査時点の状態を確定する。
- 仕様判断は、現在のソースコード、テスト、Room schema JSON、直近コミット、README の順に照合する。
- `README.md` の「まだ実装しないもの」には、すでに実装済みの CameraX と ML Kit OCR が含まれており、一部が古い。README だけを根拠に現状を後退させない。
- CI設定、正式なリリース手順、PR規約、コードフォーマッタ／Lintの必須設定は確認できていない。未確定事項として扱い、推測で追加しない。

## プロジェクトの目的

「わるん会計」は、小規模飲食店が売上、支出、現金残高、レシートを端末内で整理し、月末に税理士へ提出する資料を準備するための Android アプリです。確定申告書を自動作成するアプリではありません。

重要な品質目標は、入力途中のデータを失わないこと、会計金額を重複計上しないこと、OCR候補をユーザー確認なしに確定しないこと、既存データをMigrationで安全に引き継ぐことです。

## 技術構成とリポジトリ構造

- Android本体: `app/`
- 参考用Webプロトタイプ: `index.html`、`styles.css`、`app.js`。Android実装と混同しない。
- 言語／UI: Kotlin 2.0.21、Jetpack Compose、Material 3
- Android: AGP 8.7.3、compileSdk/targetSdk 35、minSdk 26、Java/JVM 17
- DI／状態管理: Hilt 2.52、ViewModel、Coroutines/Flow、SavedStateHandle
- DB: Room 2.6.1、KSP、DB名 `warun-accounting.db`、schema version 18
- カメラ: CameraX 1.5.3
- OCR: ML Kit Japanese Text Recognition 16.0.1
- Gradle Wrapper: 9.3.0
- 主要パッケージ:
  - `camera`: CameraX制御、撮影結果、pending画像管理
  - `ocr`: ML Kit OCR、画像参照、エラー処理
  - `ocr/parser`: Android非依存の候補抽出
  - `data/local`: Room Entity、DAO、Database
  - `data`: Repository契約と端末内実装
  - `ui/viewmodel`: 画面状態、入力保持、保存呼び出し
  - `ui/receipt`: カメラ、OCR確認、編集・反映UI
  - `ui/model`、`util`: 集計、金額、支払方法の共通ロジック
  - `future`: 将来機能の契約。実装済み機能との互換性を壊さない。

## 現在の主要機能

- ホーム、日報入力、レシート、収支確認、月別整理、日報一覧、税理士提出、設定
- 任意日の日報の下書き／完了保存、売上内訳、現金管理、営業情報
- `ExpenseRecord` による支出明細、支払先候補、支払方法、個別保存
- 日報と支出のTransaction保存、および未保存入力の画面遷移ガード
- 支出日と日報日が異なる未保存支出を一括保存せず、個別保存へ誘導する安全ガード
- Roomによる端末内永続化と、schema 6〜18のMigration
- CameraXによる支出レシート撮影、内部pending領域へのJPEG保存、再撮影／破棄時の清掃
- ML Kitによる日本語OCR全文取得
- OCR全文から店舗名、購入日時、合計金額の候補抽出
- OCR候補の確認・編集と、支払先・支出日・金額だけを既存支出下書きへ反映
- Phase 4AジャーナルとSHA-256検証を用いたpending画像の正式化
- `EvidenceRecord`と`ExpenseEvidenceLinkRecord`によるExpenseRecordとの永続リンク、および保存済みレシートの再表示
- プリペイド口座、CHARGE／PURCHASE／REVERSALの不変台帳、ExpenseRecordとの永続リンク
- 新規プリペイド支出、保存済み支出のプリペイド属性編集、全支払方法の保存済み支出の論理取消
- 取消済み支出の通常一覧・集計からの除外、legacy fallback再計上防止、日報詳細の監査表示
- SAFによるRoom v18＋正式Evidenceの検証可能な手動バックアップ／復元、v15／v17バックアップ復元互換、復元journalとrollback、別環境での正式Evidence URI再割当
- 税理士への紙提出済みローカル記録。実際に紙資料を渡した後だけ記録し、電子ファイル生成・メール送信・電子提出は行わない

Android Auto Backup／端末間転送は、Roomと正式Evidenceの部分復元を実運用保証にしないため無効化している。手動バックアップは現行v18を正式対象とし、v15／v17バックアップ復元互換を維持する。cache、OCR一時ファイル、pending撮影画像、Evidence finalization journalを含めない。

現時点では、OCRフローからのExpenseRecord自動保存およびReceiptRecord保存、正式Evidenceの削除・差し替え、複数画像追加UI、通帳画像管理、カテゴリ／支払方法の自動判定は未実装です。Phase 4Aで過去に正式化済みでも永続リンクがない孤立画像は自動関連付けしません。通常のReceiptRecord保存経路は既に存在するため、OCRフローの未実装事項と混同しないでください。

## 最小差分の原則

1. 作業前に `git status -sb`、`git log -1 --oneline`、必要に応じて現在ブランチとGitルートを確認する。
2. ユーザーが指定した範囲だけを変更する。無関係な命名変更、整形、依存更新、UI刷新、リファクタリングを同じ差分へ混ぜない。
3. 既存の公開契約、Entity、Repository経路、入力保持、Navigation、保存Transactionを優先して再利用する。
4. 変更前に呼び出し元、テスト、保存経路、集計への影響を追跡する。
5. dirty worktreeの既存差分はユーザーのものとして保持する。重なる場合は内容を確認し、上書きやresetをしない。
6. 問題修正は再現原因に対する最小の差分に限定し、別フェーズの機能を先回りしない。
7. READMEと実装が矛盾する場合は、実装・テスト・直近履歴を調べ、矛盾を報告する。推測だけでどちらかへ統一しない。

## Codexが自動実行してよい作業

ユーザーが依頼した作業範囲内では、以下を追加確認なしで進めてよい。

依頼が調査、レビュー、診断、説明、状態報告に限られる場合は、読み取り中心の確認だけを行い、コード、設定、データ、外部状態を変更しない。実装、修正、作成、更新が依頼に含まれる場合だけ、その目的に必要な最小編集を行う。

- `rg`、Git、Gradle、ADB等による読み取り中心の調査
- 関連するソース、テスト、schema、履歴、差分の確認
- 明示された機能や不具合に必要な最小限のコード／テスト／ドキュメント編集
- `git diff --check`、対象JVMテスト、全JVMテスト、Debugビルド、Androidテストのコンパイル
- 失敗したテストやビルドの原因調査、および依頼範囲内の最小修正
- 接続済みテスト端末の確認、logcat取得、スクリーンショット、アプリ内部のテスト用pending画像確認。ただし実機操作が依頼範囲に含まれる場合に限る。

次の操作は、明示的に依頼されていない限り自動実行しない。

- commit、push、PR作成、履歴書き換え
- 実端末のアプリデータ消去、アンインストール、DB削除、設定の恒久変更
- 外部サービスへの送信、課金を伴う操作、秘密情報の設定
- 既存データを変更する手動DB操作

## Safe batching and concise reporting

Within a bounded stage, batch only independent, read-only inspections when the available execution tool supports it.

Parallel execution is allowed only when all operations:

- are read-only,
- do not modify the Git working tree or index,
- do not invoke Gradle builds or tests,
- do not access the same database, generated output directory, cache, or mutable resource,
- do not install, uninstall, launch, or modify apps on a device,
- do not use ADB against the same device,
- do not depend on one another,
- and cannot affect the next decision.

When partial failures are acceptable and every result remains useful, use a batch mechanism equivalent to Promise.allSettled and inspect every result individually.

Use an all-or-fail batch mechanism equivalent to Promise.all only when any single failure must abort the entire batch.

Do not split otherwise batchable, independent, read-only inspections into repeated outer tool calls without a safety or dependency reason.

Keep the following sequential:

- Git mutations such as add, commit, push, merge, rebase, reset, restore, and clean,
- code or file modifications,
- Gradle builds and tests,
- Room Migration tests,
- operations using the same database, build directory, cache, generated output, or mutable file,
- APK installation and device verification,
- all ADB operations against A90,
- Evidence, pending, stored, Journal, Recovery, and database operations,
- approvals, waits, retries, resumes, and adaptive investigations,
- conflicting or interdependent mutations,
- any operation whose result determines the next step.

Do not run multiple Gradle invocations concurrently in this repository.

Do not run concurrent operations against A90.

Do not run Git index or working-tree mutations concurrently.

When safety, independence, or shared-resource access is uncertain, run sequentially.

## Token and output efficiency

Keep routine progress updates brief.

Do not restate unchanged repository context, safety rules, previously confirmed facts, or full task requirements.

Report only:

- new findings,
- failures,
- decisions,
- material risks,
- user actions required,
- and final results.

Avoid repeating full command output unless it is required to explain an error, prove a safety condition, or support a decision.

Summarize successful routine checks instead of reproducing their full output.

Batch related read-only inspections when safe, rather than repeating separate searches or status checks.

Reuse already confirmed facts within the same task instead of re-reading or re-reporting them without a reason.

Do not repeat file contents, diffs, logs, or test results that have not changed.

For successful routine work, use a compact final report.

For failures or safety incidents, include the relevant evidence and stop condition in sufficient detail.

Never reduce validation, skip required checks, or combine unsafe operations merely to save tokens.

Safety, correctness, data integrity, and auditability take priority over speed and token reduction.

## ユーザー確認が必要な停止条件

以下に該当する場合は、安全な読み取り調査まで行い、変更を進めずに根拠と選択肢を報告する。

- 指定された作業ディレクトリ、ブランチ、最新コミット、clean状態が期待値と一致しない。
- ユーザーの未コミット差分と変更対象が競合し、安全に分離できない。
- Room schema、Database version、Entity、Migration、Repository保存方式を変える必要が生じた。
- 会計計算、保存対象、日付の意味、重複排除などの業務仕様に複数の妥当な解釈がある。
- データ消失、二重保存、Migration競合、後方互換性破壊の可能性がある。
- 必要な認証情報、外部サービス、ネットワーク権限、追加のユーザー判断がないと完了できない。
- 実機固有の確認が必須だが端末が接続されていない、またはユーザーの目視／物理操作が必要である。
- 依頼範囲を超える機能追加や大規模リファクタリングが必要になる。

軽微で可逆な実装詳細は既存パターンに合わせて判断し、不要な確認待ちは増やさない。

## アーキテクチャと状態管理の原則

- UIからRoomやML Kitを直接呼ばず、既存のViewModel、Gateway、Repository、DAOを通す。
- `Activity`、`Context`、`View`、`PreviewView`、`LifecycleOwner`をViewModelに保持しない。
- CameraXのbind/unbindとPreviewView接続はController／画面ライフサイクルで管理する。
- OCRとReceiptParserはCameraControllerへ混在させない。ParserはAndroid API非依存を維持する。
- Bitmap、ByteArray、画像本体をNavigation引数へ渡さない。`captureId`／URI等の参照だけを渡す。
- SavedStateHandleで返す一度限りの結果は、受信後にconsume/removeして再処理を防ぐ。
- 支出入力からカメラ／OCRへ移動する前にInputStateViewModelへ編集中の値を反映し、戻った際にカテゴリ、支払方法、メモ等を失わない。
- OCR候補は自動確定しない。低confidence候補、上書き、未検出はユーザーに明示する。
- pending画像は、撮影失敗、再撮影、キャンセル、破棄、置換で削除する。採用して次フェーズへ渡す画像は勝手に削除しない。

## 会計計算の実装原則

- 金額は円単位の `Long` で扱う。`Float`／`Double`で金額計算しない。
- 売上合計は、現金、カード、QR、売掛、その他売上の合計とする。
- 支出合計は全支払方法のExpenseRecordを含む。
- 現金支出は `paymentMethod == "現金"` のExpenseRecordだけを含む。クレジット、電子マネー、掛け、null、空欄、不明値で現金残高を減らさない。
- 理論上の終了時現金は `営業開始時現金 + 現金売上 - 現金支出` とする。
- `hasActualClosingCash` により「実際の終了時現金が未入力」と「0円入力」を区別する。
- 計算で得た理論値・概算値、OCR／Parserの候補、confidence、預り額と釣銭からの推定値は、ユーザー入力または保存済みの確定値と区別する。UIと報告では「理論上」「概算」「候補」「未確定」等を明示し、確定済み・保存済みと表現しない。
- OCR／Parserの推定値を確定値へ変えるのは、ユーザーが内容を確認して明示的に反映した場合だけとする。低confidence候補や算術推定を無条件に反映しない。
- 同じ日付・カテゴリにExpenseRecordがある場合は、その合計を優先する。該当レコードがない場合だけDailyReportのlegacy金額をfallbackとして使い、両方を加算して二重計上しない。
- 期間集計は `DailyReport.reportDate` と `ExpenseRecord.expenseDate` をそれぞれ正しく絞り込み、日報のない支出も一度だけ集計する。
- `expenseDate` と `reportDate` は別の意味を持つ。OCR購入日を理由に日報日を自動変更しない。
- 日付不一致の未保存支出がある場合、日報一括保存を拒否して個別保存へ誘導し、下書きを保持する。個別保存後の日報保存で同じExpenseRecordを再保存しない。
- 日報と同時に支出を保存する場合は、既存の `saveDailyReportWithExpense` とDAOの `@Transaction` を維持する。
- 計算式やfallback条件を変える場合は、0件、現金以外、日付外、legacy併存、日報なし、重複防止をテストする。

## Room、Migration、保存仕様

- 現在のRoom schema versionは18。schema JSONは `app/schemas/com.warun.accounting.data.local.WarunDatabase/` の6〜18。
- `MIGRATION_13_14`は`expense_cancellations`と一意Indexを追加し、既存テーブルへのALTER／UPDATE／backfillは行わない。
- `MIGRATION_14_15`は既存取消行を全件保持したままプリペイド台帳参照をnullable化し、非プリペイド取消を架空の台帳IDなしで同じ監査テーブルへ保存できるようにする。
- 過去Migrationは既存ユーザーデータの契約である。既存Migrationや既存schema JSONを後から書き換えない。
- schema変更が承認された場合は、Database versionを1つ上げ、新しいMigrationを追加し、新schema JSONを生成し、Migrationテストを追加する。
- `fallbackToDestructiveMigration`を導入しない。
- Entity変更だけ、version変更だけ、Migrationなしの状態を作らない。
- 保存処理をComposeへ直接追加せず、ViewModel → AccountingRepository → DAOの経路を維持する。
- 複数レコードを一体として保存する処理はTransactionとし、途中成功を許さない。
- 保存済み支出編集は編集操作記録と編集本体を同一Room Transactionで処理し、同一operationKeyの異なるrequest fingerprintを拒否する。完了済み編集操作記録を上書きしない。
- プリペイド台帳のPURCHASEは物理削除・上書きしない。編集と取消はREVERSALを追加して履歴を残し、残高はPURCHASE／REVERSALを含む台帳合計を正とする。
- 保存済み支出の取消にExpenseRecordの物理削除を使わない。元Expense、EvidenceとそのLinkを保持し、プリペイドでは`ExpensePrepaidLinkRecord`と元PURCHASEも監査追跡用に保持する。
- 取消済みExpenseは通常一覧・集計から除外し、編集・再取消を許可しない。監査取得と通常取得を分離する。
- プリペイドの複数レコード更新はRoom Transactionで扱い、永続的冪等性はRoom上のoperation記録とrequest fingerprintを正とする。
- ReceiptRecord、ExpenseRecord、pending画像、`EvidenceRecord`は同一物として扱わない。ExpenseRecordとの関連は`ExpenseEvidenceLinkRecord`を通し、将来の証憑種別・複数ページ構造は未確定である。
- OCRのrawText・抽出候補・編集途中の値と、`receipt-images/pending` 配下の画像は一時状態であり、正式保存済みの会計データまたは正式証憑として扱わない。将来承認される正式化処理を経るまでは、Room保存や証憑保存が完了したと表示・報告しない。

## テスト方針

変更リスクに応じ、最低限次を実行する。

```powershell
git diff --check
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
.\gradlew.bat compileDebugAndroidTestKotlin
```

- 純粋KotlinのParser、計算、状態遷移、正規化はJVM単体テストを中心にする。
- ViewModel変更では、成功、失敗、空値、多重実行防止、SavedStateHandle復元、一度限りの消費をテストする。
- 入力経路変更では、既存値保持、対象項目だけの更新、戻る／回転、二重反映防止をテストする。
- ファイル管理変更では、生成、再撮影、破棄、失敗、古いpendingの清掃、採用画像の保持をテストする。
- 集計変更では `ExpenseCalculationsTest`、`Phase3AggregationTest` と関連instrumented testを確認し、二重計上と現金／非現金を重点検証する。
- Repository／DAOの複合保存変更では `TransactionSaveTest` 相当のAndroidテストを追加する。
- Room変更が承認された場合は、対象MigrationのAndroidテストとschema検証を必須とする。
- CameraX実撮影、権限、回転、レンズ方向、ML Kitの実レシート精度をJVMテストだけで成功扱いにしない。接続済み実機で確認するか、未確認として手順を提示する。
- 接続端末がある場合の実機テストは `adb devices -l` で対象を特定し、推測で端末名やOSを報告しない。
- `connectedDebugAndroidTest`は禁止する。Instrumentationは分離された
  `com.warun.accounting.instrumented`／`warun-accounting-instrumented.db`を対象とする
  `connectedInstrumentedAndroidTest`だけを、Android Emulatorまたは専用テスト端末で実行する。
- A90は手動受入確認専用とし、A90接続中は`connected*AndroidTest`を実行しない。
  A90への`adb install -r`は通常版の手動受入確認を明示された場合だけ行う。
- Instrumentation実行前に、対象APKとandroidTest manifestのtarget applicationIdが
  `com.warun.accounting.instrumented`であることを確認する。通常版
  `com.warun.accounting`をtargetにしたテストを実行しない。
- 失敗、skip、未実行は成功と分けて報告する。

テストのみの変更でも、少なくとも対象テストと `git diff --check` を実行する。ドキュメントのみの変更では、ビルドは通常不要だが `git diff --check` と差分範囲確認を行う。

## Git運用

- 作業開始時にブランチ、同期状態、working treeを確認する。
- 現在の主作業ブランチは `feature/ui-foundation` だが、ユーザー指定がある場合はそれに従う。勝手にブランチを切り替えない。
- commitメッセージは直近履歴に合わせ、英語の命令形で1つの論理変更を表す。
- commit前に `git diff --check` の成功、stage対象ファイルの全件、意図しない差分がないこと、変更リスクに応じた必須テスト／ビルドの結果を確認する。必須検証が失敗または未実行なら、その事実を報告し、ユーザーが状況を認識したうえで改めてcommitを指示しない限りcommitしない。
- commitとpushはユーザーが明示した場合のみ行う。push後は `git status -sb` で同期済みかつcleanであることを確認する。
- push前に、現在ブランチ、push対象commit hash、upstream、working treeを確認し、指定されたリモート／ブランチ以外へpushしない。
- GitHub CLI、PR作成、force push、rebase、履歴書き換えは明示依頼がない限り行わない。
- `git reset --hard`、無断のcheckoutによる破棄、他者差分のstash／削除を行わない。
- build生成物、APK、local.properties、IDE設定、秘密情報をcommitしない。

## 禁止事項

- 依頼されていないRoom schema、Database version、Migration、Entity、Repository、保存方式の変更
- 依頼されていない会計計算、集計対象、日付意味、支払方法判定の変更
- 安定している支出入力経路や未保存ガードを壊す変更
- OCR候補の無確認確定、OCR結果の自動保存、誤認識値による日報日の自動変更
- pending画像や支出下書きの無言削除
- 画像本体をNavigation／SavedStateHandleへ格納する実装
- CameraX、OCR、Parser、保存責務の密結合
- 正式証憑をレシート専用モデルへ固定する先行実装。将来はレシート、領収書、通帳、請求書、その他資料を共通管理する予定だが、モデル詳細は未確定。
- 外部AI、Supabase、CSV／PDF／ZIP、税理士共有、通帳機能を依頼なしに追加すること
- WebプロトタイプをAndroid本体の代わりに変更すること
- 秘密情報、実データ、レシート画像、DBスナップショットをログやGitへ含めること
- テスト未実行や実機未確認を成功と報告すること

## 作業完了時の報告形式

簡潔に、次の順で事実を報告する。

1. 結果: 実装／調査した内容と、依頼目的を満たしたか
2. 変更ファイル: 追加・変更したファイルと要点
3. 設計・安全性: 既存契約、入力保持、保存、集計、pending画像への影響
4. 検証: 実行したコマンド、成功件数、失敗／skip／未実行
5. 実機確認: 端末名、OS、実際に確認した項目。未確認項目は分離する。
6. 残存リスク／未確定事項: 推測せず、次に必要な判断や確認を示す。
7. Git状態: commit／pushを行った場合はhash、push結果、`git status -sb`。行っていない場合はその旨。

問題がなければ、不要な長文説明や将来フェーズの実装提案を追加しない。問題がある場合は、成功扱いにせず、再現条件と影響範囲を先に示す。

## AGENTS.mdの更新方法

- ユーザーが更新を依頼した場合、または許可された作業で構成・コマンド・安全規約が実質的に変わり、この規約の更新も作業範囲に含まれる場合だけ編集する。
- 更新前に、該当するソース、テスト、Gradle設定、Room schema、Git履歴を再確認する。READMEだけを根拠に現状を確定しない。
- 既存規約への最小差分とし、未確認事項を事実へ変えない。基準日、version、commit等は実際に確認できた値だけを更新する。
- 自動実行範囲、停止条件、データ保護、Git権限を緩める変更は、ユーザーの明示的な確認なしに行わない。
- AGENTS.mdだけの変更では、少なくとも `git diff --check`、`git diff -- AGENTS.md`、`git status -sb` で内容と変更範囲を確認する。未追跡ファイルで通常のdiffに表示されない場合は、ファイル内容またはno-index diffでも確認する。ビルドは通常不要である。
- AGENTS.mdのcommit／pushにも通常のGit運用規約を適用し、明示的な依頼なしに実行しない。

## 未確定事項

- 正式Evidenceの削除・差し替え、複数ページ通帳画像、保持期限
- ReceiptRecordとEvidenceの関連付け、および1支出へ複数画像を追加するUI
- Phase 4Aで過去に保存された孤立画像を安全に関連付ける方針
- リリース署名、配布、手動バックアップファイルの保管・世代管理、production DBの正式運用手順
- CI、必須Lint／フォーマット、コードレビュー、PRの正式ルール
- REFUND、部分返金、複数返金、プリペイド口座削除、Expense物理削除、Journal v2、スマホ最適化はPhase C-3の実装範囲外。
- A90に受入開始前から残るpending画像1件は、読取専用調査でも一時UI状態による所有を完全には否定できず、分類E「判定不能」とした。永続データ上は孤立pendingの可能性が高いが、削除可能とは扱わず、手動削除、自動復旧の強制、Journal／EvidenceRecord／Linkの人工的な作成を行わない。
- 将来pendingを孤立候補と判定する場合も、EvidenceRecord、ExpenseEvidenceLink、Evidence Journal、quarantine、現在の入力状態、SavedStateHandle、進行中のOCR／撮影／写真選択から参照されず、一定期間以上更新されていないことを確認し、削除直前に全参照を再確認する。候補検出後は直ちに削除せず、quarantine、猶予期間、再確認を経る設計を先に承認する。
- READMEの実装状況を更新する時期と範囲

これらを必要とするタスクでは、既存コードから推測して契約を固定せず、設計案と影響範囲を提示してユーザー確認を待つ。

## 作業時間見積り

- 各作業開始前に、概算所要時間を範囲で提示する。未実測の場合は推測値と明記し、主な変動要因を簡潔に示す。
- 見積り上限を超える見込みになった時点で、進捗、残作業、更新見積りを報告する。実機操作を依頼する場合も、その後の確認に必要な概算時間を提示する。
