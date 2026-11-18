# 空叶记账 · KongyeLedger

空叶记账是一款 Android 记账应用。支持手动记账、付款通知和银行短信识别、账单导入、预算、收支趋势和本地备份。

- 在「日历流水」查看每日账单和粉噗噗印章。
- 在「洞察」按不同时间单位查看支出、收入和结余。
- 在「我的／自动」授权自动记账渠道，或导入微信、支付宝账单。
- 备份中心提供本地 JSON 备份、CSV 导入导出和手动 WebDAV 备份。WebDAV 备份存入服务器上的 `KongyeLedger` 文件夹。

## 安装与构建

安装包在 [GitHub Releases](../../releases) 提供，支持 Android 8.0 及以上版本。

源码可用 Android Studio 打开。配置 Android SDK 37 后，从项目根目录运行：

```powershell
.\gradlew.bat :app:assembleDebug
```

调试包使用独立包名 `com.kongye.ledger.qa`。正式安装包请从 Releases 下载。
