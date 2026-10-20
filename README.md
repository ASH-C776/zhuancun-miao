# 转存喵

网盘分享链接一键转存 + 剧集批量重命名的 Android 小工具。

粘贴 **夸克网盘 / 光鸭云盘** 的分享链接，选择转存路径与命名规则，即可把分享内容整个转存到自己的网盘，并把剧集文件按 `剧名.S01E01.mkv` 的格式批量重命名。

## ✨ 功能

- 支持夸克网盘、光鸭云盘
- 分享链接一键转存到指定目录
- 命名规则：通用剧集命名（`剧名.S01E01.mkv`）/ 保持原名 / 自定义（剧名开关 + 季度选择）
- 转存前预览「原名 → 新名」，支持单条手改
- 暖橘奶油 · M3 Expressive 界面，深浅色主题（自动 / 亮 / 暗）
- 凭证加密存储，界面脱敏显示
- 内置检查更新：新版本发布后「关于」页自动提示

## 📥 下载

前往 [Releases](https://github.com/ASH-C776/zhuancun-miao/releases) 获取最新版本；或自行克隆本仓库构建：

```bash
cd AndroidApp
gradle :app:assembleRelease   # 混淆裁剪后的安装包（约 1.9MB）
```

## 🖼 截图

> 截图未随仓库公开（内含个人账号信息），可自行构建体验。

## 🚀 使用

1. 设置 → 登录网盘（光鸭填 Refresh Token，夸克填 Cookie；获取办法请自行研究）
2. 主页粘贴分享链接、填写剧名、选择转存路径与命名规则
3. 开始转存 → 预览确认 → 完成

## 🛠 构建

用 Android Studio 打开 `AndroidApp` 目录，或：

```bash
cd AndroidApp
gradle :app:assembleRelease   # 混淆裁剪后的安装包（约 1.9MB）
```

### 工程结构

```
app/src/main/java/com/ashchan/cloudsaver/
├── core/
│   ├── adapter/CloudDriveAdapter.kt   # 统一网盘适配器接口（登录/解析/列文件/转存/重命名）
│   └── model/                         # DriveFile / ShareInfo / RenameRule / TransferResult
├── drive/
│   ├── guangya/                       # 光鸭：GuangYaApi + GuangYaAdapter（Token + 自动刷新）
│   ├── quark/                         # 夸克：QuarkApi + QuarkAdapter（Cookie，移动端分享兼容）
│   └── DriveFactory.kt                # 按网盘类型创建适配器
├── rename/                            # 规则引擎：filter/rename、占位符填充、合法性校验
├── storage/                           # 凭证加密存储 / 自定义规则持久化
├── transfer/TransferManager.kt        # 转存→拿新 fileId→重命名 流程编排（限流间隔）
└── ui/                                # Compose 页面（暖橘奶油 M3 主题）
```

## ⚠️ 声明

接口均为非官方逆向接口，可能随官方改版失效；本项目仅供个人学习与效率工具用途，请遵守对应网盘的服务条款。

---

作者：基耶杰
