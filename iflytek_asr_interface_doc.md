# 讯飞 ASR 接口对接说明（可复用）

本文档描述本仓库实际接入的讯飞 **AIUI 传统语义交互链路**（WebSocket `wsapi.xfyun.cn/v1/aiui`）听写用法，并按官网交互协议整理，供其他项目复用。

- **范围**：凭证申请 → HTTP 鉴权 → WebSocket 流式听写 → 两种业务用法（按住说话 / 自动聆听）
- **不含**：TTS、语义 NLP、翻译、所见即可说等未使用能力
- **MAC / auth_id**：见 [`讯飞WiFi-MAC获取说明.md`](./讯飞WiFi-MAC获取说明.md)
- **完整日志手册**：见 [`讯飞ASR日志手册.md`](./讯飞ASR日志手册.md)
- **「喘气后不跟嘴」排查结论**：见 [`讯飞ASR流式日志与跟嘴问题分析.md`](./讯飞ASR流式日志与跟嘴问题分析.md)

官方要点（对接前先建立共识）：

1. **短连接**：每次交互新建 WebSocket；会话结束后由**讯飞服务端**断开。
2. **两种结束方式**（时序图对应）：
   - **设备有 VAD**（客户端判停）→ 音频发完后发 `--end--`
   - **设备无 VAD**（依赖云端 VAD）→ 靠 `cloud_vad_eos` 判静音结束，可不发 `--end--`
3. **连接约束**：握手后连接超过约 **60s** 断开；超过约 **10s** 无数据交互断开；单次音频总时长 **&lt;60s**、体积 **&lt;2M**、帧数 **&lt;3000**。
4. **中断上传**时也必须发 `--end--`，再等服务端收尾断开；**客户端不应抢先主动断连**。

---

## 0. 能力分层（先选模式）

| 模式 | 对应官网时序 | 谁决定「说完了」 | 结束动作 | `cloud_vad_eos` 建议 |
|------|--------------|------------------|----------|----------------------|
| **按住说话** | 设备有 VAD | **客户端**（松手） | 停麦后发 `--end--`，等 `is_finish` | 拉大（本项目 `"60000"`），避免松手前被云端静音掐断会话 |
| **自动聆听** | 设备无 VAD / 云端 VAD | **云端** | 静音达到 `cloud_vad_eos` 后出终态；服务端断连 | 本项目 `"3000"`（官网示例常见为数百毫秒级，如 `700`） |

两种模式共用鉴权 + WS 协议；差异只在「谁发结束」以及如何解释 `is_finish`。

---

## 1. 总体调用顺序

```
① 取得设备 WiFi MAC，计算 auth_id
② 申请 / 读取缓存凭证（token、app_id、api_key）
③ HTTP POST 鉴权（license=token）
④ 签名并新建 WebSocket（每次交互一条新连接）
⑤ 收到 action=started 后再开麦、推 PCM
⑥ 持续发送二进制音频帧（保持有数据，避免 10s 空闲断连）
⑦ 结束：
     · 按住说话 → 发 "--end--"
     · 自动聆听 → 通常等云端 VAD（中断时仍应发 "--end--"）
⑧ 收到会话终态（is_finish=true）→ 由服务端断开 → 客户端清理本地资源
```

本仓库参考类（其他项目不必依赖类名）：

| 步骤 | 类 |
|------|----|
| ①②③ | `XunfeiCredentialProvider` / `DeviceMacReader` |
| ④～⑧ | `IflytekASRClient` |
| 开麦与编排 | `VoiceInputController` |
| UI | `VoiceChatOverlayController` |

---

## 2. 凭证：申请接口（HTTP）

| 项 | 值 |
|----|-----|
| 方法 | `POST` |
| URL（本项目） | `http://www.newlinksz.cn/chat/voice/xunfei/apply` |
| Body | JSON：`macaddr`、`sn` / `project_id` 等 |

成功响应取：

- `xunfei.token` → HTTP 鉴权 `license`
- `xunfei.app_id` → WS `appid`
- `xunfei.api_key` → 仅用于算 `checksum`

按 MAC 缓存；失败可降级旧缓存。详见 MAC 说明文档。**不要**把某台设备的 token 写死给其他项目。

---

## 3. 会话前鉴权（HTTP）

| 项 | 值 |
|----|-----|
| URL | `http://api.voice.gskiot.com/voice-api/voice/auth` |
| 方法 | `POST` |
| Content-Type | `application/json; charset=utf-8` |

```json
{
  "xiriSn": "1C:79:2D:02:F6:2D",
  "license": "<token>",
  "channel": "NEWLINK01",
  "devicewifiMac": "1C:79:2D:02:F6:2D",
  "deviceMac": "1C:79:2D:02:F6:2D",
  "sn": "QUALMETA-1C:79:2D:02:F6:2D",
  "clientVersion": "V1.0.1.2:2026-06-02:V1.4.4",
  "timestamp": "1780889790077"
}
```

| 字段 | 说明 |
|------|------|
| MAC 相关 | **大写**、冒号分隔 |
| `license` | 申请得到的 `token` |
| `channel` | 本项目 `NEWLINK01` |
| `sn` | 本项目 `"QUALMETA-" + WIFI_MAC` |
| `timestamp` | **毫秒**时间戳字符串 |

成功：`code == "00000"` 且 `data.status == 0`。

---

## 4. 流式听写（WebSocket）

| 项 | 值 |
|----|-----|
| URL | `ws://wsapi.xfyun.cn/v1/aiui`（可用 `wss`） |
| Origin | `http://wsapi.xfyun.cn` |
| 签名 | 本项目 `signtype=sha256`（官网默认也可 `md5`） |

### 4.1 握手

```
ws://wsapi.xfyun.cn/v1/aiui?appid=<appid>&checksum=<checksum>&curtime=<curtime>&param=<paramBase64>&signtype=sha256
```

1. `curtime` = **秒**级时间戳（有效期约 5 分钟；需东八区标准时间语义）
2. `param` JSON → UTF-8 → Base64 → `paramBase64`
3. `checksum` = `SHA256(apiKey + curtime + paramBase64)` 小写十六进制  
   （若用 md5：`MD5(apiKey + curtime + paramBase64)`）
4. Header：`Origin: http://wsapi.xfyun.cn`

### 4.2 `param`（本项目听写实际发送）

```json
{
  "result_level": "plain",
  "auth_id": "<MD5(wifiMac去冒号小写)>",
  "data_type": "audio",
  "aue": "raw",
  "scene": "main",
  "sample_rate": "16000",
  "dwa": "wpgs",
  "cloud_vad_eos": "60000"
}
```

| 字段 | 说明 |
|------|------|
| `auth_id` | 设备唯一 ID，32 位小写字母数字；本项目由 MAC 计算 |
| `aue` / `sample_rate` | `raw` + `16000`，与 PCM 采集一致 |
| `scene` | `main`（普通话主场景） |
| `dwa` | `wpgs`：动态修正，便于流式刷新 Partial |
| `cloud_vad_eos` | 云端后端点静音时长（ms 字符串）。**按住说话拉大；自动聆听用较短值** |
| `close_delay` | 官网可选：交互完成后云端延迟关连接，范围 `[0,200]` ms。本项目未传 |
| `vad_info` | 官网可选：`end` 时额外下发 VAD 端点事件。本项目未用 |

官网示例里的 `pers_param`、`main_box`、`vad_info` 等，本听写路径未使用。

### 4.3 上传音频与结束符

| 项 | 要求 |
|----|------|
| 内容 | **二进制** PCM（非 JSON） |
| 格式 | 16 kHz、单声道、16-bit 小端 |
| 分片 | 本项目约 **3200B / ~100ms** |
| 开麦 | 收到 `action=started` **之后** |
| 结束符 | 字符串 `--end--` 的二进制（ASCII） |

官网：数据发完后，**若不走「云端 VAD + 延迟断连」**，客户端必须发 `--end--`。  
按住说话属于客户端判停，**松手必须发 `--end--`**。

### 4.4 下行字段（务必按官网语义理解）

```json
{
  "action": "result",
  "code": 0,
  "data": {
    "sub": "iat",
    "text": "今天天气真好",
    "is_last": false,
    "is_finish": false
  },
  "sid": "..."
}
```

| 字段 | 官网含义 | 正确用法 |
|------|----------|----------|
| `action` | `started` / `result` / `error` / `vad` | `started` 后开麦；`error` 后会话结束 |
| `data.text` | 识别文本（`plain` 时为 string） | 流式刷新 UI |
| `data.is_last` | **该业务**（如 iat）是否最后一条 | 业务内收尾参考 |
| `data.is_finish` | **本次会话**是否最后一条 | **会话结束标志**；之后服务端会断连 |
| `sid` | 会话 ID | 排查问题时提供给讯飞 |

**常见误读（旧实现踩坑）**：

- 把 `is_finish=true` 当成「一句话中间的段结束、还可以在同一连接里继续说」——**不对**。官网明确：`is_finish` 表示**本会话最后一条**，随后连接由服务端关闭。
- 因此按住说话时**不应**用 `is_finish` 做「多段拼接」；同一按住周期内只应看到流式 `text` 刷新（`dwa=wpgs`），直到松手 `--end--` 后出现真正的会话终态。

### 4.5 客户端事件抽象建议

| 事件 | 条件 |
|------|------|
| `Partial(text)` | `action=result` 且尚未会话结束，有文本 |
| `Final(text)` | `is_finish == true`（或已发 `--end--` 后的会话收尾）；业务可提交 |
| `Error` | `code ≠ 0` / `action=error` / 鉴权失败等 |

收到 `Final` / 服务端 `onClosed` 后再做本地 `close` 清理；**不要**在结果未收齐时抢先 `WebSocket.close`。

---

## 5. 按住说话（Hold-to-Speak）

对齐官网 **「设备有 VAD」** 时序：手指 = 本地 VAD。

### 5.1 正确时序

```
按下
  → 新建 WS（短连接，一次按住一次连接）
  → started → 开麦 → 持续 send PCM（注意 <60s / 勿超 10s 无数据）
  → 仅刷新 Partial(text)（不要把 is_finish 当段边界）
松手（有效）
  → 停麦
  → 立即 send("--end--")          ← 客户端判停的关键动作
  → 等待服务端下发识别结果，直到 is_finish=true
  → 服务端断开连接
  → 业务提交最终文本；本地释放资源
取消（上滑 / 短按无效等）
  → 停麦 → 仍应 send("--end--") 再清理（官网：中断上传也必须发 end）
```

### 5.2 参数

```
模式语义        = 设备有 VAD（客户端判停）
cloud_vad_eos   = "60000"   // 拉近单次会话上限，降低「未松手就被云端静音结束」概率
松手            = 发 --end--
```

说明：官网 `cloud_vad_eos` 示例多为短静音（如 `700`）。那是**云端 VAD**场景。按住说话若仍用短值，用户换气停顿就可能 `is_finish` + 断连，会话已死，继续推音频无效。拉大 eos 是为了**在松手前尽量不让云端抢先结束会话**，真正结束仍靠 `--end--`。

### 5.3 状态机

```
IDLE
  │ 按下
  ▼
HOLDING（推流 + 显示实时识别）
  │ 松手且有效
  ▼
WAITING_FINAL（已发 --end--，等 is_finish）
  │ 收到 Final / 超时兜底
  ▼
IDLE（提交业务）
```

本项目附加产品规则（非协议强制）：按住 &lt;1s 视为太短并取消；上滑取消。

### 5.4 易错点（对照官网）

1. **结束符**：松手必须 `--end--`，不能只停麦或只关 Socket。
2. **断连方向**：等讯飞断；本地仅在会话结束后清理。抢先 `close` 可能导致收不到最后一包。
3. **不要用 `is_finish` 拼多段**：同一连接内 `is_finish` = 会话结束，不是段标记。
4. **超时**：连接约 60s、空闲约 10s、音频 &lt;60s —— 按住过久或松手后迟迟不发 `--end--` 都会失败。
5. **短连接**：下次再按住必须**新建**连接，不能复用已 `is_finish` 的连接。

### 5.5 伪代码

```text
onFingerDown:
  openNewWebSocket(param.cloud_vad_eos = "60000")
  on started: startMic(); loop sendAudio(pcm)

onPartial(text):
  subtitle = text          // 覆盖刷新即可（wpgs 动态修正）

onFingerUp(valid):
  if not valid: send("--end--"); teardown; return
  stopMic()
  send("--end--")
  wait until is_finish (timeout 3~5s fallback)
  // 优先等服务端 onClosed，再本地释放
  submit(finalText)

onCancel:
  stopMic()
  send("--end--")
  teardown
```

---

## 6. 自动聆听（云端 VAD）

对齐官网 **「设备无 VAD，依赖云端 VAD」**。

### 6.1 参数与行为

```
cloud_vad_eos = "3000"     // 本项目；可按体验调到官网常见的数百~几千 ms
结束          = 云端静音判停 → is_finish → 服务端断连
```

- 正常结束：**不必**为了「说完」再发 `--end--`（云端 VAD + 断连路径）。
- **中断**（用户改按住说话、关窗等）：仍应发 `--end--` 再清理。
- 收到非空 `Final` → 交给业务；是否立刻再开下一轮 = 产品策略（下一轮必须**新连接**）。

### 6.2 与按住并存

自动聆听中若按下「按住说话」：先中断当前会话（`--end--` + 清理），再按第 5 节开新连接。

---

## 7. 采集与权限（Android）

| 项 | 要求 |
|----|------|
| 权限 | `RECORD_AUDIO`；后台采麦需符合 FGS 类型 |
| `AudioRecord` | `MIC` / 16000 / MONO / PCM_16BIT |
| 开麦 | `started` 之后 |

---

## 8. 其他项目最小接入清单

- [ ] MAC → `auth_id`；凭证申请与缓存
- [ ] 每次会话 HTTP auth → **新建** WS
- [ ] `started` 后推 PCM；注意 10s 空闲 / 60s 会话上限
- [ ] 模式二选一：客户端 `--end--` **或** 云端 `cloud_vad_eos`
- [ ] 按住：松手发 `--end--`；`is_finish` 仅作会话结束；不抢先断连
- [ ] 取消/中断：也发 `--end--`
- [ ] 正确理解 `is_finish`（会话结束）与 `is_last`（业务结束）
- [ ] Final 超时、鉴权失败有兜底

---

## 9. 与错误理解 / 旧实现的对照

| 错误或过时理解 | 官网 / 正确做法 |
|----------------|-----------------|
| `is_finish` = 中间断句，可同连接继续听 | `is_finish` = **本会话最后一条**，随后服务端断连 |
| 按住期间用 `is_finish` 做多段拼接 | 按住只刷新 Partial；松手 `--end--` 后一次 Final |
| 客户端结果一到就 `WebSocket.close` | 应由服务端断连；客户端事后清理 |
| 取消时直接关 Socket、不发 end | 中断上传也必须 `--end--` |
| 按住也用很短的 `cloud_vad_eos` | 易被云端抢先结束；按住应拉大或接受「停顿即会话死」 |
| 自动聆听松手式发 end 才结束 | 自动聆听靠云端 VAD；中断才需要 end |

---

*文档版本：2026-07，按 AIUI 传统语义交互 API + 仓库听写实现整理，面向跨项目复用。*
