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

## Phase A完了・v0.2.2正式release準備

- Phase A実装完了。
- トキノ屋・サカツの掛け購入を仕入先別に個別表示し、掛け合計は表示しない。支出合計・カテゴリ合計へ二重加算しない。
- 左下サマリーは今月を初期表示とし、今日／今月を切り替えられる。
- 要確認レシート一覧と提出前警告を実装した。
- Room schema v16のまま、Migration変更なし。
- JVM 558/558 PASS。
- Pixel 8 API 34 instrumentation 118/118 PASS。
- A90へdebug署名版を`install -r`し、データ保持とPhase A手動受入PASSを確認した。
- 提出前警告の「今回は除外して作成」は実データ保護のため手動未実行で、自動テストで確認した。
- A90対象月にはトキノ屋・サカツの実データはなく、自動テストで確認した。
- 中部電力の要確認レシート1件は自動変更していない。
- 現在のversionCodeは4、versionNameは0.2.2。
- MyKomon実アップロードは税理士回答待ち。
- 電気・水道・通信・ガスのEvidence連携は次Phase。
- A90の正式署名版移行はバックアップ／復元を伴う別工程。

## v0.2.2正式release受入checkpoint

- v0.2.2／versionCode 4の正式署名受入はPASSした。
- 正式保管先は`C:\\Users\\user\\Documents\\warun-private\\releases\\warun-accounting-0.2.2-vc4-release.apk`である。
- APKサイズは58,388,658 bytes、SHA-256は`F0F037CC1E73A2EFD544C1539BB805D8C675B10FA036AE19F97A5AA239278154`である。
- 正式証明書一致をPASSした。
- Pixel 8 API 34でv0.2.1からv0.2.2への更新をPASSした。
- Room schema v16のままで、データ消去は行っていない。
- Phase Aは要確認レシート一覧と提出前警告まで実装済みである。
- 要確認レシートから日報への反映、確認済み更新、固定費Evidence連携は未実装である。
- 電気・水道・通信・ガスのEvidence連携は次Phaseである。
- A90はdebug署名版のままで、正式版への移行は未実施である。
- MyKomon実アップロードは税理士回答待ちである。
- v0.2.2は安全なPhase A checkpointであり、固定費証憑フロー完成版ではない。

## Phase C1固定費Evidence基盤checkpoint

- Phase C1基盤を実装済み。
- Room schema v17、Migration 16→17を追加した。
- Backup formatVersion 2を採用し、formatVersion 1互換を維持した。
- Migration16To17Testは1/1 PASS、connected instrumentationは119/119 PASS、JVMは560/560 PASS。
- A90へinstrumented APKは未導入で、既存データも変更していない。
- v0.2.2正式APKはRoom v16のまま正式保管済みである。
- 現在のv17コードは未リリースである。
- 次工程は固定費専用Journal、JPEG／PNG／PDF保存、固定費Receipt確認フローである。
- UI、月次PDF統合、A90受入、正式署名は未実施である。
- versionCode 4、versionName 0.2.2のままである。

## Phase C2固定費Evidence保存・復旧checkpoint

- Phase C2基盤を実装済み。
- JPEG／PNG／PDF原本をstream保存し、MIME・実ファイルシグネチャ・サイズ・SHA-256を検証する。
- Evidenceファイルの上限は50 MiBで、50 MiBちょうどを許可し、1 byte超過を拒否する。
- 固定費専用Journalを追加し、pending、stored、DB反映、復旧、quarantineを管理する。
- 複数EvidenceのsortOrderを保持し、全Evidence保存後だけDailyReport、application、link、Receipt確認済み化を行う。
- ContentResolver途中例外時に部分pending、stored Evidence、EvidenceRecord、application、linkを残さないことを確認した。
- DB反映後にJournalが残った場合の復旧は冪等で、DailyReport親行のREPLACEによる子行削除を行わない。
- Backup format v2でJPEG／PNG／PDFの実ファイル往復を確認し、v1 `.jpg`読込互換を維持した。
- Pixel 8 API 34 connected instrumentationは124/124 PASS、skip 0、failure 0、error 0。
- JVMテストは564/564 PASS、assembleDebugはPASSした。
- A90は未接続・未操作で、instrumented APKを導入していない。
- UI、SAF Picker、Receipt確認画面、月次Evidence PDF統合は未実装である。
- 現在のv17コードは未リリースである。
- versionCode 4、versionName 0.2.2のままである。次の正式版では5／0.3.0へ更新予定である。

## 現在状態（Phase C3A受入後）

- Phase C3Aを完了した。
- 固定費反映はEMPTY／SAME／CONFLICTの契約で処理する。
- Receipt保存金額との一致、および固定費種別から決定する支払方法を保存境界で検証する。
- DailyReport親行へのREPLACE／Upsertを廃止し、EMPTY時は条件付きUPDATE件数を検証する。
- Pixel 8（AVD名Pixel_8、Android 14／API 34）でC3A targeted instrumentation 7/7 PASS、connected instrumentation全件127/127 PASS（skip 0、failure 0、error 0）。
- JVMテストは568/568 PASS。assembleDebug、compileInstrumentedAndroidTestKotlinもPASSした。
- Room schema v17のままで、Migration差分はない。
- A90は未接続・未操作である。
- 現在のv17コードは未リリースで、versionCode 4、versionName 0.2.2のままである。
- C3BのCompose UI／SAF Picker、月次Evidence PDF統合は未実装である。

## Phase C3B Pixel 8受入・条件付きcheckpoint

- Phase C3BのCompose UI／SAF Evidence選択基盤を実装した。
- 要確認Receipt一覧からReceipt IDだけを固定費詳細画面へ渡す行遷移を実装し、対象月を維持する。
- 固定費詳細画面でEvidence選択、JPEG／PNG／PDFの添付情報、プレビュー、並び替え、削除、保存結果表示を行う。
- JVMテストは571/571 PASS。
- C3B Room／Evidence integrationは7/7 PASS。
- Pixel 8（AVD名Pixel_8、Android 14／API 34）のconnected instrumentationは128/128 PASS、skip 0、failure 0、error 0。
- ユーザーがPixel 8のDocumentsUIで`c3b_test.jpg`、`c3b_test.png`、`c3b_test.pdf`を各1件選択し、固定費Evidence画面への復帰とPDF表示を確認した。
- 選択後にホームへ戻った事象は、logcat上でinstrumentation終了時に`com.warun.accounting.instrumented`とtest APKが自動アンインストールされたためであり、Productionクラッシュ、ANR、SecurityExceptionではないことを確認した。
- 以上を根拠に、Pixel 8でのC3B受入を条件付きPASSとする。
- 実画面からの最終保存、Receipt確認済み化、要確認一覧からの除外、保存後の不要URI権限解放は未確認であり、A90受入へ持ち越す。
- A90は未接続・未操作である。
- Room schema v17、Migration変更なし。versionCode 4、versionName 0.2.2のままである。
- 月次Evidence PDF統合、MyKomon画面変更、正式署名、A90正式版移行は未実装・未実施である。

## v0.2.3 A90試験運用checkpoint

- C3B checkpoint `bb3f44b4f91989bd365202b0e6e5fdb55945d852`を基準に確認した。
- A90更新前はversionCode 3、versionName 0.2.1、更新後はversionCode 5、versionName 0.2.3である。
- debug APKとA90既存版の署名証明書一致、およびinstall -r成功を確認した。
- 更新前正式バックアップの作成・存在・manifest・DB・Evidence整合性検証はPASSした。
- Room v16からv17へのMigrationをPASSした。
- 日報28件、Receipt 2件、Expense 42件、Evidence 41件、月次提出1件、電子提出履歴5件を更新前後で保持した。
- DBとEvidenceのサイズ・SHA-256整合性をPASSした。
- アプリ起動とレシート管理画面表示をPASSした。
- クラッシュ、ANR、アプリ固有のSecurityExceptionは確認していない。
- A90にinstrumented APKが存在しないことを確認した。
- 要確認レシートは0件であり、固定費Evidenceの実データ最終保存は未確認である。
- 2026年8月のactive ExpenseでEvidenceなしは0件である。7月のEvidence未添付データは今回の対象外として保留する。
- 月次Evidence PDFへの固定費統合は未実装である。
- 正式署名版v0.2.3は未作成であり、MyKomon実提出も未実施である。
- versionCode 5、versionName 0.2.3を試験運用版として確定した。

## 固定費Evidence日報表示・Viewer checkpoint

- 固定費4種（電気代、水道代、通信費、ガス代）の日報Evidence状態表示を実装した。
- 金額0円・Applicationなしは強調せず、金額あり・未登録は「証憑未登録」、Applicationあり・stored 0件は「証憑要確認」、stored件数ありは「証憑n件」と表示する。
- stored Evidenceだけを件数へ算入し、pending等は除外する。sortOrderと日報IDによる分離、重複なしを確認した。
- JPEG／PNG／PDFの読み取り専用Viewerを実装した。ファイル名、MIME、サイズ、位置／総件数、前へ／次へ／閉じるを表示する。
- 欠損・破損ファイルはクラッシュせず「表示できません」とする。Viewerは原本を変更しない。
- Evidenceは金額、支出集計、現金残高、通常支出Evidence経路へ影響しない。
- Room／Entity／Migration／保存契約は変更していない。Room schemaはv17、versionCode 5、versionName 0.2.3のままである。
- 固定費Evidenceの分離targeted instrumentationは4/4 PASS、connected instrumentation全件は132/132 PASS、JVMは572/572 PASSした。
- compileInstrumentedAndroidTestKotlin、assembleDebug、git diff --checkはPASSした。
- 日報一覧から詳細へのNavigation smokeはViewer受入から分離し、今回未実施である。
- A90は未操作である。ローカルIP、ADB serial、認証情報、Evidence内容は記録していない。

## Receipt安全削除 checkpoint（2026-09-02）

- 未確認かつ未関連のReceiptだけ削除可能とし、確認済み、固定費Application／Evidence Link、Expense、Evidence関連済みReceiptは削除禁止とした。
- Evidence原本、pending画像、Journal、バックアップ、Receipt以外のDBレコードは削除しない。削除判定と条件付きDELETEはRoom transaction内で行う。
- 削除確認ダイアログで支払先、日付、金額、「この操作は取り消せません。」を表示し、キャンセル時は削除処理を呼ばず一覧とDB件数を維持する。
- Compose削除確認テスト1/1 PASS、Room削除targeted instrumentation 5/5 PASS、connected instrumentation全件143/143 PASS、JVM 572/572 PASS。
- Room schema v17、Migration差分なし、versionCode 7、versionName 0.2.5を維持した。
- A90は未操作である。ローカルIP、ADB serial、認証情報、Evidence内容は記録していない。

## v0.2.4 A90受入 checkpoint（2026-09-01）

- A90現行版を確認し、debug APKと署名証明書が一致したため、既存データを保持したまま `install -r` を実施した。
- 更新後は versionCode 6、versionName 0.2.4。Room schema v17、Migration変更なし。
- 更新前後で日報28件、Receipt 2件、Expense 42件、Evidence 41件、月次提出1件、電子提出履歴5件を保持した。
- 既知の正式バックアップをサイズ、SHA-256、ZIP、manifest、DB、Evidence整合性まで検証した。
- 日報確認画面で、金額ありの通信費に「証憑未登録」、0円の電気代・水道代・ガス代は状態強調なしを確認した。
- 起動、日報一覧、日報確認画面を確認した。アプリ由来のクラッシュ、ANR、SecurityExceptionは確認していない。
- instrumented APKはA90へ導入していない。固定費Evidenceの実データ最終保存は未確認である。
- Pixel 8の既存検証結果（connected instrumentation 132/132、JVM 572/572）を受入根拠とし、今回重いテストは再実行していない。
- ローカルIP、ADB serial、認証情報、Evidence内容は記録していない。

## Phase 1.5 固定費Evidenceバックアップ checkpoint（2026-09-04）

- 通常Evidence（`accounting-evidence/stored`）と固定費Evidence（`fixed-cost-evidence/stored`）を正式バックアップ対象として検証する経路を実装した。
- Evidence IDごとにDB記録のサイズ・SHA-256と原本を照合し、同一IDの重複収録を防止する。両保存先に同一IDがある場合は内容不一致を拒否する。
- Restore時はManifestの保存先URIに応じて通常Evidence／固定費Evidenceの保存先へ戻す。Room schema、Entity、Migrationは変更していない。
- アプリ内部でZIP・manifest・DB・Evidenceを完全検証してからSAF出力を行い、SAF出力後の再読込検証も実施する。Build失敗時はDocumentsUIを開かない。
- Pixel 8のBackup targeted instrumentation 15/15、connected instrumentation全件156/156、JVM全件、compileInstrumentedAndroidTestKotlin、assembleDebug、git diff --checkをPASSした。
- A90（version 10／0.2.8）へ署名一致確認後に通常APKのみ`install -r`し、Room v17、日報35、Receipt3、Expense45、Evidence45、固定費Application 1、固定費Link 1、Journal 0を保持した。
- A90で正式バックアップを作成し、110,028,298 bytes、ZIP、manifest、Room v17、DB integrity／foreign key、Evidence45件、固定費PDF 634,790 bytesのサイズ・SHA検証をPASSした。A90上の正式バックアップは保持している。
- 既存および今回作成された0バイトバックアップ2件は削除していない。Evidence原本、DB、app/release、output、`hs_err_pid3188.log`は変更・削除していない。

## Phase 1 日報保存外部キー修正監査（2026-09-04）

- A90の読み取り監査で、日報35件、Receipt 3件、Expense 45件、Evidence 45件、固定費Application 1件、固定費Link 1件、月次提出1件、電子提出履歴5件を確認した。
- 2026-08-23の日報の電気代41,617円を保持し、固定費PDF 1件（634,790 bytes、stored）のDBメタデータと実ファイルSHA-256一致を確認した。
- Journal 0件、Room v17、integrity check／foreign key check PASS。A90の既存正式データは変更していない。
- 日報保存失敗の原因は、既存日報の`INSERT OR REPLACE`が参照中の日報親行を置換し、固定費Applicationの外部キー制約に抵触したことと確定した。
- `DailyReport`保存をUPDATE優先・新規時のみINSERTのRoom transactionへ変更し、固定費参照を保持した再保存と新規日報INSERTをPixel 8で確認した。
- Phase 1修正後のPixel 8 targeted 12/12、connected 148/148、JVM全件、compileInstrumentedAndroidTestKotlin、assembleDebug、git diff --checkはPASSした。
- A90へは通常APKのinstall-rのみ実施し、instrumented APKは導入していない。再保存操作は実施していない。

## v0.2.7 最近のレシート削除 A90受入 checkpoint（2026-09-02）

- versionCode 9、versionName 0.2.7。Room schema v17、Migration差分なし。
- 最近のレシートで未確認行にゴミ箱を表示し、確認済み行には表示しないことをA90で確認した。
- 0円・未設定の確認待ちReceiptで削除確認ダイアログを開き、支払先、日付、金額、「この操作は取り消せません。」を確認した。
- 受入ではキャンセルし、Receipt件数と対象行が不変であることを確認した。実削除は未実施である。
- 電気／中部電力41,617円の2件は未操作である。
- A90へのinstall -r、DB件数・Evidence保持、正式バックアップ検証はPASSした。instrumented APKは導入していない。
- Pixel 8 connected instrumentation 144/144、JVM 572/572 PASS済みである。
- A90受入で使用したローカルIP、ADB serial、認証情報、Evidence内容は記録していない。

## v0.2.5 電子提出画面最下部受入 checkpoint（2026-09-02）

- 電子提出画面のLazyColumnへシステムnavigation bar insetを適用し、通常の下部余白を追加した。アプリ内bottomBarの既存innerPaddingとの二重適用は行っていない。
- 長い電子提出履歴を最下部までスクロールし、最終「MyKomonへの提出完了を記録」ボタン全体が下部ナビゲーションより上に表示されることをCompose bounds assertionとA90目視で確認した。
- Pixel 8 targeted Compose instrumentation 2/2 PASS、connected instrumentation全件137/137 PASS、JVM 572/572 PASS。compileInstrumentedAndroidTestKotlin、assembleDebug、git diff --checkもPASSした。
- A90へversionCode 7、versionName 0.2.5を署名一致確認後にinstall -rした。既存DB・Evidence・バックアップは保持され、instrumented APKは導入していない。
- Room schema v17、Migration差分なし。A90のアプリ固有クラッシュ、ANR、SecurityExceptionは確認していない。
- A90受入で使用したローカルIP、ADB serial、認証情報、Evidence内容は記録していない。

## v0.2.5 A90受入 checkpoint（2026-09-02）

- 電子提出画面の最終提出完了ボタンについて、system navigation bar insetとアプリ内bottomBar領域を区別して適用し、長い履歴でも最下部まで完全表示できることを確認した。
- Pixel 8 targeted Compose instrumentation 2/2 PASS、connected instrumentation全件137/137 PASS、JVM 572/572 PASS。compileInstrumentedAndroidTestKotlin、assembleDebug、git diff --checkはPASSした。
- A90へversionCode 7、versionName 0.2.5を署名一致確認後にinstall -rした。Room schema v17、Migration差分なし。既存DB、Evidence、バックアップを保持し、instrumented APKは導入していない。
- A90で電子提出履歴の最終ボタン全体と下側余白を目視確認した。アプリ固有のクラッシュ、ANR、SecurityExceptionは確認していない。
- 固定費Evidenceの実データ最終保存、および実データを含む月次PDF提出は未確認である。
- A90受入で使用したローカルIP、ADB serial、認証情報、Evidence内容は記録していない。

## C4最終監査 checkpoint（2026-09-02）

- 日報から固定費4種（電気代、ガス代、水道代、通信費）へEvidenceを直接添付し、日報確認画面の状態表示と既存Viewer導線を確認した。
- 金額0円は保存前に拒否され、Receipt／Application／Link／Evidenceを生成しない。保存前後のExpenseRecord件数は不変である。
- 月次PDFは固定費Evidenceを取り込み、JPEG／PNGと複数ページPDFの全ページを出力する。同一Evidence IDが通常Expenseと固定費に存在する場合は1回だけ出力する。
- ガス代は「銀行振込」として保存し、通常ExpenseRecordを生成せず、現金支出・現金残高へ算入しない。
- stored Evidenceのみを登録済み件数へ算入し、sortOrder順、日報分離、重複排除を維持する。原本・SHA-256・サイズ・DBは変更しない。
- 税理士顧問料22,000円の空欄初回入力、既存値保持・変更・クリア、日報／電子提出画面の最下部スクロール、カレンダー曜日列・月移動を確認した。
- Pixel 8 API 34のC4関連instrumentationは17/17 PASS、connected instrumentation全件は137/137 PASS（skip 0、failure 0、error 0）、JVMは572/572 PASSした。
- compileInstrumentedAndroidTestKotlin、assembleDebug、git diff --checkはPASSした。Room schema v17、Migration差分なし、versionCode 6、versionName 0.2.4を確認した。
- A90は未操作である。ローカルIP、ADB serial、認証情報、Evidence内容は記録していない。

## v0.2.6 A90試験運用 checkpoint（2026-09-02）

- A90へ versionCode 8、versionName 0.2.6を署名互換性確認後に `install -r`し、PASSした。
- Room schema v17、DB整合性、既存件数保持をPASSした。
- 更新前後で日報35件、Receipt 6件、Expense 42件、Evidence 41件、月次提出1件、電子提出履歴5件を保持した。
- 正式バックアップを既存UI経路で作成し、manifest、DB、Evidenceの整合性を検証してPASSした。既存バックアップは変更・削除していない。
- instrumented APKはA90へ導入していない。
- A90の要確認Receiptは0件だったため、削除ダイアログを開く実データがなく、手動確認は未実施である。
- 架空Receiptは作成していない。削除機能はPixel 8のCompose 1/1、Room 5/5、connected 143/143でPASS済みである。
- 実際の要確認Receiptが発生した時に、削除ダイアログとキャンセル後の保持を最終運用確認する。
- ローカルIP、ADB serial、認証情報、Evidence内容は記録していない。
