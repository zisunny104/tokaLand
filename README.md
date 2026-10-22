# tokaLand

獨立的領地與地籍插件，採 Nukkit API，支援多選區組成單一領地、子領地參照上層，以及對重疊範圍回傳全部命中結果。資料儲存使用 tokaStorage，預設 SQLite，可選 JSON／YAML。

目前仍為開發中的領域模型，尚未接管 zero 的正式資料、玩家指令或保護事件，請勿替換正式保護。尚未完成驗證時，即使設定 `ownership.active: true` 也會保持待命。遷移工具留在 zero；未確認的名稱／UUID 對照保留待處理，不猜測歸屬。玩家領地頂部提示已有實作，待正式接手才顯示；公用地籍與道路／鐵路設施範圍不顯示個人領地提示。Area 的道路／鐵路類型先以 `type` 保留；設施管理者與其權限繼承列第二期。

同一 claim 的多個 selection 共用擁有者與 ACL，子 claim 使用 `parentClaimId` 參照上層，只保存權限覆寫。空間查詢回傳所有命中的 claim；每種操作的衝突優先序須在保護規則盤點後制定，不能以第一筆命中決定權限。

使用 Java 21 與 Maven 執行 `build.ps1`。建置產物放在 `output/`，並複製到 `plugin-modernization/output/`。

開發測試站可用以下唯讀指令核對原生資料，權限 `tokaland.inspect` 預設 OP。指令已宣告動作候選與參數型別；待命期間不註冊 `/land`，以免與既有插件衝突。

| 指令 | 功能 |
| --- | --- |
| `/tokaland help` | 顯示全部可用查詢指令 |
| `/tokaland status` | 查看 ACTIVE／STANDBY 與領地筆數 |
| `/tokaland list [頁碼:int]` | 每頁列出 10 筆穩定 ID、名稱與分類 |
| `/tokaland info <領地ID:UUID>` | 查看擁有者、保護模式及所有選區識別碼／範圍 |

資料索引會查出全部命中領地；子領地可橫跨父領地多個選區，但不能超出其聯集或跨越空洞。原生資料讀取會拒絕未知巢狀欄位、溢位／非整數的方塊座標及不支援的格式。保存以不可變內容檔與作用中 manifest 切換，舊內容暫保留，不代表已實作定期清理。唯一拆分進度與交接規劃位於 zero 的 `docs/ARCHITECTURE.md`。

保護查詢 API（限伺服器主執行緒）：`Main.queryProtectionJson(Player, world, x, y, z, action)`。action 為 `INTERACT`／`BUILD`／`CONTAINERS`／`MANAGE`／`CREATE_CHILD`。回傳 JSON 的 status、allowed、reason、claims（全部命中）與 blockingClaims；`UNAVAILABLE`、`INVALID_REQUEST` 均不可視為允許。正式 ownership 尚未啟用，因此目前查詢回傳 `UNAVAILABLE`。玩家保護 listener 已註冊但僅在正式 ownership 啟用後才執行，目前保持待命。

`tokaland.admin` 預設 OP，可管理／協助編輯一般領地，仍尊重 maintenance lock。PROTECT 的外部授權須透過 `registerProtectedAuthority(providerName, BiPredicate<Player,String>)` 註冊；第二參數是 claim 與 action 的 JSON，provider 必須保留自身規則，不可直接用一般分享權替代舊 qx。provider 缺少、停用、重新載入或拋出錯誤時拒絕外部授權。`unregisterProtectedAuthority(providerName)` 可解除；一次只接受一個 provider。這些是可信任插件介面，providerName 不是驗證憑證。

LOCK 繞過目前仍停用：`requestLockBypassJson(Player, enabled, durationMillis)` 回傳 `LOCK_BYPASS_AUDIT_UNAVAILABLE`（或待命時的 `LAND_NOT_ACTIVE`），不能聲稱已啟用。純 session policy 已測試明確啟用、兩項權限、到期、撤銷與等待稽核確認；尚待接上有界非同步持久稽核及實際使用紀錄，之後才可開放。`tokaland.lock-bypass` 預設 OP 也不會單獨放行。`setMaintenanceLocked(boolean)` 是可信任插件的全域維護鎖介面，開啟時任何玩家查詢均拒絕。
玩家保護接線目前包含破壞／放置（BUILD）、點擊方塊與踩踏（INTERACT，含原有容器互動）及展示框取物（INTERACT）。查詢以事件的實際目標為準，放置查放置位置、展示框優先查框本體，不查玩家腳下；只新增取消，不解除其他插件已取消的事件。空氣點擊不查核心提供的假 `(0,0,0)` 方塊，修正舊版的誤判。沒有額外加入 InventoryOpen／CONTAINERS 門檻，避免更改舊效果。

盔甲座受玩家傷害亦查實際實體位置（BUILD），依已確認的新規則統一套用全部命中領地與 Area 保護；這是相較舊 Area 沒有對應 handler 的明確行為調整。選取工具、PvP、液體／火／活塞等規則亦不在這批玩家事件範圍內。`PROTECTION_READY` 仍為 false，這批接線不代表可以停用 zero 正式保護。
TNT 來源追蹤基礎已加入，僅使用核心明確保留的 TNT source／投射物 shooter，限制追蹤深度並偵測循環；未知來源不猜玩家。這不是跨重啟歸因紀錄，也不代表雷擊／爆炸事件已接入或玩家已獲授權。

全服爆炸規則使用 `protection.explosions.mode`（`cancel-whole`／`disabled`）、`environment-protection` 與 `attribution-max-depth`。未知來源 TNT 與自然爆炸共用環境防護；已確認玩家來源仍檢查領地權限。實體爆炸已接上 `EntityExplosionPrimeEvent` 的最終 force，並在 `EntityExplodeEvent` 前檢查方塊、傷害及擊退的完整軸對齊範圍；找不到對應 force 時整次取消並記錄一次警告。核心的 `BlockExplodeEvent` 沒有 force／範圍欄位，因此 `cancel-whole` 模式會 fail closed，取消床、重生錨等所有區塊來源爆炸並記錄一次警告；這可能影響領地外的爆炸。若不接受此保護優先策略，可設 `mode: disabled` 停用 Land 爆炸攔截。`PROTECTION_READY` 仍為 false，這段接線目前不會接手正式防護。
