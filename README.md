# 芝麻 (Sesame)

在 Android 平板上做一个**全天候语音唤醒**的 ChatGPT 语音助手：说出自定义唤醒词，
熄屏锁屏状态下直接进入 ChatGPT 的 GPT‑Live 语音会话。

唤醒词识别完全在本机离线完成（sherpa-onnx KWS），**不上传任何音频**。
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
git clone <this repo> && cd sesame
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

依赖（sherpa-onnx AAR + KWS 模型）已随仓库提供；如需自行拉取：

```bash
tools/fetch-deps.sh
```

### 设置

1. 打开应用，按"权限与设置"清单逐项授权：
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
- 常驻推理约占 **23% 单核**、**PSS 约 125 MB**（chunk-16 int8 encoder）。适合插电摆放，纯电池续航会有影响。
- 锁屏路径下 ChatGPT 不发出常驻通知，因此无法在锁屏时通过通知按钮挂断。
- 仅打包 arm64-v8a。

---

## 致谢与许可

本项目 Apache-2.0。

- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) v1.13.4（Apache-2.0），
  KWS 模型 `sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20`
- 拼音词典由 [pypinyin](https://github.com/mozillazg/python-pinyin) 在构建期生成

> ⚠️ 随仓库分发的模型权重与 `en.phone` 词典来自上游 release，二次分发前请自行确认其许可条款。
