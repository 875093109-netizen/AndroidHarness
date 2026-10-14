# AndroidHarness 简体中文源码补丁（实验版）

对应仓库：[Sanuu7/AndroidHarness](https://github.com/Sanuu7/AndroidHarness)。
制作时参考的上游版本是 `main`、v2.2，树对象 SHA `121d421cd4f44f2e9937ab52fb4f651a61a8307f`（2026-10-15 取得）。上游更新后可能需要更新词典。

## 文件说明

- `apply_zhcn.py`：补丁程序，只改动 `app/src/main/java/com/androidharness/app/ui/` 下的 Kotlin 界面源码。不会修改模型 API、调用工具、授权流程或账号数据。
- `translations.tsv`：可扩展中英文字典，以制表符分隔两列。
- `test_patch.py`：补丁的基础单元测试。
- `zhcn-build.yml`：在 GitHub Actions 云端编译 APK 的工作流。

本补丁是**静态源码文本汉化**，并非 App 内的动态语言切换插件。对于基于状态拼接的英文、非 `ui` 目录中的提示、服务端返回的错误、模型内容，仍可能出现英文。代码中的短字符串可能被用作逻辑判断；补丁对短词仅尝试在显式显示文本的上下文中替换，以降低破坏性。需要 Android/Compose 的完整构建、运行测试才能评定实际覆盖率。

## A. 电脑构建（需 JDK 21、Android SDK 和可下载 Gradle 依赖）

```bash
git clone https://github.com/Sanuu7/AndroidHarness.git
cd AndroidHarness
mkdir zhcn
# 把 apply_zhcn.py、translations.tsv、test_patch.py 放入源码根目录的 zhcn/ 中
python3 zhcn/apply_zhcn.py --root . --dry-run
python3 zhcn/apply_zhcn.py --root . --report zhcn/report.json
./gradlew :app:assembleDebug
```

工程配置要求 Android SDK compileSdk 37、JDK 21、Gradle Wrapper 9.7。构建原生组件时需要 NDK `27.2.12479018`；上游配置在缺少 NDK 时会跳过相应本地组件，不建议省略。请按 Android Studio 提示安装依赖。

生成 APK：`app/build/outputs/apk/debug/app-debug.apk`。

## B. 只有安卓手机（推荐 GitHub Actions 云构建）

1. 在手机浏览器打开 [AndroidHarness 仓库](https://github.com/Sanuu7/AndroidHarness)，登录 GitHub，点击 **Fork** 创建你自己的副本。
2. 在 Fork 的根目录新建 `zhcn/` 文件夹，上传 `apply_zhcn.py`、`translations.tsv`、`test_patch.py`。可以使用 GitHub 网页的 **Add file → Upload files**，路径为 `zhcn/文件名`。
3. 在 `.github/workflows/` 下新增 `zhcn-build.yml`，内容使用补丁包内同名文件（注意不要放错路径）。
4. 在 Fork 的 **Actions** 中启用工作流（GitHub 可能需要你主动启用），找到 **Build AndroidHarness zh-CN APK**，选择 **Run workflow**。
5. 工作流成功后，在运行页面底部 **Artifacts** 下载 `AndroidHarness-zhCN-debug` 压缩包，解压并安装 `app-debug.apk`。

若工作流卡在 SDK 37 / Gradle 依赖下载，请查看构建日志。这些依赖由上游工程定义，本补丁不自行修改其版本。

## 安装注意

- `debug` APK 的包名带 `.debug` 后缀，通常能与 Play 商店正式版并存，但不会自动迁移正式版账号数据和 API Key。
- 安装后请先确认「模型服务商 → 选择模型 → 聊天 → 工具审批」均正常，再授予文件、Shizuku 等敏感权限。
- 不要在公开仓库提交 API Key、Cookie、Token、用户角色卡或敏感配置。
- 此补丁非官方发布，也**没有在本环境内完成 Android APK 编译和设备测试**。发现界面异常可先用 `git diff` 查看修改；恢复可以 `git restore app/src/main/java/com/androidharness/app/ui`。

## 调整翻译

用文本编辑器修改 `translations.tsv`。每一行是 `英文原文<TAB>简体中文`。请保留英文中的 Kotlin 插值变量（例如 `$providerName` 或 `${status.toolCount}`），否则补丁会拒绝运行。注意已有译文无法无损自动恢复，请从干净的 Git 分支重新执行。

## 已知限制

1. 短文本如 `Save` 仅在显式 `Text(...)` 或显示属性附近替换；其他包装组件仍可能是英文。
2. UI 外的用户可见字符串未批量覆盖，以避免修改 API、工具调用、模型提示和其他程序逻辑。
3. 原始代码可能把英文文案用于状态分支；静态源码替换仍然存在兼容性风险，不能等同于已经验证的正式汉化版。
4. 只有实际构建和运行测试之后，才可以确认 APK 能否使用；目前交付的是可应用、可审计的源码补丁和自动构建配置，不是现成 APK。
