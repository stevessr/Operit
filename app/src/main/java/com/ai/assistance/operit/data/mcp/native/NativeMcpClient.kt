package com.ai.assistance.operit.data.mcp.native

import android.content.Context
import android.util.Log
import com.ai.assistance.operit.data.mcp.IMcpClient
import com.ai.assistance.operit.data.mcp.MCPRepository
import io.modelcontextprotocol.client.McpAsyncClient
import io.modelcontextprotocol.client.McpClient
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport
import io.modelcontextprotocol.spec.McpSchema
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages native Kotlin MCP clients for remote services.
 */
object NativeMcpClientManager {
    private val clients = ConcurrentHashMap<String, McpAsyncClient>()

    fun getOrCreateClient(name: String, endpoint: String): McpAsyncClient {
        return clients.getOrPut(name) {
            Log.d("NativeMcpClientManager", "Creating new native MCP client for '$name' at $endpoint")
            // The SDK's HttpClientSseClientTransport works for both SSE and Streamable HTTP
            val transport = HttpClientSseClientTransport(endpoint)
            val client = McpClient.async(transport).build()
            client.initialize().subscribe() // Initialize the connection asynchronously
            client
        }
    }

    fun getClient(name: String): McpAsyncClient? {
        return clients[name]
    }

    fun removeClient(name: String) {
        clients.remove(name)?.closeGracefully()?.subscribe()
        Log.d("NativeMcpClientManager", "Removed native MCP client for '$name'")
    }

    fun shutdownAll() {
        clients.values.forEach { it.closeGracefully().subscribe() }
        clients.clear()
        Log.d("NativeMcpClientManager", "All native MCP clients shut down.")
    }
}

/**
 * Native Kotlin implementation of [IMcpClient] for remote services.
 */
class NativeMcpClient(context: Context, override val serviceName: String) : IMcpClient {

    private val mcpRepository = MCPRepository(context)
    private val isConnectedState = AtomicBoolean(false)

    private val sdkClient: McpAsyncClient? by lazy {
        val pluginInfo = mcpRepository.getInstalledPluginInfo(serviceName)
        if (pluginInfo?.type == "remote" && pluginInfo.endpoint != null) {
            NativeMcpClientManager.getOrCreateClient(serviceName, pluginInfo.endpoint)
        } else {
            Log.e("NativeMcpClient", "Cannot create client for '$serviceName': not a remote plugin or endpoint is null.")
            null
        }
    }

    override suspend fun connect(): Boolean {
        if (sdkClient == null) return false
        return ping()
    }

    override fun isConnected(): Boolean = isConnectedState.get()

    override suspend fun ping(): Boolean {
        if (sdkClient == null) return false
        return withTimeoutOrNull(5000) {
            try {
                // MCP doesn't have a client-side ping. Listing tools is a good health check.
                val result = sdkClient?.listTools()?.block()
                val success = result != null
                isConnectedState.set(success)
                success
            } catch (e: Exception) {
                isConnectedState.set(false)
                Log.e("NativeMcpClient", "Ping failed for service '$serviceName': ${e.message}")
                false
            }
        } ?: false
    }

    override suspend fun callTool(method: String, params: JSONObject): JSONObject? {
        if (sdkClient == null || !connect()) {
            Log.e("NativeMcpClient", "Cannot call tool: client for '$serviceName' is not connected.")
            return null
        }
        return withTimeoutOrNull(30000) {
            try {
                val paramsMap = params.keys().asSequence().associateWith { key -> params.get(key) }
                val request = McpSchema.CallToolRequest(method, paramsMap)
                val result = sdkClient?.callTool(request)?.block()
                
                if (result != null) {
                    // Convert the result back to JSONObject
                    JSONObject().put("success", true).put("result", JSONObject(result.toString()))
                } else {
                    null
                }
            } catch (e: Exception) {
                Log.e("NativeMcpClient", "Tool call failed for '$serviceName': ${e.message}", e)
                null
            }
        }
    }

    override suspend fun getTools(): List<JSONObject> {
        if (sdkClient == null || !connect()) {
            Log.e("NativeMcpClient", "Cannot get tools: client for '$serviceName' is not connected.")
            return emptyList()
        }
        return withTimeoutOrNull(10000) {
            try {
                val result = sdkClient?.listTools()?.block()
                result?.tools?.map { tool ->
                    JSONObject().apply {
                        put("name", tool.name)
                        put("description", tool.description ?: "")
                        put("arguments", JSONObject(tool.inputSchema as Map<String, Any>? ?: emptyMap<String, Any>()))
                    }
                } ?: emptyList<JSONObject>()
            } catch (e: Exception) {
                Log.e("NativeMcpClient", "Get tools failed for '$serviceName': ${e.message}")
                emptyList<JSONObject>()
            }
        } ?: emptyList<JSONObject>()
    }

    override suspend fun getToolNames(): List<String> {
        return getTools().mapNotNull { it.optString("name") }
    }

    override suspend fun getServiceInfo(): com.ai.assistance.operit.data.mcp.plugins.ServiceInfo? {
         if (sdkClient == null) return null
        val isUp = ping()
        val tools = if (isUp) getTools() else emptyList()
        return com.ai.assistance.operit.data.mcp.plugins.ServiceInfo(
            name = serviceName,
            active = isUp,
            ready = isUp,
            toolCount = tools.size,
            toolNames = tools.mapNotNull { it.optString("name") }
        )
    }

    override fun disconnect() {
        NativeMcpClientManager.removeClient(serviceName)
        isConnectedState.set(false)
    }
} 