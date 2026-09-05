# DEV_STATE

最終確認日: 2026-09-05

## 現在状態

- 基準ブランチ: `feature/ui-foundation`
- 現行リリース: `versionCode = 11`、`versionName = "0.2.9"`
- Room schema: v17。Room Entity、DAO、Migration、既存DB保存仕様は今回変更していない。
- Phase 2〜4の機能実装、自動検証、A90手動受入は完了し、各PhaseをPASSと判定した。
- 実装commitは作成済み。記録commit作成後もpushは行わず、次の作業はpush前監査とpush判断とする。

## Phase 2〜4完了記録

### Phase 2: 未確認Receiptの安全削除

- A90で未関連の未確認NTT Receiptに削除ボタンが表示され、固定費関連Receiptには表示されないことを確認した。
- 削除確認ダイアログの内容を確認後、「キャンセル」で終了し、実削除は行っていない。受入結果はPASS。

### Phase 3: 固定費Evidenceと日報ID同期

- 固定費Evidenceが`dailyReportId + fixedCostType`に一致する場合だけ、固定費証憑ボタンを`✓ 保存済み（再編集）`と表示し、該当しない場合は`証憑追加`を表示する。
- 日付手入力で`reportDate`だけを変更して古い日報IDを保持する経路を修正した。対象日に既存日報があれば実IDへ切り替え、なければ空IDを使う。
- 日報一覧のロード前に初期化された未編集入力は、ロード後に既存日報IDへ同期する。入力開始後の再同期は行わず、未保存状態を保護する。
- Pixel emulator（Android 14／API 34）で最終全体検証156/156 PASSした。
- A90で`2026-08-23`へ日付変更後、電気代`41,617円`と`✓ 保存済み（再編集）`を確認した。保存済み固定費証憑`1/1`、PDF`634,790 bytes`の表示も確認した。受入結果はPASS。

### Phase 4: 収支確認の税表示

- トキノ屋: 8%、税抜`16,260円`、消費税`1,300円`、税込`17,560円`。請求書とA90表示が一致し、PASS。
- サカツ: 10%、税抜`19,500円`、消費税`1,950円`、税込`21,450円`。A90表示と入力仕様確認資料が一致し、PASS。
- サカツの資料は`2026-09-03`納品書であり、2026年8月の3件そのものではない。この点を記録したうえで、10%外税の入力仕様確認根拠として扱った。
- Phase 4全体はPASSを確定した。

## 検証結果

- `testDebugUnitTest`: PASS。
- `assembleDebug`: PASS。
- `compileInstrumentedAndroidTestKotlin`: PASS。
- `git diff --check`: PASS。CRLF変換に関するGit警告のみで、whitespaceエラーはない。
- Pixel初回の全体実行は156件中155件PASS、1件が10秒タイムアウトした。アプリCrash、データ破損、アプリANRは確認していない。
- 初回タイムアウト対象を同じPixel・同じ一時作業コピーで単独再実行しPASS、その後に全体を再実行して156/156 PASSした。初回結果は「非再現タイムアウト」と記録し、フレークとは確定していない。
- A90ではInstrumentationを実行していない。Instrumentation対象はPixel emulatorだけである。

## Emulator環境とデータ保護

- Pixel起動時、共有`.android`設定先の書込み制限があったため、プロセス限定で`ANDROID_USER_HOME`と`ANDROID_EMULATOR_HOME`をリポジトリ外の一時ディレクトリへ指定し、`ANDROID_AVD_HOME`は既存AVD保存先を指定した。永続環境変数は変更していない。
- AVDの再作成・移動・設定変更、ACL・所有者・継承設定、ユーザーデータ、スナップショット、SDKは変更していない。
- A90の既存データを保持したまま通常Debug APKを`install -r`した。A90の確認済み件数は、日報35、Receipt 3、Expense 45、EvidenceRecord 45、固定費Application 1、固定費Link 1、Journal 0。更新前後で一致した。
- Stored Evidenceファイルは通常44件＋固定費PDF 1件の45件で、固定費PDFは`634,790 bytes`。正式バックアップは`110,028,298 bytes`、ZIP・manifest・DB・SHA検証PASSで保持している。
- `app/release/`、`output/`、`hs_err_pid3188.log`、APK、DB、Evidence、バックアップ、0バイトバックアップ2件、請求書／納品書画像、Pixel関連の一時ビルド・失敗成果物・Emulator一時ホームは変更・削除・stageしていない。添付画像はリポジトリへ保存していない。

## Gitと次の作業

- 実装commitメッセージ: `fix: synchronize fixed-cost evidence state`
- 記録commitメッセージ: `docs: record phase 2 to 4 acceptance`
- pushは未実施。記録commit後に可能なら`git fetch origin`だけを行い、branch、HEAD、ahead／behind、working tree、保護対象を再確認してからpush可否を判断する。
- commit、push、fetchの結果やSHAは、実行後のGit状態を正として報告する。

## 継続中の未確定事項

- 正式Evidenceの削除・差し替え、複数ページ通帳画像、ReceiptRecordとEvidenceの将来の関連付け、CIの必須設定、正式署名APKの配布手順は未確定である。
- これらは今回のPhase 2〜4完了判定の対象外であり、既存データを推測で変更しない。
