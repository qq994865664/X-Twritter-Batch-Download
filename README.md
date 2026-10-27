# X 图片批量下载 0.7.0

[下载 0.7.0 安卓安装包](https://github.com/qq994865664/X-Twritter-Batch-Download/releases/download/v0.7.0/XBatchDownload-0.7.0-release.apk)

**安装 APP → 输入用户名 → 一键批量下载 → 保存到 `Download/用户名/`。**

不需要 X API 凭证、不付 API 费用、不需要部署后端，也不需要登录 X。手机需要能够访问 X、`pbs.twimg.com` 及至少一个公开 RSS 镜像；网络流量仍按你的网络套餐计费。

## 界面更新

- 使用暖白背景和青蓝主色，输入、粘贴及一键下载集中在首页。
- 下载进度分开展示已发现、已保存和失败数量；暂停、继续和重试按任务状态出现。
- 下载目录、已有文件夹关联、自动下拉和使用说明集中在「常用功能」，查找记录默认折叠。
- 新的应用图标以照片和下载箭头呈现，支持 Android 13 及以上的主题图标。
- 打开应用时自动检查正式版本，也可在「常用功能」中点击「检查更新」。发现新版本后选择「更新」，系统会用默认浏览器打开 APK 下载地址；安装仍由用户在系统中完成。

版本信息来自本项目的 [GitHub Releases](https://github.com/qq994865664/X-Twritter-Batch-Download/releases)，检查时需要能够访问 GitHub，无需登录或申请 API 凭证。只有高于当前版本的正式发布才会提醒；发布时需要附带命名为 `XBatchDownload-版本号-release.apk` 的安装包。

## 使用

1. 安装上方链接下载的 `XBatchDownload-0.7.0-release.apk`，支持 Android 10 及以上。沿用 0.5.2 的发布签名和签名轮换链；其他设备或不同签名的旧版可能无法覆盖。
2. 输入 `NASA`、`@NASA` 或 `https://x.com/NASA`。用户名是 @ 后面的 handle，不是昵称。
3. 如果以前下载过图片，特别是重装过 APP 或文件来自其他应用，请点击「关联已有图片文件夹（防重复）」，在系统选择器中选 `Download/用户名/` 并授权读取。以后此账号不必重复选择。
4. 点击「一键批量下载」。应用自动翻页读取 RSS 历史链接，逐条核对 X 原帖并保存原图。文件管理器中打开 `Download/NASA/` 即可查看。
5. 从旧版升级后，请重新点击「一键批量下载」开始新扫描，才能使用新增镜像和搜索入口；「继续」会沿用旧任务的扫描队列。
6. 「暂停/继续」恢复当前扫描；「重试失败」仅处理已发现的图片；再次点击「一键批量下载」会重新扫描并跳过已保存文件。
7. RSS 翻页包含在一键下载中，无需额外操作。普通扫描结束后，也可点击「自动下拉查找更多」。保持网页窗口打开，APP 自动向下滚动并收集新图片；结束后自动下载新增图片。

自动下拉约每 2.5 秒执行一轮，连续 6 轮没有新帖子/图片且没有请求等待时停止；单次最多 100 轮或 5 分钟。登录跳转、加载错误、离开页面或手动停止也会结束本次查找，并保留已加入队列的图片。

下拉页面正常运行网页 JavaScript，观察页面自己加载的公开帖子。每次会话使用清空后的匿名 Cookie，退出后清理，不提供登录入口。只从网页缩略图发现的新图片，会再读取对应公开帖子核对原始格式；不会把网页转换的 WebP 缩略图误当成原图。不同显示格式按同一媒体路径去重。

不再有服务设置。升级会清除旧版保存的后端地址和服务密钥，已保存的图片保留。旧 API 扫描游标不能继续，请重新输入用户名开始。

## 公开范围与过滤

优先尝试 `nitter.mie.foundation` 的主页、媒体与图片搜索，然后查找 X 公开主页、媒体页、嵌入时间线及其他公共 Nitter 镜像：`nitter.kareem.one`、`nitter.meowing.monster`、`nitter.jaydenha.uk`、`nitter.mizuki.st`。镜像仅用于发现帖子链接，第三方镜像会收到所查询的用户名；APP 不传入 X 登录信息。

每个镜像的主页、媒体和图片搜索是独立扫描流，各自读取 `Min-Id` 游标；不再扫描专门的回复时间线。某一入口失败时，继续扫描其他入口；其他镜像从自己的第一页开始，不假设游标可以跨镜像使用。跨来源的帖子会去重，再逐条通过 X 原帖核对作者、转帖关系和原图地址。

RSS 返回空页、重复游标、连续三次重复的同一页或没有游标时停止该流。某条原帖请求临时失败时最多自动再试两次，仍失败则在任务中显示。镜像错误、限流和验证页面会记录原因；重新点击一键下载可再尝试未成功来源。暂停/继续会保存并恢复未完成队列。免费镜像和公开页面都可能临时失效，不能保证完整历史。

**能发现多少，就下载多少，不保证完整历史。** 匿名页面可能只展示几条或几十条帖子，不一定按时间顺序返回。所有公开来源处理后显示「本次公开扫描结束，仅覆盖公开可见范围」，不显示「全部历史已下载」。网络错误、登录限制、未知页面结构等会在首页下方显示；0 张不等于该用户没有图片。

- 核对帖子作者用户名，排除普通转帖、引用转帖及其嵌套图片。
- 排除所有回复帖，包括本人回复他人和本人回复自己的帖子；排除头像、视频封面和 GIF。
- 普通扫描解析结构化帖子数据，不执行页面脚本；自动下拉使用 WebView 正常加载公开页面。
- 仅请求 `pbs.twimg.com/media/` 的 `name=orig` 图片，核验格式、传输长度及像素尺寸，原字节保存，不压缩。
- 每张新图片下载前，必须由目标原帖的完整公开数据确认本人作者、非回复状态和原始媒体信息；数据不完整时标记待重试，不会猜测后下载。原尺寸失败时不使用缩略图替代。
- 下载前查找可访问的同账号已有文件，复用有效原图，避免重新下载；包括系统追加 `(1)` 的文件名。若其他应用的同名文件不可见，系统创建时的重名结果会被撤销，提示关联目录，不覆盖旧文件。
- 文件夹关联使用 Android 文件选择器，只读取用户选择的 `Download/用户名/` 文件夹。不同账号各选择一次；选择系统的 Download 根目录无效。
- 旧版本已经保存的回复图片或重复文件不会自动删除，计数可能包含旧版文件；可在文件管理器检查后自行清理。若旧文件被改名、移走，或用户未授予目录读取权限，无法保证识别所有不同名称的副本。

一次执行一个账号任务。开始另一账号会替换当前扫描位置，但不删除已保存图片。目录按输入的用户名命名；本版按忽略大小写的用户名管理任务，账号改名不会自动合并旧记录。

## 已验证（2026-10-27）

- 0.7.0 的 31 项 Android 模拟器检查通过，包含版本比较、更新链接校验、更新弹窗和浏览器调用；真实 GitHub 版本检测通过，APK 构建及 Android Lint 通过。
- Android 15 模拟器使用隔离包验证了 0.5.2 调试版 → 0.5.2 Release → 0.7.0 Release 覆盖升级，设置保留；其他设备尚未验证。
- 0.6.0 的 27 项 Android 模拟器基本检查通过，覆盖优先镜像顺序、回复识别、历史任务核验、来源切换与去重；APK 构建及 Android Lint 通过。
- 0.6.0 在 Android 模拟器上检查了首页、工具区、任务状态、自动下拉页以及窄屏和 130% 字体显示；其他设备尚未验证。
- X 页面的结构和公开镜像随时可能变化，无法确认非回复状态时不会下载；图片历史不保证完整。

## 构建与代码

Kotlin 原生 UI、前台服务、SQLite、MediaStore，运行时直接访问公开网页和图片 CDN。

需要 JDK 17、Android SDK 36、Gradle 9.3.1。设置 `JAVA_HOME`，在未提交的 `local.properties` 填写：

```properties
sdk.dir=C\:/Android/sdk
```

```powershell
.\gradlew.bat :app:assembleRelease
python package_signed_release.py
```

Gradle 先输出未签名的 `app/build/outputs/apk/release/app-release-unsigned.apk`；打包脚本会对齐并使用发布签名，最终生成 `dist/XBatchDownload-0.7.0-release.apk`、校验值和源码包。该 APK 非调试构建，使用 Android 9 起支持的签名轮换链，尝试兼容本项目此前用本机调试密钥签名的 APK。

本机私有 `.release-signing/` 文件夹内保存发布密钥、密码文件、旧签名密钥备份及签名轮换链；后续发布需使用同一套材料。请自行妥善备份并限制访问，**不要公开或随源码包分发**。源码包不包含密钥或密码。若覆盖安装提示签名不兼容，不要直接卸载旧版；先备份已有图片与应用数据，再检查设备和旧版签名。升级后旧调试 APK 通常不能直接覆盖回装。

- `MainActivity.kt`：输入、一键启动、进度及来源记录。
- `UpdateChecker.kt`：读取 GitHub 正式版本并校验版本号与 APK 链接。
- `PublicTimeline.kt`：公开 HTML/JSON 解析、作者和转帖过滤、持久化扫描队列。
- `PublicRss.kt`：RSS 作者过滤、帖子链接发现、分页 URL 校验。
- `ScrollScanActivity.kt`、`assets/scroll-collector.js`：匿名网页自动滚动、新帖子收集和停止原因。
- `DownloadService.kt`：边发现边下载、暂停继续和失败重试。
- `Transfer.kt`：匿名 HTTPS 请求、已有文件识别、原尺寸校验和安全保存。
- `ExistingFolder.kt`：用户授权目录的只读匹配与已有文件复用。
- `Store.kt`：清单、任务状态和原子分页持久化。

Android 15 及以上可能限制长时间后台运行，停止后打开 APP 继续。暂停遇到正在进行的网络请求时，可能等待当前读取或超时后生效。

## 基本功能检查

为避免影响使用中的 APP，使用单独的 `com.xbatch.download.probe` 测试安装包：

```powershell
.\gradlew.bat :app:assembleProbe :app:assembleProbeAndroidTest -PprobeTests
```

安装 `app-probe.apk` 和 `app-probe-androidTest.apk` 后运行：

```sh
adb shell am instrument -w -e class com.xbatch.download.MainUiChecks,com.xbatch.download.RssChecks,com.xbatch.download.PublicPageChecks,com.xbatch.download.TimelineFallbackChecks,com.xbatch.download.DownloadChecks,com.xbatch.download.ReplyDownloadChecks,com.xbatch.download.ScrollChecks,com.xbatch.download.UpdateCheckerChecks,com.xbatch.download.UpdateDialogChecks com.xbatch.download.probe.test/android.test.InstrumentationTestRunner
```

真实网络检查建议仅在无 NASA 下载任务的测试模拟器中执行，会下载并清理测试图片：

```sh
adb shell am instrument -w -e class com.xbatch.download.LivePublicChecks com.xbatch.download.probe.test/android.test.InstrumentationTestRunner
```

`app/src/androidTest/assets/nasa-public-page.html` 是公开页面测试样本，仅进入测试 APK，不进入用户安装包。

## 旧版文件

`backend/` 和 `dist/backend.zip` 是 0.1.0 的历史实现。**当前 0.7.0 不调用它们，无需部署。** 新版源码包不包含旧后端；旧 APK 保留以便区分版本，请安装名称含 `0.7.0` 的 APK。
