# VolumeLockr PLUS

[English](README.md) | [繁體中文](README.zh-TW.md)

VolumeLockr PLUS 是一款用於控制 Android 裝置音量並為各個音量頻道設置鎖定狀態的應用程式。

## Fork 說明與 GPLv3 條款聲明

本專案為 [原始 VolumeLockr (com.klee.volumelockr)](https://github.com/jonathanklee/VolumeLockr) 的修改分支版本。
應用程式套件名稱已調整為 `com.lnu.volumelockr.plus`。

### 主要修改項目
 
- **Android TV 遠端遙控與動態安全配對**：支援透過手機經由 Wi-Fi 網路遠端控制 Android TV / Google TV 的音量與鎖定狀態。內建 TV 端的 HTTP 服務，具備 60 秒動態 PIN 碼刷新、QR Code 掃描配對，以及安全 Token 交換機制（首次配對成功後頒發長效 Token，後續自動免密重連）。
- **生物辨識與密碼安全防護**：支援獨立指紋 / 生物辨識解鎖與 PBKDF2 加鹽雜湊密碼防護，並提供獨立的密碼安全設定子頁面。
- **介面與操作體驗 (UI / UX) 現代化**：
  - 首頁採用單一動態切換按鈕，一鍵「全部鎖定 / 全部解鎖」。
  - 支援點擊數值標籤直接彈出對話框手動輸入精準音量。
  - 完整支援 Android TV 與 D-pad 遙控器操作，並自適應大螢幕佈局。
  - 整合權限狀態橫幅提示與勿擾模式 (DND) 存取防護。
- **動態隱藏圖示**：支援從 Google TV / Android TV 啟動器中動態隱藏或顯示應用程式圖示。
- **多語言支援**：新增繁體中文 (zh-TW) 及多國語言翻譯。
 
本修改衍生作品依據 **GNU General Public License v3.0** 授權條款釋出，完整遵守開源 Copyleft 之要求。

---

## 手動編譯與安裝

### 1. 取得原始碼

```bash
git clone git@github.com:ian20040409/VolumeLockr-PLUS.git
```

### 2. 編譯 APK

```bash
cd VolumeLockr-PLUS
./gradlew assembleDebug
```

### 3. 安裝至裝置

```bash
adb install ./app/build/outputs/apk/debug/app-debug.apk
```

---

## Android TV 遠端配對與模擬器測試說明

### 實體裝置連線（真實手機與電視）
1. 確保手機與 Android TV 連接在**同一個 Wi-Fi 區域網路**。
2. 電視端開啟 VolumeLockr PLUS，畫面上會顯示真實區域網路 IP（例如 `192.168.1.100`）與 6 位數配對 PIN 碼。
3. 手機端切換至「TV Remote」分頁，輸入該 IP 或掃描 QR Code，點擊 **Connect** 並輸入 PIN 碼即可完成配對。

### 雙模擬器環境測試（手機模擬器 ⟷ 電視模擬器）
Android 模擬器（AVD）皆在獨立的虛擬 NAT 路由器後方運行，每個模擬器的本機 IP 預設皆為 `10.0.2.15`，無法直接透過該 IP 互通。請依照下列步驟進行連線：

1. **設定宿主機連接埠轉發 (Port Forwarding)**  
   在電腦終端機執行下列指令，將宿主機的 `8080` 端口轉發至電視模擬器（以 `emulator-5554` 為例）：
   ```bash
   adb -s emulator-5554 forward tcp:8080 tcp:8080
   ```
2. **手機端連線設定**  
   在手機模擬器的「TV Remote」頁面中：
   - **TV IP Address** 請填入 **`10.0.2.2`**（此位址在模擬器中代表電腦宿主機）。
   - 點擊 **Connect**，在彈出的對話框中輸入電視模擬器當前顯示的 6 位數 PIN 碼。
3. 配對成功後即可在手機模擬器上即時控制電視模擬器的音量與鎖定狀態。

