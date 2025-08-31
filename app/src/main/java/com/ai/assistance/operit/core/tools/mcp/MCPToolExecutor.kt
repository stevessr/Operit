package com.ai.assistance.operit.core.tools.mcp

import android.content.Context
import android.util.Log
import com.ai.assistance.operit.core.tools.StringResultData
import com.ai.assistance.operit.core.tools.ToolExecutor
import com.ai.assistance.operit.data.mcp.IMcpClient
import com.ai.assistance.operit.data.mcp.McpClientProvider
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolResult
import com.ai.assistance.operit.data.model.ToolValidationResult
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject

/**
 * MCP工具执行器
 *
 * 处理MCP工具的调用，类似于已有的PackageToolExecutor
 */
class MCPToolExecutor(private val context: Context, private val mcpManager: MCPManager) :
        ToolExecutor {
    companion object {
        private const val TAG = "MCPToolExecutor"
    }

    override fun invoke(tool: AITool): ToolResult {
        // 从工具名称中提取服务器名称和工具名称
        // 格式：服务器名称:工具名称
        val toolNameParts = tool.name.split(":")
        if (toolNameParts.size < 2) {
            return ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = "无效的MCP工具名称格式，应为 '服务器名称:工具名称'"
            )
        }

        val serverName = toolNameParts[0]
        val actualToolName = toolNameParts.subList(1, toolNameParts.size).joinToString(":")

        // 获取MCP客户端
        val mcpClient = mcpManager.getOrCreateClient(serverName)
        if (mcpClient == null) {
            return ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = "无法连接到MCP服务器: $serverName"
            )
        }

        Log.d(TAG, "准备调用MCP工具: $serverName:$actualToolName")

        // 将AITool参数转换为Map
        val parameters = tool.parameters.associate { it.name to it.value }

        // 获取工具参数类型信息 (如果可用)
        val toolInfo = getToolInfo(serverName, actualToolName)

        // 自动类型转换处理
        val convertedParameters = convertParameterTypes(parameters, toolInfo)

                        // 调用MCP工具 - 使用异步版本转同步
        val result =
                try {
                    // 调用工具
                    val jsonResponse = kotlinx.coroutines.runBlocking { 
                        mcpClient.callTool(actualToolName, JSONObject(convertedParameters))
                    }

                    if (jsonResponse != null) {
                        Log.d(TAG, "MCP工具调用成功: $serverName:$actualToolName")
                        ToolResult(
                                toolName = tool.name,
                                success = true,
                                result = StringResultData(jsonResponse.toString()),
                                error = null
                        )
                    } else {
                        // 从原始响应中提取错误信息
                        var errorMessage = "工具调用失败"

                        // 检查是否有详细错误可以提取
                        try {
                            // 尝试从客户端内部获取错误响应
                            val errorMethod =
                                    mcpClient.javaClass.getDeclaredMethod("getLastErrorResponse")
                            errorMethod.isAccessible = true
                            val errorResponse = errorMethod.invoke(mcpClient) as? JSONObject

                            if (errorResponse != null) {
                                val error = errorResponse.optJSONObject("error")
                                if (error != null) {
                                    errorMessage = error.optString("message", errorMessage)
                                }
                            }
                        } catch (e: Exception) {
                            // 如果无法提取错误，使用默认错误消息
                            errorMessage = "工具调用失败: $serverName:$actualToolName"
                        }

                        Log.w(TAG, "MCP工具调用失败: $serverName:$actualToolName - $errorMessage")
                        ToolResult(
                                toolName = tool.name,
                                success = false,
                                result = StringResultData(""),
                                error = errorMessage
                        )
                    }
                } catch (e: Exception) {
                    val errorMessage = "调用工具时发生异常: ${e.message}"
                    Log.e(TAG, "调用MCP工具时发生异常: $errorMessage", e)
                    ToolResult(
                            toolName = tool.name,
                            success = false,
                            result = StringResultData(""),
                            error = errorMessage
                    )
                }

        return result
    }

    /** 尝试获取工具的参数类型信息 */
    private fun getToolInfo(serverName: String, toolName: String): JSONObject? {
        try {
            val client = mcpManager.getOrCreateClient(serverName) ?: return null
            val tools = kotlinx.coroutines.runBlocking { client.getTools() }

            return tools.find { it.optString("name") == toolName }
        } catch (e: Exception) {
            Log.w(TAG, "获取工具信息失败: ${e.message}")
            return null
        }
    }

    /**
     * 自动转换参数类型
     *
     * 将字符串参数转换为适当的数字类型
     */
    private fun convertParameterTypes(
            parameters: Map<String, Any>,
            toolInfo: JSONObject?
    ): Map<String, Any> {
        val result = mutableMapOf<String, Any>()

        parameters.forEach { (name, value) ->
            // 默认使用原始值
            var convertedValue: Any = value

            // 尝试根据工具信息进行类型转换
            if (value is String) {
                // 尝试从工具定义中获取参数类型
                val expectedType =
                        toolInfo?.optJSONArray("parameters")?.let { params ->
                            for (i in 0 until params.length()) {
                                val param = params.optJSONObject(i)
                                if (param?.optString("name") == name) {
                                    return@let param.optString("type")
                                }
                            }
                            null
                        }

                // 即使没有工具定义，也尝试智能类型转换
                convertedValue =
                        when {
                            // 如果工具参数明确要求数字类型或字符串看起来是数字
                            expectedType == "number" ||
                                    value.matches(Regex("-?\\d+(\\.\\d+)?")) -> {
                                try {
                                    if (value.contains(".")) value.toDouble() else value.toLong()
                                } catch (e: Exception) {
                                    Log.d(TAG, "数字转换失败，使用原始字符串: $value")
                                    value
                                }
                            }
                            expectedType == "boolean" ||
                                    value.lowercase() == "true" ||
                                    value.lowercase() == "false" -> {
                                value.lowercase() == "true"
                            }
                            else -> value
                        }

                if (convertedValue != value) {
                    Log.d(
                            TAG,
                            "参数 $name 从 ${value::class.java.simpleName} 转换为 ${convertedValue::class.java.simpleName}: $value -> $convertedValue"
                    )
                }
            }

            result[name] = convertedValue
        }

        return result
    }

    override fun validateParameters(tool: AITool): ToolValidationResult {
        // 验证工具名称格式
        val toolNameParts = tool.name.split(":")
        if (toolNameParts.size < 2) {
            return ToolValidationResult(
                    valid = false,
                    errorMessage = "无效的MCP工具名称格式，应为 '服务器名称:工具名称'"
            )
        }

        // 这里可以添加更多验证逻辑，但目前简单返回成功
        return ToolValidationResult(valid = true)
    }
}

/**
 * MCP管理器
 *
 * 管理MCP客户端的创建和缓存 注意：此版本使用MCPBridgeClient作为底层客户端，替代了原有的MCPClient
 */
class MCPManager(private val context: Context) {
    companion object {
        private const val TAG = "MCPManager"

        @Volatile private var INSTANCE: MCPManager? = null

        fun getInstance(context: Context): MCPManager {
            return INSTANCE
                    ?: synchronized(this) {
                        INSTANCE ?: MCPManager(context.applicationContext).also { INSTANCE = it }
                    }
        }
    }

    // 缓存已创建的MCP客户端，避免重复创建
    private val clientCache = ConcurrentHashMap<String, IMcpClient>()

    // 缓存服务器配置
    private val serverConfigCache = ConcurrentHashMap<String, MCPServerConfig>()

    /**
     * 检查服务器是否已注册
     *
     * @param serverName 服务器名称
     * @return 如果服务器已注册则返回true
     */
    fun isServerRegistered(serverName: String): Boolean {
        return serverConfigCache.containsKey(serverName)
    }

    /**
     * 获取所有已注册的服务器配置
     *
     * @return 服务器名称到服务器配置的映射
     */
    fun getRegisteredServers(): Map<String, MCPServerConfig> {
        return serverConfigCache.toMap()
    }

    /**
     * 获取或创建MCP客户端
     *
     * @param serverName 服务器名称
     * @return MCP客户端，如果服务器不存在或无法连接则返回null
     */
    fun getOrCreateClient(serverName: String): IMcpClient? {
        // 检查缓存中是否已有客户端
        val cachedClient = clientCache[serverName]
        if (cachedClient != null) {
            // 检查客户端连接状态 - 只做轻量检查，不要过早断开
            if (cachedClient.isConnected()) {
                Log.d(TAG, "使用已缓存的客户端: $serverName")
                return cachedClient
            } else {
                // 尝试重新连接现有客户端
                Log.d(TAG, "尝试重新连接缓存的客户端: $serverName")
                val reconnected = kotlinx.coroutines.runBlocking { cachedClient.connect() }
                if (reconnected) {
                    Log.d(TAG, "成功重新连接到服务: $serverName")
                    return cachedClient
                }
                // 客户端不再可用，从缓存移除
                Log.w(TAG, "无法重新连接到服务: $serverName，将创建新的连接")
                clientCache.remove(serverName)
            }
        }

        // 获取服务器配置
        val serverConfig = serverConfigCache[serverName] ?: return null

        try {
            // 使用McpClientProvider创建客户端
            val client = McpClientProvider.getClient(context, serverName)

            // 尝试连接 - 带详细日志
            Log.d(TAG, "正在创建新的连接到服务: $serverName")
            val connectResult = kotlinx.coroutines.runBlocking { client.connect() }

            if (connectResult) {
                // 连接成功，在会话期间保持此连接
                Log.d(TAG, "成功连接到服务: $serverName，将在会话期间保持连接")
                clientCache[serverName] = client
                return client
            } else {
                Log.w(TAG, "无法连接到服务: $serverName")
            }
        } catch (e: Exception) {
            Log.e(TAG, "创建MCP客户端时出错: ${e.message}", e)
        }

        return null
    }

    /**
     * 注册MCP服务器配置
     *
     * @param serverName 服务器名称
     * @param serverConfig 服务器配置
     */
    fun registerServer(serverName: String, serverConfig: MCPServerConfig) {
        serverConfigCache[serverName] = serverConfig

        // 如果已有缓存的客户端，需要更新或移除
        if (clientCache.containsKey(serverName)) {
            // 移除旧客户端，下次需要时会重新创建
            val oldClient = clientCache.remove(serverName)
            oldClient?.disconnect()
        }
    }

    /**
     * 注册MCP服务器（简化版）
     *
     * @param serverName 服务器名称
     * @param endpoint 服务器端点URL
     * @param description 服务器描述
     */
    fun registerServer(serverName: String, endpoint: String, description: String = "") {
        val serverConfig =
                MCPServerConfig(
                        name = serverName,
                        endpoint = endpoint,
                        description = description,
                        capabilities = listOf("tools"),
                        extraData = emptyMap()
                )
        registerServer(serverName, serverConfig)
    }

    /** 关闭所有MCP客户端连接 */
    fun shutdown() {
        clientCache.values.forEach { it.disconnect() }
        clientCache.clear()
    }
}
