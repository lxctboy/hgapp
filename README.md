# 橙子短剧 · Android

用于浏览、搜索和观看短剧、漫剧的 Android 应用。

## 功能

- 首页推荐、轮播与按类型浏览。
- 剧名搜索、选集和横竖屏播放。
- 连续播放、播放进度恢复与首页继续观看。
- 观看历史、收藏、账号同步及浅色、深色主题。
- 应用版本和界面资源更新。

## 安装

从 [Releases](https://github.com/lxctboy/hgapp/releases/latest) 下载 ChengZi-Android-v版本号-release.apk，在 Android 8.0 或更高版本设备上安装。后续可在应用设置中检查更新。

首次安装时，按系统提示允许当前下载工具安装应用。

## 从源码构建

需要 Android SDK 37、JDK 25、Python 3，以及 Android Studio 自带的 Kotlin 编译器。也可通过 KOTLIN_HOME 指定 Kotlin 编译器目录。

配置 ANDROID_HOME 或本地 local.properties 后，在项目目录运行：

```powershell
.\gradlew.bat :app:assembleDebug
```

调试包位于 app/build/outputs/apk/debug/。自行分发时请使用自己的签名配置。

## 云构建签名配置

CI 打包时会优先使用仓库 Secret 里的正式密钥；**未配置时自动回退 debug 签名**，
保证产物一定能安装（未签名包会被系统以 `INSTALL_PARSE_FAILED_NO_CERTIFICATES` 拒绝）。

需要正式签名时，在仓库 **Settings → Secrets and variables → Actions** 添加四个 Secret：

| Secret | 说明 |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | keystore 文件的 base64（单行） |
| `ANDROID_KEYSTORE_PASSWORD` | 密钥库密码 |
| `ANDROID_KEY_ALIAS` | 密钥别名 |
| `ANDROID_KEY_PASSWORD` | 密钥密码（**PKCS12 下会被忽略**，见下方说明） |

> 本仓库使用的密钥库为 **PKCS12**（`.p12`）。PKCS12 内部没有独立的密钥口令，
> 解锁密钥用的就是库口令，因此 Gradle 在检测到 PKCS12 时会忽略
> `ANDROID_KEY_PASSWORD`、直接使用 `ANDROID_KEYSTORE_PASSWORD`。
> 若密钥为 JKS，两个口令才会分别生效。
>
> 格式由**文件魔数**判定（JKS 为 `FEEDFEED`），不依赖扩展名，
> 因此 CI 把密钥还原成 `signing.keystore` 这种名字也能正确识别。

本机没有 JDK（`keytool`）时，可用自带脚本生成等价密钥库：

```powershell
python scripts/gen-keystore.py
```

脚本会在**仓库外**的 `D:\project\keystores\` 生成密钥库与 `hgapp-signing.txt`
（内含可直接粘贴的 Secret 值）。该目录已被 `.gitignore` 兜底拦截，切勿提交。

> 密钥丢失将无法向用户推送更新，请自行备份 `hgapp.p12` 与密码。
> 密钥文件为 PKCS12 格式，Gradle 会按扩展名自动切换 `storeType`。

## 许可与致谢

本项目按 [GPL-3.0-only](LICENSE) 提供源码。内容适配代码使用了 [N3urda/hongguoTV](https://github.com/N3urda/hongguoTV) 的部分代码，来源及版权说明见 [第三方声明](third_party/hongguo/NOTICE.txt)。
