# 本机开发服务（HTTP v2）

首次打开 App 并授权作品目录后默认启动服务；「开发」可暂停或恢复，主动暂停会保留到用户再次开启。默认地址 `http://127.0.0.1:17653`，只监听回环，不提供远程 Shell。不同开发工具共用同一协议。用户关闭服务后连接不可用；客户端不得回退到本地核心执行工程操作。

## 配对与授权

`GET /v2/capabilities` 返回 `protocol_version`、`installation_id`、`core_version` 和实际可用的 `operations`。客户端必须核对身份及协议；准备辅助代码与服务选择的核心版本一致。

页面直接显示地址、一次性配对码和有效期，也提供标准命令与可选的 AI 说明。邀请一小时内有效，客户端提交 `POST /v2/pairings`：

```json
{"code":"页面显示的配对码","label":"开发工具名称","access":"shared","request_id":"调用端持久保存的32位随机十六进制标识"}
```

`shared` 用于挂载了公共目录的 CLI：按返回的 `probe` 相对路径读取文件、计算 SHA-256，在同名 `.response` 文件写入 `challenge`，随后向 `/v2/pairings/{id}/verify` 提交 `secret` 和 `proof`。验证后仍须由用户确认。有悬浮权限时显示临时确认卡，否则可直接使用通知中的允许/拒绝操作，也可在 App 页面确认。通知被系统折叠时，点击通知打开独立确认框，完成后自动返回原应用。锁屏时不能授权。

无法挂载共享目录的普通 HTTP 客户端使用 `access: "files"`，直接等待用户确认，通过文件接口操作作品。此授权不能提交需要开发端工具准备的操作。

客户端用 `POST /v2/pairings/{id}/result` 和 `{"secret":"本次配对返回值"}` 等待 `approved`，私有保存返回的 `token`、`client_id`、`installation_id`。后续请求均携带 `Authorization: Bearer <token>`，包括 WebSocket 握手。不要在 URL、提示词、公共目录或日志中存放令牌。App 可撤销连接；客户端也可 `POST /v2/clients/revoke` 撤销自身授权。

服务声明 `pairing.resume=true` 时，开始配对支持上述 `request_id`：相同标识和内容返回原请求，内容不同会拒绝。客户端先私有保存标识与握手，再执行验证；验证可幂等重试。请求与批准结果在私有存储中保存，可跨服务进程重启恢复。请求有效十五分钟，目录验证成功后重新给予十五分钟确认时间。返回的 `expires_at` 是毫秒时间戳。请求明确过期才需要新邀请码，不能因网络瞬断或挂载错误反复新建请求。

服务空闲时只保留系统通知，不显示常驻悬浮图标。外部游戏操作开始时临时显示真实进度卡，以满足系统的可见窗口要求；实际进入画中画后收起。配对确认不使用画中画，避免占用游戏窗口。

## 工程、游戏与任务

`GET /v2/projects` 列出作品。操作中的 `project` 是 `projects/<目录名>`，不使用开发端绝对路径。实例属于作品，不能重绑定到其他作品；宿主只允许一个活动游戏实例。

```http
POST /v2/operations
Content-Type: application/json
```

```json
{"request_id":"调用端生成的唯一标识（16–80字符）","operation":"build","project":"projects/example","parameters":{},"preparation_client":true}
```

返回 `operation_id`，随后 `GET /v2/operations/{id}` 查询。完成时 `result` 包含结果；失败保留 `error/code/hint`。`queued`、`running`、`waiting_preparation` 是非终态，`completed`、`failed`、`interrupted`、`cancelled`、`unknown` 是终态。`running` 不代表游戏玩法或源码验证成功。

`request_id` 在同一客户端内必须唯一。相同请求返回原任务，不执行第二次；同一个 ID 携带不同内容会被拒绝。v2 操作 ID 为 UTF-8 `client_id + ":" + request_id` 的 SHA-256 十六进制值，可在提交响应丢失时直接查询。找不到任务时也不要自动换 ID 重放副作用。

`POST /v2/operations/{id}/cancel` 请求中断。已经发出的游戏请求或外部脚本可能继续执行，必须查询状态，不能将中断理解为事务回滚。服务重启后未完成任务标为中断，不自动重放。

主要操作：

| 操作 | 参数 |
| --- | --- |
| `init` / `mod` / `add` / `remove` / `sync` / `build` / `package` | 标准 mcpy 命令参数；由 App 核心执行 |
| `script.list` / `script.run` | `effective`；或 `name`、`args` |
| `run` | `new`、`instance_prefix` 等标准单实例选项 |
| `status` / `stop` / `restart` | `session`；status 可用 `list` |
| `runtime.capabilities` | `session`，查询实际端侧和运行状态 |
| `runtime.py` | `session`、`side: client/server`、`code` |
| `runtime.reload` | `session`、`side`、`kind: python`，及作品内 `filename` 或 `module` |
| `screenshot` | `session`，从游戏 Surface 获取完整 PNG |
| `instances.list` / `instances.create` / `instances.trash` | 创建传 `name`；移入回收站传完整 `instance` ID |
| `projects.rename` / `projects.trash` | 重命名传 `name`；作品及所属实例共用回收规则 |
| `files.write` / `files.trash` | 下述文件参数；移入回收站传 `path` |

不支持的地图、多客户端、桌面路径覆盖、输入控制或 Python 排队条件不能因协议统一而假定可用。后台启动需要用户开启临时提示（系统悬浮窗）授权；被系统拦截时返回真实失败或未知状态。

`status` 中的 `pip` 是 Android 画中画标志，不是 Python 环境状态。`loadingStage` 描述当前加载阶段；`serverReady` 表示服务端源码初始化记录已生成，仍应通过双端运行时探针验证具体模组。`bufferWidth/Height` 是游戏渲染缓冲区大小，`viewWidth/Height` 是当前窗口视图大小；截图来自完整缓冲区，不能单独证明聊天页上方的小窗可见。

游戏菜单中的「保存并退出」和宿主 `stop` 共用保存完成确认。`status.exit_evidence` 提供 `world_unloaded`、`world_files`（`open/closed/unknown`）、`confirmed`、`source`（`game_exit/host_request`）、`observed_at`、查询时计算的 `age_ms` 与采样时的 `screen`。只有本次会话观察到已进入世界、世界文件曾打开，随后连续采样确认世界卸载且文件句柄关闭，才写入会话目录的 `save-exit.complete` 并转为 `saved`。这不是独立的游戏存档校验工具，也不能保证异常断电后的物理介质持久性。检查失败、超时或进程消失不作为保存成功证据；世界尚未开始加载时的取消记为 `cancelled`。画面名称仅用于诊断，不用主菜单或保存页的显示状态代替完成确认。

## 开发端准备阶段

具有共享目录授权且声明 `preparation_client` 的请求，可以收到 `waiting_preparation` 与 `preparation` 对象（`id/kind/options/core_version`）。由标准 CLI 执行限定的 Git 源获取、pip、工具环境、包元数据查询和已声明脚本，并将 `{"ok":true,"result":...}` 或结构化错误提交到 `/v2/operations/{id}/preparation/{step}`。

阶段顺序由 App 核心决定，包含组装之后的 `before_archive`。不得提前执行所有 hooks。脚本执行前持久记录开始状态，执行后保存结果，再提交给 App；中断后只能重传已保存结果，不重跑结果未知的阶段。App 对重复提交核对内容，已经收到的相同结果可安全重传。

## 文件、日志与产物

- `GET /v2/files?project=projects/example&path=behavior_pack/example.py` 返回文本与 `digest`。
- 同一路径加 `list=true` 列举目录。
- `PUT /v2/files` 携带 `request_id/project/path/text/digest`，返回任务 ID，按任务接口等待结果。创建时传 `create:true`；创建目录再传 `directory:true`。已有文件必须使用读取时的摘要，冲突不覆盖。作品身份文件不能直接修改。
- `GET /v2/logs?project=projects/example&session=<id>&source=game&tail=100` 返回文本及游标；传 `after` 继续读取。`game` 包含带端侧标签的日志，也可分别选 `client`、`server`；引擎日志用 `engine`，不同来源不混用游标。
- 截图和打包完成后，`GET /v2/artifacts/{artifact_id}` 下载结果文件；其他客户端不能读取不属于自己的任务产物。
- `GET /v2/events?after=<sequence>` 查询事件；同一路径用 WebSocket Upgrade 订阅事件。WebSocket 中断后通过 HTTP 查询原任务，事件流不能代替任务结果。事件为有限保留，长期断线应重新查询作品、会话与任务状态。

用户源码、公共缓存、构建结果、存档和日志都位于 `/sdcard/mcpy`。客户端工具环境与长期凭据保存在其应用私有目录。

## 启动检查、诊断与恢复

`capabilities` 与 `doctor` 返回 `launch_preconditions`：`ready` 表示没有已知阻塞项，`checks` 分别列出工作区权限、运行环境、锁屏、悬浮权限、PiP 支持和可查询的 PiP 授权。每项为 `ok/missing/unknown/not_required`，缺失项包含提示及可用的系统设置 action/URI。可查询的厂商后台弹出限制独立列出：小米系读取本应用的 AppOp 10021，被拒绝时返回 `vendor_background_launch_denied`；系统不支持查询时为 `unknown`，不能保证系统一定接受启动。`run/restart` 在工程准备前检查工作区与运行环境等硬性条件；可恢复的界面权限问题允许先完成准备，再进入 `waiting_user`。

会话查询使用统一的观察结果：

- `reported_phase`：游戏最后写入的原始阶段；`phase/state`：综合当前进程与记录新鲜度后的阶段。
- `heartbeat`：只有游戏进程确实上报时才存在，否则为 `null`。`created_at` 是部署记录创建时间，`observed_at` 是此次查询时间，二者不充当游戏心跳。
- `process_alive`：进程存活检查，无法确认时为 `null`。心跳停滞报告 `unresponsive`，不直接断言崩溃；已确认进程消失报告 `interrupted`。
- `bootstrap`、`launch_receipt`：进程/类加载与 Activity 启动阶段证据。启动无确认时报告 `unknown`，不能仅由超时判断被系统拦截。
- `process_exit`：系统可提供且匹配该进程与会话时间的退出信息；`engine_log_tail`：有界的引擎日志尾部。`session`、`instance_id`、`project` 保留原目标。

常见错误码包括 `pip_permission_missing`、`overlay_permission_required`、`runtime_not_installed`、`device_locked`、`engine_bootstrap_failed`、`engine_exited`、`engine_crashed`、`game_heartbeat_stale`、`game_start_unknown` 与 `pip_not_confirmed`。只有明确的系统安全拒绝才标为 `launch_blocked_by_os`。错误诊断经任务 `result` / CLI `details` 返回。

启动操作返回 `waiting_user` 时，工程准备和部署已完成，任务 `result` / CLI 错误保留 `prepared:true`、`game_started`、`session`、`instance_id`、`reason`、`hint`、`recovery_uri` 与 `actions`。`game_started:false` 表示尚无游戏进程确认；已有进程但画中画未确认时为 `true`。客户端应结束自动等待，提示用户切回 App 或通过设置入口检查权限，不应重发 `run --new`。普通悬浮窗权限不等于厂商的“后台弹出界面”权限；不以无进程确认的超时断言系统拒绝。

App 保留原启动请求，用户返回 App 或点击“继续”后，直接使用原部署、会话与世界，不重跑准备脚本。已有游戏进程时只恢复画面。未启动请求有效十五分钟，可取消；过期、取消或授权撤销后，迟到的 Activity 请求不再初始化游戏。游戏已启动后的画面恢复不按此期限终止游戏。

`GET /v2/operations/{id}` 对有关联会话的任务附加最新 `observation`，并将已实际启动的 `waiting_user` 原任务收敛为 `completed`。`mcpy host resume --operation <id>` 查询并等待同一操作，不重新准备或创建实例。历史未知结果可据新证据返回 `reconciled=true`；未确认时仍提供诊断。准备失效后重新运行必须使用原实例 ID。

正常启动直接提交 `run`，由宿主显示游戏小窗。`launch_policy.default=direct_run`、`bring_host_to_front=false`；无需先打开 App，后台权限未知或旧失败记录不代替本次运行结果。

公开恢复链接为 `mcpy://service/start`，仅用于实际失败后由用户手动恢复，客户端不得将它作为启动前置步骤自动调用。它打开开发连接页，恢复用户此前已开启、尚未主动暂停的开发模式，并在用户返回 App 后续接已有、仍获授权的待启动请求；不从链接参数指定作品，也不批准新客户端。系统阻止唤起时仍需用户打开 App。服务不可达时，CLI 返回最后已知信息及观察时间，不猜测是 App 未运行、端口错误还是服务关闭。

`logs --source all` 聚合 `game/engine`，返回 `streams` 与分源 `cursors`；续读分别指定对应来源和 `after`。`--follow --source all` 由标准 CLI 维护独立游标。无游戏日志时明确提示尚无加载证据。截图可在已有有效 Surface 的加载阶段请求，未就绪时返回 `game_surface_not_ready` 及诊断；截图不证明世界或模组已就绪。

`command_examples` 提供标准命令示例，例如实例列表使用 `run --list`；`api_only_operations` 不代表同名 CLI 子命令。`client_commands` 中的补全库、skill 与连接管理由开发端执行，不需要在线工程宿主。


## 日志查询参数

`GET /v2/logs` 和 `logs` 工程操作均支持 `contains`（区分大小写的字面文本）、`summary`、`since_ms`、`until_ms`；能力列表为 `log_query`。`source=client/server` 按记录中的真实端侧筛选。时间为 Unix 毫秒，范围包含两端；没有时间戳的记录不参与时间筛选，数量见 `excluded_without_timestamp`。

先过滤再限制 `tail`。每次每源最多扫描 512 KiB，返回 `available`、`scanned_lines`、`matched_lines`、`scan_truncated`、`scan_start`、`cursor`、`cursor_reset`、`has_more` 与原始结构化 `entries`。不完整的末尾记录留待下次读取；未匹配记录仍推进游标。传 `after` 时返回扫描段内全部匹配记录，不再用 `tail` 丢弃增量；首次 `tail=0` 跳过历史。没有日志文件与存在但没有匹配记录分别由 `available` 区分。

`summary=true` 返回按原文、端侧及 stdout/stderr 分组的计数；引擎行首时间戳不参与消息分组，但原始输出保留。`unique_messages/summary_truncated` 表明摘要是否完整。`source=all` 分源返回 `streams` 和 `cursors`。这些字段不推断错误等级或模组健康。

## 启动时间与会话标识

`launch_options` 声明 `ack_timeout`、默认 45 秒与 5–120 秒范围。`run/restart` 的 `parameters.launch_timeout` 只影响进程/画中画确认，世界加载仍有独立的 300 秒限制；已知权限阻塞直接进入等待用户，不必耗完时限。

`status.launch_timeline` 返回有界阶段历史和 `truncated`。条目的 `observed_at` 是原始证据时间，`age_ms` 在查询时计算，`elapsed_ms` 相对于本次部署，`since_previous_ms` 表示相邻记录间隔。不同进程写入各自的时间线，未留下记录的旧阶段不推测补齐。`clientReady/serverReady` 只表示宿主调试初始化证据。

后台权限检查附带可选的 `last_observation`，包含最近后台尝试的 `outcome/code/observed_at/age_ms/same_environment`；前台恢复不会覆盖后台记录。历史成功不能把当前未知权限改为已允许。观察不包含客户端凭据或项目源码。

重启结果与其会话诊断携带 `previous_session/session_changed`，包括等待用户及恢复后的结果。后续命令必须使用新 `session`，旧会话继续保留历史日志和截图。


## 用户接手与后台设置

需要用户恢复界面或权限时，任务结果和错误携带 `needs_user_action=true`、`next_action=ask_user`、`retryable=false`、`retry_policy=after_user_confirmation`、`user_message` 与 `agent_instruction`。客户端必须停止自动启动、重新构建和循环重试，先向用户展示下一步，收到明确确认后再用原 `operation_id` 查询；已有操作附带 `resume_command`。`host_unavailable` 也遵循此交接，但不推断已提交、已准备或已运行。

`capabilities.background_execution` 提供后台耗电策略的引导，独立于后台弹出界面权限。小米可通过 `android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 与 `package:<应用ID>` 打开应用省电策略，用户自行选择“无限制”；系统不支持该入口时依次使用兼容设置页和应用详情。入口仅出现在开发 Tab，由用户点击，不自动请求或更改权限。

`system_optimization_exempt` 只表示 Android 电池优化豁免状态。无法可靠读取厂商后台策略时仍报告 `state=unknown`，不能因用户打开过设置或 Android 已豁免就声称小米不会冻结。后台耗电策略作为提示，不作为普通编辑和手动运行的硬性前置条件。
