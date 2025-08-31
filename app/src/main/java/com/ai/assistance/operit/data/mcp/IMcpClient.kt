
package com.ai.assistance.operit.data.mcp

import org.json.JSONObject

/**
 * A unified interface for interacting with MCP services, whether they are local (via bridge) or remote (native).
 */
interface IMcpClient {
    val serviceName: String
    suspend fun connect(): Boolean
    fun isConnected(): Boolean
    suspend fun ping(): Boolean
    suspend fun callTool(method: String, params: JSONObject): JSONObject?
    suspend fun getTools(): List<JSONObject>
    suspend fun getToolNames(): List<String>
    suspend fun getServiceInfo(): com.ai.assistance.operit.data.mcp.plugins.ServiceInfo?
    fun disconnect()
} 