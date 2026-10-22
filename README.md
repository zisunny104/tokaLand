# tokaLand

獨立的領地與地籍插件，採 Nukkit API，支援多選區組成單一領地、子領地參照上層，以及對重疊範圍回傳全部命中結果。資料儲存使用 tokaStorage，預設 SQLite，可選 JSON／YAML。

目前仍為開發中的領域模型，尚未接管 zero 的正式資料與保護，請勿替換正式保護。尚未完成驗證時，即使設定 `ownership.active: true` 也會保持待命。遷移工具留在 zero；未確認的名稱／UUID 對照保留待處理，不猜測歸屬。玩家領地頂部提示已有實作，待正式接手才顯示；公用地籍與道路／鐵路設施範圍不顯示個人領地提示。Area 的道路／鐵路類型先以 `type` 保留；設施管理者與其權限繼承列第二期。

同一 claim 的多個 selection 共用擁有者與 ACL，子 claim 使用 `parentClaimId` 參照上層，只保存權限覆寫。空間查詢回傳所有命中的 claim；每種操作的衝突優先序須在保護規則盤點後制定，不能以第一筆命中決定權限。

使用 Java 21 與 Maven 執行 `build.ps1`。建置產物放在 `output/`，並複製到 `plugin-modernization/output/`。

開發測試站可用 `/land` 唯讀核對原生資料，權限 `tokaland.inspect` 預設 OP。指令宣告動作候選與參數型別，啟動時確認 `/land` 註冊成功；若仍由 zero 舊模組占用，tokaLand 不註冊替代入口。搬遷應先依 zero 的 `features.land` 開關停用舊入口，並處理其相依功能，再重啟讓 tokaLand 註冊 `/land`。

| 指令 | 功能 |
| --- | --- |
| `/land help` | 顯示全部可用查詢指令 |
| `/land status` | 查看 ACTIVE／STANDBY 與領地筆數 |
| `/land list [頁碼:int]` | 每頁列出 10 筆穩定 ID、名稱與分類 |
| `/land info <領地ID:UUID>` | 查看擁有者、保護模式及所有選區識別碼／範圍 |

資料索引會查出全部命中領地；子領地可橫跨父領地多個選區，但不能超出其聯集或跨越空洞。原生資料讀取會拒絕未知巢狀欄位、溢位／非整數的方塊座標及不支援的格式。保存以不可變內容檔與作用中 manifest 切換，舊內容暫保留，不代表已實作定期清理。唯一拆分進度與交接規劃位於 zero 的 `docs/ARCHITECTURE.md`。

保護查詢 API（限伺服器主執行緒）：`Main.queryProtectionJson(Player, world, x, y, z, action)`。action 為 `INTERACT`／`BUILD`／`CONTAINERS`／`MANAGE`／`CREATE_CHILD`。回傳 JSON 的 status、allowed、reason、claims（全部命中）與 blockingClaims；`UNAVAILABLE`、`INVALID_REQUEST` 均不可視為允許。正式 ownership 尚未啟用，因此目前查詢回傳 `UNAVAILABLE`。玩家保護 listener 已註冊但僅在正式 ownership 啟用後才執行，目前保持待命。

`tokaland.admin` 預設 OP，可管理／協助編輯一般領地，仍尊重 maintenance lock。PROTECT 的外部授權須透過 `registerProtectedAuthority(providerName, BiPredicate<Player,String>)` 註冊；第二參數是 claim 與 action 的 JSON，provider 必須保留自身規則，不可直接用一般分享權替代舊 qx。provider 缺少、停用、重新載入或拋出錯誤時拒絕外部授權。`unregisterProtectedAuthority(providerName)` 可解除；一次只接受一個 provider。這些是可信任插件介面，providerName 不是驗證憑證。

LOCK 繞過目前仍停用：`requestLockBypassJson(Player, enabled, durationMillis)` 回傳 `LOCK_BYPASS_AUDIT_UNAVAILABLE`（或待命時的 `LAND_NOT_ACTIVE`），不能聲稱已啟用。純 session policy 已測試明確啟用、兩項權限、到期、撤銷與等待稽核確認；尚待接上有界非同步持久稽核及實際使用紀錄，之後才可開放。`tokaland.lock-bypass` 預設 OP 也不會單獨放行。`setMaintenanceLocked(boolean)` 是可信任插件的全域維護鎖介面，開啟時任何玩家查詢均拒絕。
玩家保護接線目前包含破壞／放置（BUILD）、點擊方塊與踩踏（INTERACT，含原有容器互動）、展示框取物（INTERACT）、玩家對非玩家實體互動／傷害（INTERACT／BUILD）。非玩家來源傷害在領地內依設定採保護優先；不推測攻擊者，PvP 仍走獨立政策。查詢以事件的實際目標為準，放置查放置位置、展示框優先查框本體，不查玩家腳下；只新增取消，不解除其他插件已取消的事件。空氣點擊不查核心提供的假 `(0,0,0)` 方塊，修正舊版的誤判。沒有額外加入 InventoryOpen／CONTAINERS 門檻，避免更改舊效果。

選區工具已由 `selection.tool` 設定物品名稱或 `ID:damage`，左右鍵暫存選區兩端座標，不寫入領地；目前仍沒有建立／編輯領地流程。自然變更接線含液體流動、方塊擴散、樹木生長跨界、生物踩踏／互動，以及雷擊、雷擊點火、傷害與可辨識的生物轉化。液體與生長政策由 `protection.liquids.prevent-boundary-flow`、`protection.growth.prevent-boundary-crossing` 控制；生物互動及無法歸因的生物傷害可分別設定；雷擊由 `protection.lightning.protect-claims` 控制。這些仍受 `PROTECTION_READY=false` 待命閘門保護，未宣稱涵蓋核心未發事件的每種生物行為、火燒、活塞或所有轉化效果，也不能停用 zero 正式保護。
TNT 來源追蹤基礎已加入，僅使用核心明確保留的 TNT source／投射物 shooter，限制追蹤深度並偵測循環；未知來源不猜玩家。這不是跨重啟歸因紀錄。

全服爆炸規則使用 `protection.explosions.mode`（`cancel-whole`／`disabled`）、`environment-protection` 與 `attribution-max-depth`。未知來源 TNT 與自然爆炸共用環境防護；已確認玩家來源仍檢查領地權限。實體爆炸已接上 `EntityExplosionPrimeEvent` 的最終 force，並在 `EntityExplodeEvent` 前檢查方塊、傷害及擊退的完整軸對齊範圍；找不到對應 force 時整次取消並記錄一次警告。核心的 `BlockExplodeEvent` 沒有 force／範圍欄位，因此 `cancel-whole` 模式會 fail closed，取消床、重生錨等所有區塊來源爆炸並記錄一次警告；這可能影響領地外的爆炸。若不接受此保護優先策略，可設 `mode: disabled` 停用 Land 爆炸攔截。`PROTECTION_READY` 仍為 false，這段接線目前不會接手正式防護。
