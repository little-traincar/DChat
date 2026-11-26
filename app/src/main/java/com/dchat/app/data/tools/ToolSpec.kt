package com.dchat.app.data.tools

/**
 * 工具的执行位置（决定「工具管理」里的分组，也决定成本模型）。
 *
 * - [SERVER]：服务端工具，需联网、按官方计费；
 * - [LOCAL]：本地工具，跑在设备上，需要系统权限；
 * - [USER]：用户自建工具（App 内编写，id 以 `user.` 开头）。
 */
enum class ToolSource { SERVER, LOCAL, USER }

/**
 * 工具要求的最低权限档位，与「权限三档」一一对应：
 * 仅可查看 / 工作区内修改 / 完全权限。
 *
 * 本期只做数据层，执行管线（审批）留到后续期接入。
 */
enum class ToolMode { READ_ONLY, WORKSPACE_WRITE, FULL }

/**
 * 请求级枚举能力（例如内容安全的三档 `level`）。
 *
 * @param key 参数名（进模型请求时使用的字段名）
 * @param values 允许的取值（有序，UI 按此顺序渲染档位）
 * @param default 当前档位（迁移/用户切换后写这里；`server.safety` 为 `high`/`low`/`off`）
 */
data class ToolOption(val key: String, val values: List<String>, val default: String)

/**
 * 一次模型请求里由工具注册表统一推导出的开关（ChatScreen → DeepSeekApi）。
 *
 * 第三期起不再由 ChatScreen 自己散着传 thinking/useSearch/useReadUrl/safetyLevel/title，
 * 而是 [ToolRegistry.requestConfig] 按「工具启用 + 当前会话权限可执行」一次生成。
 */
data class ToolRequestConfig(
    val thinking: Boolean = false,
    val webSearch: Boolean = false,
    val readUrl: Boolean = false,
    val safetyLevel: String = "off",
    val title: Boolean = false,
    /** 4a-2：模型可调用的客户端工具（local.python + 用户自建工具） */
    val clientTools: List<ClientToolSpec> = emptyList(),
    /** 缓存阶段 A：system+tools 的稳定 hash（由 ToolRegistry 按确定性顺序计算） */
    val toolsEpochHash: String = ""
)

/** 发给模型的 custom tool 定义：name/description/input_schema */
data class ClientToolSpec(
    val name: String,
    val description: String,
    /** 参数 JSON Schema 字符串（来自编辑器参数表） */
    val inputSchema: String
)

/**
 * 全局工具注册表里的一条工具定义（内置 + 用户自建共用）。
 *
 * 数据层只管「是什么、开没开」；具体执行、请求拼装、UI 渲染交给后续期。
 * 存盘见 [ToolRegistry]，文件为 `filesDir/tools.json`。
 *
 * @param id 全局唯一标识，如 `server.web_search` / `local.location` / `user.py.xxx`
 * @param name 模型可见名称（全局唯一，不能和别的工具重名）
 * @param description 给模型看的一句话说明：做什么、返回什么
 * @param source 执行位置（SERVER / LOCAL / USER），对应工具页分组
 * @param paramsSchema 参数 JSON Schema（默认空对象；本期内置工具都无参数）
 * @param needs 申请的系统/网络能力：`NET` / `FS_READ` / `FS_WRITE` / `LOCATION` / `CAMERA`
 * @param option 可选：请求级枚举能力（如内容安全三档），UI 渲染成档位选择而不是开关
 * @param costNote 成本提示文案，如「需联网 · 计费」/「本机 · 免费」
 * @param minMode 该工具要求的最低权限档位
 * @param enabled 是否启用（用户改过的值优先于内置默认值）
 * @param builtIn 是否内置（用户工具为 false，不可被内置 seed 覆盖或删除）
 * @param version 定义版本号（用户工具每次编辑 +1，便于后续执行器校验）
 */
data class ToolSpec(
    val id: String,
    val name: String,
    val description: String,
    val source: ToolSource,
    val paramsSchema: String = """{"type":"object","properties":{},"additionalProperties":false}""",
    val needs: Set<String> = emptySet(),
    val option: ToolOption? = null,
    val costNote: String = "",
    val minMode: ToolMode = ToolMode.READ_ONLY,
    val enabled: Boolean = false,
    val builtIn: Boolean = true,
    val version: Int = 1,
    /**
     * 单次调用超时（毫秒），第三期按工具声明：默认 10s；
     * 文件解析 180s、OCR 120s（官方允许更长的上传/识别时间）。
     */
    val timeoutMs: Int = 10_000,
    /** 用户 Python 工具的入口函数名（第四期，默认 run） */
    val entryFunction: String = "run",
    /** 用户 Python 工具已安装到 site-packages 的包名（tool.json requirements） */
    val requirements: List<String> = emptyList(),
    /** 4b：用户工具产出 UI 的方式：`data`（自动渲染）/ `schema`（UI 树） */
    val uiMode: String = "data",
    /** 内置工具是否从工具页隐藏（OCR 已统一改用图片理解；执行代码保留） */
    val hidden: Boolean = false
)
