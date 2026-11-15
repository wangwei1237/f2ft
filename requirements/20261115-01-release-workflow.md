# GitHub Actions 编译打包并发布 APK

- 编号：20261115-01
- 状态：已确认
- 版本：v2
- 创建日期：2026-11-15
- 确认记录：v2 于 2026-11-15 确认

## 背景

用户原话："为本项目增加 app 编译打包的 github actions，要求手动触发该 actions，触发时需要输入打包的版本号，版本号是 3 位，例如 0.0.1。编译打包后，把最终生成的 apk 文件提交到 github 的 releases 页面，方便别人可以直接下载安装，打包的时候注意多机型、架构，不同的机型、架构需要产出对应的 apk，可以在 apk 的名称上加以标注，方便用户了解需要下载哪个 apk。"

现状：

- 仓库没有 `.github/workflows/`，目前只能在本机用 `./gradlew :app:assembleDebug` 打 debug 包。
- `versionName` 固定为 `1.0`，`versionCode` 固定为 `1`（`android/app/build.gradle.kts`）。
- 目前没有 release 签名配置。未签名的 APK 无法安装。
- 现有 APK 内含原生库（来自 AndroidX），覆盖 `arm64-v8a`、`armeabi-v7a`、`x86`、`x86_64` 四种 CPU 架构，所以按架构拆分 APK 是有意义的。

关于"多机型"：Android 安装包与机型相关的维度主要是 CPU 架构（ABI）。屏幕密度拆分只影响图片资源，本项目图片很少，拆分没有收益，所以只按架构拆分，另外提供一个通用包。

## 目标

维护者在 GitHub 网页上手动运行一个工作流，输入版本号（如 `0.0.1`），即可自动编译出签名后的各架构 APK，并发布到 Releases 页面，其他人可以直接下载安装。

## 需求范围

1. **工作流**：新增 `.github/workflows/release.yml`。
   - 只能手动触发（`workflow_dispatch`），触发时必须填写版本号。
   - 版本号格式校验：必须是三段数字 `X.Y.Z`（如 `0.0.1`、`1.2.10`），格式不对时工作流直接失败并给出提示。
   - 同名版本（标签 `v0.0.1`）已存在时直接失败，不覆盖已有发布。
2. **版本号写入 App**：
   - `versionName` 使用输入的版本号，例如 `0.0.1`。
   - `versionCode` 由版本号计算：`X × 10000 + Y × 100 + Z`（`0.0.1` → `1`，`1.2.3` → `10203`），保证新版本号大于旧版本号，可以覆盖安装。为此要求 Y、Z 不超过 99。
   - 本机构建不传版本号时，保持现在的默认值，不影响日常开发。
3. **按架构拆分 APK**：构建 release 包时输出 5 个 APK：

   | 文件名 | 适用设备 |
   |---|---|
   | `f2ft-0.0.1-arm64-v8a.apk` | 绝大多数近几年的 Android 手机（64 位 ARM），推荐 |
   | `f2ft-0.0.1-armeabi-v7a.apk` | 较老的 32 位 ARM 手机 |
   | `f2ft-0.0.1-x86_64.apk` | 64 位 x86 设备，如电脑上的 Android 模拟器 |
   | `f2ft-0.0.1-x86.apk` | 32 位 x86 设备（少见） |
   | `f2ft-0.0.1-universal.apk` | 包含所有架构，体积最大，不确定选哪个时使用 |

4. **签名**：release APK 用固定的发布密钥签名，密钥不进仓库，存放在 GitHub 仓库的 Secrets 中。
   - 我在本机生成专用发布密钥（RSA 4096 位，有效期 30 年），密码随机生成。
   - 密钥文件和密码保存在仓库之外的 `~/.f2ft-release/`（`release.keystore` 和 `credentials.txt`，权限仅本人可读），由你另行备份。
   - 我用 `gh secret set` 把以下 4 项写入仓库 Secrets：`RELEASE_KEYSTORE_BASE64`、`RELEASE_KEYSTORE_PASSWORD`、`RELEASE_KEY_ALIAS`、`RELEASE_KEY_PASSWORD`。密码不会出现在命令行参数、日志或对话中。
5. **发布**：
   - 构建成功后，创建标签 `v<版本号>`（指向本次构建的提交）和同名 Release，上传上述 5 个 APK。
   - Release 说明里写明每个 APK 适用的设备，以及安装方法（允许"安装未知来源应用"）。
6. **文档**：
   - 在 `README.md` 中增加"下载安装"和"发布新版本"两部分（含首次配置签名 Secrets 的步骤）。
   - 在 `docs/architecture.md` 中补充发布流程和版本号规则。

## 不在范围内

- 自动触发（推送代码或打标签时自动发布）。
- 上传到应用商店、生成 AAB 包。
- 代码混淆和体积优化（`isMinifyEnabled` 保持关闭）。
- 按屏幕密度或语言拆分 APK。
- 自动生成更新日志（Release 说明使用固定模板加 GitHub 自动生成的提交列表）。

## 验收标准

1. 在 GitHub 仓库的 Actions 页面能看到"Release APK"工作流，只有手动运行入口，运行时要求输入版本号。
2. 输入格式错误的版本号（如 `1.0`、`v0.0.1`、`0.0.a`）时，工作流失败，日志里有明确的格式错误提示，不创建 Release。
3. 输入 `0.0.1` 运行成功后，Releases 页面出现 `v0.0.1`，包含上述 5 个 APK，Release 说明里有各 APK 的适用设备说明。
4. 下载 `f2ft-0.0.1-arm64-v8a.apk` 安装到测试手机（华为 ALN-AL80），能正常打开；系统应用信息里显示版本 `0.0.1`。
5. 用 `aapt`（或 `apksigner`）检查：5 个 APK 都已签名；各架构包只包含对应架构的原生库，通用包包含全部 4 种。
6. 再次用同一版本号 `0.0.1` 运行时，工作流失败并提示版本已存在，已有的 Release 不被修改。
7. 本机不传版本号执行 `./gradlew :app:assembleDebug` 仍能正常构建。
8. `README.md` 和 `docs/architecture.md` 已按需求范围第 6 条更新。

## 影响范围

- 新增 `.github/workflows/release.yml`。
- `android/app/build.gradle.kts`：版本号参数、ABI 拆分、release 签名配置、APK 文件名。
- `README.md`、`docs/architecture.md`。

## 风险

- 第一次在 GitHub 上构建时，可能遇到运行环境与本机不同的问题（如 Android SDK 平台版本未安装、Gradle 下载慢），需要实际运行后调整。
- 验收需要在 GitHub 上真正运行工作流并创建 `v0.0.1` 发布，这是对外公开的操作（仓库是公开的）。测试通过后保留 `v0.0.1` 作为第一个正式版本。
- 签名密钥一旦丢失，之后的版本无法覆盖安装旧版本（用户需要先卸载）。密钥文件和密码需要你自己另外备份。

## 已确认的决策

1. 签名方式选 A：由我生成专用发布密钥，并用 `gh secret set` 写入仓库 Secrets（见需求范围第 4 条）。
2. 保留通用包 `universal.apk`。
3. 验收时创建的 `v0.0.1` 保留，作为第一个正式版本。
4. APK 文件名前缀用 `f2ft`。

## 变更记录

- v2（2026-11-15）：记录用户对 4 个待确认问题的决策；补充密钥的生成、本地保存位置和写入 Secrets 的方式；`v0.0.1` 测试后保留。
- v1（2026-11-15）：首次整理。
