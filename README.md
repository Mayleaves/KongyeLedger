# 空叶记账 · KongyeLedger

一款 Android 个人记账应用，支持手动记账、识别支付通知和银行短信，以及导入账单。你可以按日历查流水、设定预算，并查看收支趋势和分类构成。

[下载 APK](https://github.com/Mayleaves/KongyeLedger/releases/download/v1.0.1/KongyeLedger-v1.0.1.apk) · [更新日志](https://github.com/Mayleaves/KongyeLedger/releases)

## 主要功能

- **手动记账**　记录收入与支出，使用内置计算器输入金额，自定义分类，记账时间可精确到秒。
- **自动记账与导入**　授权后可识别微信、支付宝、云闪付、抖音、闲鱼和阿里巴巴 1688 发出的付款通知，以及银行动账短信。也可以粘贴通知文本识别，或导入微信和支付宝导出的账单。
- **流水与统计**　在印章日历中查看每日账单，按日期、分类等条件筛选流水。在「洞察」查看收支趋势和分类构成。
- **预算**　支持按日、周、月、季或年设置预算，并在首页查看今日可用额度。
- **备份与恢复**　支持本地 JSON 备份与恢复、CSV 导入导出，以及手动 WebDAV 备份与恢复。WebDAV 备份存入服务器上的 `KongyeLedger` 文件夹。

## 安装与构建

正式版支持 Android 8.0 及以上版本，可通过上方链接下载安装。

源码可用 Android Studio 打开。配置 Android SDK 37 后，在 Windows 上从项目根目录运行以下命令构建调试版。

```powershell
.\gradlew.bat :app:assembleDebug
```

调试版的应用 ID 为 `com.kongye.ledger.qa`，可与正式版同时安装。
