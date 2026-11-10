# 立體動態桌布（ParallaxWall）

Android 動態桌布 App，針對 ROG Phone 7 Ultimate（9:20.4）設計。

## 功能

- **靜態圖立體效果**：陀螺儀驅動的網格扭曲視差。依亮度＋畫面中心自動推算前後景（不需深度圖），晃動手機時近景與遠景位移不同。
- **光暈**：亮部模糊後以「濾色」疊回（可呼吸），外加隨傾斜移動的光影掃過。
- **自動辨識檔案格式**：讀檔頭魔術位元組判斷 JPEG/PNG/WebP/GIF/BMP/HEIC/AVIF 與 MP4/MOV/WebM/MKV/3GP/AVI，不靠副檔名；GIF 與動態 WebP 自動走動圖流程，影片走影片流程。
- **鎖定畫面時鐘**：桌布自繪駭客任務風格（#00FF41 發光）單行時鐘 `2026/11/11 週三    02：24：56`，位置/大小可調，只在鎖定時顯示。影片桌布改走 OpenGL 渲染才能疊字（GL 失敗會自動退回無時鐘的直出模式）。系統自己的時鐘 App 無法移除。
- **其他**：
  - 素材庫（匯入後存在 App 內，不依賴外部權限）
  - 預覽上拖曳調整構圖焦點
  - 亮屏時依間隔自動輪播素材
  - 桌面左右滑動時額外視差
  - 影片靜音、離開桌面自動暫停、省電模式時停用呼吸動畫
  - 畫面暗化、反轉深度、反向視差

## 更新方式（不用重裝）

簽章金鑰固定放在 `app/release.keystore`，versionCode 取自 GitHub Actions 的 `run_number`（每次建置遞增）。
所以每次推送到 `main` 後，到 Releases 下載最新的 `ParallaxWall.apk` 直接安裝即可覆蓋舊版，設定與素材庫都會保留。

> 金鑰就在 repo 內，請保持 repo 為 **private**。

## 建置

推送到 `main` 自動由 GitHub Actions 建置，產物在 Releases（`ParallaxWall.apk`）。
