# GPTWake

在 Android 平板上做一个**全天候语音唤醒**的 ChatGPT 语音助手：说出自定义唤醒词，
熄屏锁屏状态下直接进入 ChatGPT 的 GPT‑Live 语音会话。

唤醒词识别完全在本机离线完成（sherpa-onnx KWS），**不上传任何音频**。
默认唤醒词「芝麻开门」，可在应用内改成任意中文或英文短语。
**不需要 root、不需要解锁 bootloader、不需要 Magisk/LSPosed。**

![状态](https://img.shields.io/badge/状态-可用-brightgreen) ![许可](https://img.shields.io/badge/license-Apache--2.0-blue)

---

## 它解决了什么

Android 14+ 对后台麦克风和后台启动 Activity 的限制，使"第三方应用在锁屏下唤起另一个应用的语音会话"
并不是写几行代码就能成的。本项目在真机上逐条验证并解决了这些系统门槛：

| 门槛 | 解法 | 实测 |
|---|---|---|
| 锁屏下 ChatGPT 拿不到麦克风 | **必须让 ChatGPT 保持系统默认助理**，它的 VIS 进程是 `PROC_STATE_PERSISTENT` | 抢走 role 后 `RECORD_AUDIO duration=0`，还回去后正常 |
| 后台启动 Activity 被 BAL 拦截 | `SYSTEM_ALERT_WINDOW` 豁免 | 无 SAW：`Background activity launch blocked!`；有 SAW：全通 |
| 后台起不了 microphone FGS | 透明 `showWhenLocked` Shim Activity 提供一个可见时刻 | 熄屏 + 安全锁屏下成功，屏幕不点亮 |
| 开机后无法自动恢复 | `LOCKED_BOOT_COMPLETED` → Shim → FGS（20 秒临时白名单内） | 首次解锁前 1.42 秒进入监听 |
| 语音会话结束后收不回麦克风 | 同一 FGS 内直接重建 `AudioRecord`，不经 Shim | 113 ms 恢复 |
| 判断 ChatGPT 语音是否真的起来了 | `AudioManager` mode + `AudioRecordingConfiguration` 复合判据 | 锁屏路径下 ChatGPT 不发通知，只能靠音频状态 |

唤醒词命中到 GPT‑Live 会话确认：**约 870 ms**，全程屏幕不亮、锁屏不解除。

---

## 功能

- 🎙️ 离线唤醒词（sherpa-onnx zipformer KWS，中英双语模型）
- ✏️ **自定义唤醒词**：直接在应用里输入中文或英文，设备本地转换成模型音素，无需联网、无需重新打包
- 🔒 锁屏熄屏下工作，屏幕不点亮
- 🔁 开机自动恢复，**首次解锁前**即可监听（Direct Boot）
- 🎚️ 灵敏度可调
- 🧪 测试模式：命中只震动提示，不打开 ChatGPT
- 📞 检测到通话/VoIP 自动暂停

---

## 快速开始

### 环境

- Android 13+（`minSdk 32`，实测 Android 16 / Lenovo TB355FU）
- arm64-v8a
- 已安装官方 ChatGPT 应用并登录

### 构建

```bash
git clone https://github.com/suddenBook/GPTWake.git && cd GPTWake
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

需要 **JDK 17**、**AGP 9.3.1+**，以及 SDK 里的 `platforms;android-37.0` 和 `build-tools;37.0.0`
（`compileSdk 37` 是 compose.ui 1.12.0-beta02 要求的，`targetSdk` 仍是 36）。

或者直接从 [Releases](https://github.com/suddenBook/GPTWake/releases) 下载已签名的 APK。

### 发布

`.github/workflows/release.yml`：push 到 `main` 就自动构建 release APK、用仓库 secrets 里的
keystore 签名（`zipalign` + `apksigner`），并按 `versionName` 打 tag `v<versionName>` 创建/更新
GitHub Release。仓库里**不存放任何签名材料**——release 构建产出的是未签名 APK，签名只发生在 CI。

需要四个 repository secret：

| Secret | 说明 |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | keystore 文件的 base64（单行） |
| `ANDROID_KEYSTORE_PASSWORD` | keystore 密码 |
| `ANDROID_KEY_ALIAS` | key alias |
| `ANDROID_KEY_PASSWORD` | key 密码（PKCS12 下必须与 keystore 密码相同） |

依赖（sherpa-onnx AAR + KWS 模型）已随仓库提供；如需自行拉取：

```bash
tools/fetch-deps.sh
```

### 设置

1. 首次打开应用会自动请求麦克风和通知权限；顶部的引导卡片会逐项带你完成剩下的：
   - 麦克风
   - 通知
   - **显示在其他应用上层**（锁屏唤醒和开机自启依赖它，缺了不行）
2. 确认 **ChatGPT 是系统默认助理**（设置 → 应用 → 默认应用 → 数字助理）。
   这一项不能换成本应用 —— ChatGPT 需要这个身份才能在锁屏下录音。
3. ChatGPT → Settings → Voice：
   - `Background conversations` = ON
   - `Start with Voice` = ON（可选，只影响回退路径）
4. 回到本应用点"启动"。

### 换唤醒词

在"唤醒词"卡片里输入新词，下方实时显示拼音/音素与模型 token，点"应用"即可。

```
芝麻开门     → zh ī m á k āi m én
你好电脑     → n ǐ h ǎo d iàn n ǎo
open sesame → OW1 P AH0 N S EH1 S AH0 M IY0
```

建议 4–6 个音节，太短会频繁误唤醒（应用会提示）。

---

## 界面

Jetpack Compose + **Material 3 Expressive**（`MaterialExpressiveTheme` / `MotionScheme.expressive()`）。

- **配色跟随系统**（Material You 动态取色，API 31+），不再是固定的基线紫。
- **状态区**是一个由 `MaterialShapes` 变形（`Cookie9Sided` ↔ `Sunny`）驱动的图形，
  颜色和卡片底色一起编码七种引擎状态，尺寸跟着实时麦克风电平走 —— 隔着房间也能看出在不在听。
- **自适应宽度**：≥840dp 时状态区通栏、下面按职责分两列（左边是你要动的，右边是你要看的），
  窄屏则单列；事件日志始终通栏，因为它是等宽长行。
- 界面语言随系统，内置**英文和中文**，也可以在系统设置里单独给这个 app 选语言
  （`localeConfig`）。
- 图标为自适应矢量图标，前景是同一个 Expressive 形状挖空出麦克风轮廓，含 monochrome 图层。

> 依赖说明：Expressive 组件**目前没有任何稳定版**。它们在 `material3 1.4.0-alpha18` 出现过，
> 在 `1.4.0-beta01` 之前被移除，现在只存在于 `1.5.0-alpha24`。在稳定的 1.4.0 上
> `MaterialExpressiveTheme`、`MotionScheme`、加大的 shape scale 全部是 Kotlin `internal`，
> app 代码根本调不到。所以本项目用 `compose-bom-alpha`，并因此需要 `compileSdk 37`
> （`targetSdk` 仍是 36）。升级 BOM 前请先看 release notes。

## 架构

```
开机 / LOCKED_BOOT_COMPLETED
  └─ BootReceiver
       └─ ShimActivity  (透明 / showWhenLocked / 不点亮屏幕)
            └─ KwsForegroundService  (FOREGROUND_SERVICE_TYPE_MICROPHONE)
                 ├─ AudioRecord 16 kHz mono VOICE_RECOGNITION
                 ├─ sherpa-onnx KeywordSpotter (常驻)
                 └─ WakeController 状态机

唤醒命中
  ├─ 停止喂帧 → stop / join / release → 等采集配置消失 → drain 250 ms
  ├─ startActivity(com.openai.chatgpt/com.openai.voice.assistant.AssistantActivity)
  │    ↑ 未文档化的内部组件，运行时校验 exported/permission，失败回退 chat.com/?mode=voice
  └─ 5 秒内用 audio mode + recording config 确认会话建立

会话结束
  └─ 同一 FGS 内重建 AudioRecord + 新 OnlineStream → 继续监听
```

### 关键实现说明

- **不要让本应用抢占 Assistant role。** 实测证明 ChatGPT 失去该身份后，锁屏下无法取得麦克风。
- `com.openai.voice.assistant.AssistantActivity` 是 ChatGPT 未公开的内部组件。代码在每次启动前
  检查它是否存在、是否 `exported`、是否需要权限，失败则回退到公开 deeplink。ChatGPT 更新后若组件
  变化，应用会退回 deeplink 而不是静默失效。
- `startActivity()` 被 BAL 拦截时**不会抛异常**，必须用旁路信号确认。

---

## 已知限制

- 唤醒词召回率与词本身强相关。当前默认词「芝麻开门」在 `threshold=0.40` 下实测近场 8/10、3 米 8/10。
- **PSS 约 125 MB**、native heap 约 60 MB（5.4 MB 权重 + ORT arena 过度分配）。这个数是实测的。
- ⚠️ **常驻推理的 CPU 占用目前没有可信实测。** 仓库里唯一一次记录是**插着 USB** 跑的
  （`plugged=2`），所以那份数据里的电流是充电电流，不是耗电；而且只有一个窗口，还包含了
  模型加载和 JIT 预热，得到的 14.2% 是冷启动均值。**过去 README 里写的「约占 23% 单核」
  在仓库任何文件里都找不到出处，已删除。** 要拿到真数据请拔掉 USB 跑
  `./testkit.sh power A1|B1|B2|A2`。
- 没有 VAD / 能量门控，encoder 对 100% 的音频（含静音）无条件推理。这是最大的一块可省功耗，
  分析和可选方案见 [`docs/power.md`](docs/power.md)。适合插电摆放，纯电池续航会有影响。
- 锁屏路径下 ChatGPT 不发出常驻通知，因此无法在锁屏时通过通知按钮挂断。
- 仅打包 arm64-v8a。
- 应用**不会**也**不应该**接管系统默认助理；那个身份必须留给 ChatGPT。

---

## 致谢与许可

本项目 Apache-2.0。

- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) v1.13.4（Apache-2.0），
  KWS 模型 `sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20`
- 拼音词典由 [pypinyin](https://github.com/mozillazg/python-pinyin) 在构建期生成

> ⚠️ 随仓库分发的模型权重与 `en.phone` 词典来自上游 release，二次分发前请自行确认其许可条款。
