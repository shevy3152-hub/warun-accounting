# DEV_STATE

最終確認日: 2026-08-31

## Git基準

- ブランチ: `feature/ui-foundation`
- 実装開始時のHEAD: `89241cae34a978f7358bfd2483e44f4b72b81f03`
- 電子提出対応、Room v16、関連テスト・文書とversion更新はローカルcommit済みである。
- `output/manual/warun_accounting_manual.docx` と `output/pdf/warun_accounting_manual.pdf` はユーザー作成の未追跡ファイルとして保持し、変更・削除していない。
- Androidアプリの次回リリース準備 version は `versionCode = 3`、`versionName = "0.2.1"`。

## DB・Migration・バックアップ

- Room schema versionは16。
- `MIGRATION_15_16`は`electronic_submission_records`テーブルと`targetMonth`、`generatedAt`のIndexだけを追加する。既存テーブルへのUPDATE、backfill、削除は行わない。
- 既存の`monthly_submissions`は変更せず、過去の紙提出記録として保持・表示する。
- `electronic_submission_records`は対象月、作成日時、作成したファイル名、MyKomon提出状態・日時、備考を履歴として保持する。同じ月の再作成は上書きしない。
- キャッシュURI、実ファイルパス、MyKomon認証情報はDBへ保存しない。
- 新規バックアップはRoom v16として作成する。v15バックアップは展開・検証とEvidence URI再割当の後、staging候補内だけで`MIGRATION_15_16`と同じSQLを使ってv16へ更新する。Room identity、`user_version`、manifestのschema・DB size・SHA-256を更新し、v16として再検証できた候補だけをinstall対象にする。更新失敗時は候補を破棄し、live DBとEvidenceを変更しない。Room Migrationとstaging更新はDIに依存しない共通SQL定義を使用する。Pixel 8 API 34上のinstrumented testとv15通常版バックアップからのv16通常版UI復元で確認済み。

## 税理士向け電子提出

対象月ごとに次の別ファイルをアプリcacheの世代別一時ディレクトリへ作成する。

- `{年}年{月}月_日報.xlsx`
  - 列: 日付、現金売上、クレジットカード売上、QR決済売上、売掛売上、その他売上、売上合計
  - 原則1日1行で、最終行に月合計を出す。
  - `DailyReport`の保存値を使い、月次売上合計を正式なBusinessMetric経路と照合する。
- `{年}年{月}月_支出明細.xlsx`
  - 列: 日付、支出先、金額、内容・用途、支払方法、Evidence件数
  - 内容・用途は`ExpenseRecord.memo`を使い、カテゴリや勘定科目で代用しない。
  - 取消済み支出を除外し、現在有効な`ExpenseRecord`を支出IDごとに1件だけ出す。現金、クレジット、電子マネー、掛け、プリペイドの表示を既存の正規化経路から取得する。
- `{年}年{月}月_レシート.pdf`
  - 支出日が対象月に属する有効な支出へリンクされたstored Evidenceだけを、固定順で原則1件1ページにする。
  - 各ページに日付、支出先、金額の見出しを付け、画像は縦横比を維持してページ内に配置する。
  - 取消済み支出、孤立Evidence、対象月外Evidenceは含めない。Evidenceが0件なら空PDFを作らない。

生成中は`.tmp`へ書き、検証後に同じUUID世代ディレクトリ内で確定する。完成世代は画面の月変更・再生成・ViewModel破棄では削除せず、FileProvider共有先やSAF保存中の読取りを保護するため24時間保持する。共有・保存開始時に保持期限を更新し、次回画面準備時はcanonical cache root直下の期限切れUUID世代だけを清掃する。未知の名前、UUID名の通常ファイル、root外は清掃しない。元の会計データとEvidence画像は変更、移動、削除しない。作成済みファイルはSAFの保存先選択またはAndroid共有で取り出せる。銀行明細PDFは利用者がネットバンキングからアプリ外で用意する。

XLSXへ数値セルとして書く金額は、Excel公式の15桁精度で安全に保持できる`-999,999,999,999,999`〜`999,999,999,999,999`に限定する。Snapshot検証とwriterの両方で範囲外を拒否し、元データを丸めない。

MyKomonへのアップロードは手動である。アプリは自動ログイン、自動アップロード、API連携、パスワード・認証情報保存を行わない。利用者が手動アップロードした後だけ、アプリ内で提出済みとして記録する。

## 生成サンプルの確認

JVMテストが`app/build/monthly-export-samples/`へ作成した合成テストデータのXLSXを展開して確認した。`output/`へはコピーしていない。

- `2026年6月_日報.xlsx`: 上記7列、日別2行、月合計3,900円。
- `2026年6月_支出明細.xlsx`: 上記6列、現金・クレジット・電子マネー・掛け・プリペイドと編集後の有効支出を含む6行、有効支出合計2,150円。取消済みテスト行、カテゴリ、勘定科目は含まない。

## 2026-08-21の検証結果

- `git diff --check`: PASS。
- 未追跡のKotlin、XML、JSON、Markdown 27ファイルをno-index相当で確認: whitespace問題0件。ユーザー所有のDOCX/PDFはバイナリのためテキストwhitespace判定から除外した。
- `testDebugUnitTest`: PASS。552件、failure 0、error 0、skip 0。
- `connectedInstrumentedAndroidTest`: Pixel 8 API 34でPASS。117件、failure 0、error 0、skip 0。対象applicationIdは分離した`com.warun.accounting.instrumented`。
- `assembleDebug`: PASS。
- `compileInstrumentedAndroidTestKotlin`: PASS。
- `verifyInstrumentedApplicationIds`: PASS。
- `assembleRelease`: PASS。
- Debug APK: 65,497,804 bytes。
- unsigned release APK: 58,359,926 bytes。
- 実装前の同条件APKを保持していないため、APKサイズの厳密な増分比較は未実施。
- 初回Gradle起動は実行環境の`JAVA_HOME`未設定でtask開始前に停止した。Android Studio同梱JDKをコマンド内だけ指定し、承認されたnetwork実行でGradle wrapperを取得して検証した。
- Gradleは既存のdeprecated API／Gradle 10互換性警告を出したが、build failureはない。

## Pixel 8 API 34 最終受入

- AVD `Pixel_8`、Android 14、API 34で電子提出機能の最終受入を実施し、PASSした。
- 対象月選択、日報XLSX、支出明細XLSX、Evidence PDF、Evidence 0件時の未作成警告、SAF保存、Android共有、電子提出履歴、MyKomon手動提出済み記録、同月再作成の別履歴保持を確認した。
- 実生成XLSXを検証ライブラリで開き、文字化けがなく、日付・金額・支払方法別売上・支出明細・Evidence件数・月合計が保存値およびBusinessMetricと一致することを確認した。
- 実生成PDFを開き、Evidence件数とページ数が一致し、見出しと画像全体が切れずに表示されることを確認した。
- detached HEAD `89241cae34a978f7358bfd2483e44f4b72b81f03`の隔離worktreeでv15通常版をビルドし、テスト用の日報1件、支出1件、Evidence 1件、紙提出履歴を含むv15バックアップを通常版UIから作成した。
- 現在のv16 Debug APKへ更新後、v15バックアップを通常版UIから復元した。候補のv16移行、アプリ再起動、日報・支出・Evidence・紙提出履歴の表示、復元データからのXLSX 2件とPDF 1件の生成・SAF保存を確認し、PASSした。
- MyKomonへの自動ログイン・自動アップロード・API連携・認証情報保存は行わない。生成ファイルを利用者がMyKomonへ手動アップロードする。

## 署名済みrelease APK受入

- `versionCode = 2`、`versionName = "0.2.0"`の署名済みrelease APK受入はPASSした。
- APK SHA-256は`a72e8ab67866d2e73ee97f3031cda8c1845ee8e686db53a17d191250c2470524`。旧v15正式署名APKとの署名証明書一致を確認し、PASSした。
- Pixel 8 API 34で旧v15正式署名版から新v16正式署名版への通常更新インストールを行い、PASSした。
- Room v15からv16へのMigration、既存のテスト用日報・支出データ、Evidence、過去の紙提出履歴の保持を確認し、PASSした。
- 更新後の署名版で日報XLSX、支出明細XLSX、Evidence PDFの生成、SAF保存、Android共有を確認し、PASSした。
- MyKomonへの提出は、利用者が生成ファイルを確認して手動アップロードする。

## 2026-08-31 MyKomon提出導線・PDF軽量化受入

- MyKomon公式アプリはACTION_SEND／ACTION_SEND_MULTIPLEの共有先として検出されなかった。
- MyKomon公式アプリの起動導線と、起動できない場合のブラウザフォールバックを実装した。
- 提出ファイルの端末保存、その他の方法で共有、MyKomonへの手動提出完了記録を分離した。
- Intent起動、ファイル保存、共有Chooser表示だけでは提出済みにならず、利用者の明示操作後だけローカル提出履歴を更新する。
- A90 Android 14／API 34で手動受入をPASSした。Evidence原画像32件は変更していない。
- レシートPDFは385,578,954 bytesから79,823,663 bytesへ軽量化し、32 Evidence＝32ページを確認した。
- 日報XLSX、支出明細XLSX、レシートPDFの3ファイル合計は79,831,527 bytesで、MyKomon共有フォルダの合計100MB上限内だった。
- Pixel 8 API 34のconnected instrumentationは118/118 PASS（skip 0、failure 0、error 0）。
- JVMテストは552/552 PASS。
- MyKomonへの実アップロードと税理士による実ファイル確認は未実施である。
- 今回のversionは`versionCode = 3`、`versionName = "0.2.1"`。正式署名APKの検証・更新受入はPASSした。
- 旧v0.2.0署名済みAPKの所在は未確認である。

## MyKomon提出運用checkpoint

- versionCode 3／versionName 0.2.1のMyKomon手動提出支援機能の実装・テストはPASSした。
- A90 Android 14／API 34の手動受入、Pixel 8 API 34のconnected instrumentation 118/118、正式署名APKの検証・更新受入はPASSした。
- 正式保管先は`C:\\Users\\user\\Documents\\warun-private\\releases\\warun-accounting-0.2.1-vc3-release.apk`で、APK SHA-256は`985c8ab08fd11216de9a920a14f17e78676745e9b02d77ef4a822179736bfca8`である。
- 旧正式署名APKとの証明書一致をPASSした。
- 提出ファイル3件をPCへコピー済み。コピー先は`C:\\Users\\user\\Documents\\warun-private\\submissions\\2026-08`である。
- MyKomonブラウザ版で共有フォルダを確認したが、現在の顧客設定ではアップロード操作が表示されなかった。
- 税理士へ提出先または権限設定を問い合わせ中である。
- MyKomon実アップロードは未実施で、「MyKomonへの提出完了を記録」も未操作である。
- 税理士回答待ちとして、運用受入のみBLOCKEDである。

## 今後の運用確認事項

- 残件は、税理士による実ファイルの最終確認と、実際のMyKomonへの手動送信確認のみ。

機密情報、パスワード、APIキー、MyKomon認証情報、実際の会計データやEvidence画像はこの文書へ記録していない。
