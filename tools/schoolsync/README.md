# Classpp 远端学校适配声明（Tier 1）

本仓库是 Classpp 的**远端学校适配分发源**。用户在 App 设置页手动点击「更新远端学校列表」时，
App 会拉取 `index.json` 与各校声明文件，把「WebView 登录 + JSON 待办接口」类学校动态注册进
学校选择器。**接入一所新学校 = 本仓库增加一个 JSON 文件并在 `index.json` 登记，无需发版。**

> 远端学校**默认关闭**，拉取是**纯手动**的：App 不会在启动时或后台自动访问本仓库。
> 用户在学校选择浮层点击「更新远端学校列表」后，声明会下载并保存到本地，
> 之后每次冷启动从本地缓存恢复（仍不会自动联网更新）。

> 适用范围（Tier 1）：只支持网页（WebView）登录 + JSON 接口取待办。
> 需要原生登录（保存凭据/自动续期）或教务课表导入的学校，仍走 App 内编译期适配（Tier 2）。

---

## 1. 仓库结构

```
index.json               # 清单：登记哪些学校、各自的声明文件
schools/<id>.json        # 单校声明（文件名随意，index 里登记即可）
templates/demo.json      # 模板：复制起点，不入 index（生产用户不可见）
```

`index.json`：

```json
{
  "schemaVersion": 1,
  "schools": [
    { "id": "abc", "file": "schools/abc.json" }
  ]
}
```

- `schemaVersion`：当前 App 支持 `≤ 1`；更高的清单会被整体忽略（提示需要更新 App）。
- `id`：学校稳定标识，必须匹配 `^[a-z][a-z0-9_]{0,31}$`，且不得与 App 内置学校 id
  （目前为 `xmu`）冲突。**id 同时是存储命名空间、待办 source、本地 id 前缀，发布后不可改。**
- `file`：仓库内相对路径，必须 `.json` 结尾；禁止 `..`、绝对路径、反斜杠与 `://`。
- 单条登记非法只跳过该条，不影响其它学校。

---

## 2. 信任边界（重要）

- **所有 host 必须属于中国高校教育域名**：`allowedHosts`、`dataHost`、头像 `cookieHosts`
  的每一项都必须以 `.edu.cn` 结尾（`edu.cn` 本身、`foo.edu.cn.evil.com` 均拒绝）。
- 远端白名单不来自编译期代码，因此用户**首次连接某所远端学校时会看到确认浮层**，
  展示学校名与登录域；确认后才写入 `remote_sync_prefs`（`remote/<id>/confirmed`）。
- App 对声明做硬校验（见 §4/§5），任何一项不通过即跳过该学校；运行期还有
  `WebViewPolicy` 的 WebView 加固（https、默认端口、无 userinfo、无通配符等）。

---

## 3. 接入新学校流程

1. 复制 `templates/demo.json` 为 `schools/<id>.json`，把 `id` 改成学校标识；
2. 按 §4/§5 填 `webView` / `copy` / `profile` / `todo`（真实抓包确认路径与字段）；
3. 在 `index.json` 的 `schools` 里登记 `{ "id": "<id>", "file": "schools/<id>.json" }`；
4. push 到本仓库 `main` 分支；
5. 用户在学校选择浮层手动点击「更新远端学校列表」；更新成功后新学校出现在选择器，
   声明保存到本地，重启后仍可用（不会自动联网更新）。

**自测清单（不依赖 App 发版）：**

- 声明能通过 §4 的全部校验（可把 JSON 贴进本地单测的 `SchoolDeclaration.decode(...).validate()`）；
- 登录后 `/profile` 的 `uidPath` 非空、`namePath` 可读；
- `/todos` 的 `listPath` 是数组，`fields.*` 路径能取到值；
- `endTime` 是 UTC `Z` 结尾（`ISO`）或按声明的 `PATTERN` 能整串解析；
- `localIdTemplate` 以 `<id>-` 开头，`remoteKeyTemplate` 在校内唯一；
- 分组 `type` 取值与 `autoGroups` 精确匹配（大小写/首尾空白容错）。

---

## 4. 单校声明字段

### 4.1 顶层

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `schemaVersion` | 否 | 默认 `1`；`≤ 1` 才接受 |
| `id` | 是 | 学校稳定标识（规则见 §1） |
| `capabilities` | 否 | 默认 `["WEBVIEW_LOGIN"]`；Tier 1 **必须恰为该值** |
| `displayName` | 是 | 学校名（选择卡/入口卡行值） |
| `webView` | 是 | 网页登录与取数（§4.2） |
| `copy` | 是 | 页面文案（§4.3） |
| `profile` | 是 | 探活响应字段与头像（§4.4） |
| `todo` | 是 | 待办映射（§4.5） |

`LocalizedText` = `{ "zh": "...", "en": "..." }`：`zh` 必填非空；`en` 可省，缺省回退中文。
App 只支持 zh/en 两种语言，其它系统语言回落中文（与内置资源语义一致）。

### 4.2 `webView`

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `entryUrl` | 是 | 登录入口页，必须 https、默认 443、无 userinfo，host ∈ `allowedHosts` |
| `allowedHosts` | 是 | 登录/取数允许的域名列表（全部 `.edu.cn`） |
| `dataHost` | 是 | 取数主域，必须在 `allowedHosts` 内 |
| `profilePath` | 是 | `/profile` 路径（相对 `dataHost`） |
| `todosPath` | 是 | `/todos` 路径（相对 `dataHost`） |
| `userAgent` | 否 | `"desktop"` = 内置桌面 UA；也可写自定义 UA；省略 = 系统 UA |

### 4.3 `copy`

必填（全部 `LocalizedText`）：

`headerTitle`、`headerDescription`、`introTitle`、`introBody`、`loginSheetTitle`、
`sourceBadge`、`groupHomework`、`groupQuiz`、`syncNotifTitle`。

可选（省略 = 使用 App 内 `sync_*` 中性文案）：

`accountChanged`、`logoutMessage`、`switchMessage`、`serviceBusy`、`invalidCredentials`、
`sessionNotEstablished`、`autoLoginDisableMessage`。

> 文案纪律：含学校名/服务名的文案由本声明覆盖；不要依赖中性默认值表达校别语义。
> `groupHomework`/`groupQuiz` 只在分组**首次创建**时作为显示名，之后以组键定位。

### 4.4 `profile`

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `uidPath` | 是 | dot-path；值非空即视为已登录（也是 WebView 登录态判定） |
| `namePath` | 是 | dot-path；账号显示名 |
| `avatar.candidates` | 否 | 头像候选键，按序取第一个非空字符串（可下探 `user`/`profile`/`data` 容器） |
| `avatar.baseUrl` | 否 | 相对头像路径的补全基准，必须 https |
| `avatar.cookieHosts` | 否 | 允许携带会话 cookie 的域（全部 `.edu.cn`）；空 = 头像请求匿名 |

dot-path 只支持对象逐层下探（如 `user.uid`），段字符限 `[A-Za-z0-9_]`；不支持数组下标。

### 4.5 `todo`

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `listPath` | 是 | 待办数组在 `/todos` 响应里的 dot-path |
| `keyFields` | 是 | `[{name, path}]`：唯一键占位来源（`name` 限 `[A-Za-z0-9_]`，不得重复） |
| `remoteKeyTemplate` | 是 | 远端唯一键模板，如 `abc:{courseId}:{id}`；占位符必须是已声明 keyField |
| `localIdTemplate` | 是 | 本地 id 模板，**必须以 `<id>-` 开头**；占位符同上 |
| `fields.titlePath` | 是 | 标题 |
| `fields.courseNamePath` | 是 | 课程名（写入待办 `sourceDetail`） |
| `fields.typePath` | 否 | 远端类型（自动分组用）；空 = 不分组 |
| `fields.endTimePath` | 否 | 截止时间原文；空 = 全部按「无截止」跳过 |
| `endTime.format` | 否 | `ISO`（默认，UTC `Z` 结尾）或 `PATTERN` |
| `endTime.pattern` | 条件 | `PATTERN` 时必填，Java `SimpleDateFormat` 模式，必须整串匹配 |
| `endTime.zone` | 否 | 固定时区 ID（如 `Asia/Shanghai`）；缺省 `Asia/Shanghai`，不依赖设备时区 |
| `filters.requireStudent` | 否 | dot-path；值为 `false` 时按「非学生」跳过 |
| `filters.skipLocked` | 否 | dot-path；值为 `true` 时按「已锁定」跳过 |
| `filters.skipOverdue` | 否 | 默认 `true`；截止早于当前时刻按「已过期」跳过 |
| `noteTemplate` | 否 | 备注模板；占位符 = keyField 名 + `{courseName}`/`{type}`/`{activityUrl}` |
| `activityUrlTemplate` | 否 | 详情链接模板，占位符同上；省略 = 备注不带链接 |
| `autoGroups` | 否 | `[{type, group}]`：远端 type → 分组键后缀（组键 = `<id>:<group>`） |

**跳过规则（顺序固定）**：非学生 → id 非法 → 标题空白 → 无截止 → 截止解析失败 →
已锁定 → 已过期。`id` 键（若声明了名为 `id` 的 keyField）必须是正整数。
键值只允许 `[A-Za-z0-9_.-]` 且长度 ≤ 128（防止远端响应把本地 id/映射键撑坏）。

**内容哈希**：App 按 `sha256(标题|截止原文|课程名|类型)` 记录远端变化基线，只落哈希不落原文。

**本地 id**：由 `localIdTemplate` 渲染；重复同步天然幂等，不会产生重复待办。
**跨校隔离**：退出/换号只清理 `source == <id>` 或 id 前缀 `<id>-` 的待办。

---

## 5. 校验与容错

App 在解码后逐条执行以下硬校验，任一失败即跳过该学校（不影响其它学校）：

- `schemaVersion ≤ 1`；`id` 合法且与内置学校不冲突；
- `capabilities` 恰为 `["WEBVIEW_LOGIN"]`；
- `webView` 通过 `WebViewPolicy`（https/默认端口/无 userinfo/无 IP/无通配符/`dataHost` 在白名单内）；
- 所有 host 以 `.edu.cn` 结尾；
- dot-path 形态合法；模板占位符均已声明；`localIdTemplate` 以 `<id>-` 开头；
- 模板不含控制字符/反斜杠/路径分隔符（键模板），文本模板允许换行与 URL 斜杠；
- `endTime.zone` 是合法时区 ID；`PATTERN` 必填 `pattern`；
- `autoGroups.type` 非空、`group` 合法。

拉取纪律：

- **默认关闭，只由用户手动触发**（学校选择浮层「更新远端学校列表」），没有启动/后台/定时拉取；
- 清单/单校文件非 2xx、JSON 坏、校验失败 → 跳过（清单坏则本次更新失败，缓存原样保留）；
- 成功后写缓存（`filesDir/sync_remote/`），冷启动从本地缓存恢复远端校（不联网）；
- 已注册校的声明变更不热更新：再次手动更新会写入新缓存，重启后生效；
- 学校列表是 StateFlow：新校合并后选择器自动出现，无需重启 UI。

---

## 6. 发布纪律

- **单校失败隔离**：一个坏声明只会让那所学校不出现，不会拖垮整批或已注册学校；
- **修复无需发版**：路径/文案/过滤规则错误直接改 JSON 重新 push，用户下次手动更新生效；
- **不要改已发布学校的 `id`**：会丢失该校已导入待办的归属（旧数据留在原命名空间）；
- **不要降低 host 信任边界**：只允许 `.edu.cn`；需要其它域名请走 App 内编译期适配；
- **schema 演进**：新增字段必须可选（旧客户端 `ignoreUnknownKeys` 会忽略）；
  破坏性变更提升 `schemaVersion`，旧客户端会整体跳过而不是误读。
