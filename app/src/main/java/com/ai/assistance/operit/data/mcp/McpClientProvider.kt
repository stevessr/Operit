
package com.ai.assistance.operit.data.mcp

import android.content.Context
import com.ai.assistance.operit.data.mcp.native.NativeMcpClient
import com.ai.assistance.operit.data.mcp.plugins.MCPBridgeClient

/**
 * Provides the correct IMcpClient instance based on the plugin type.
 */
object McpClientProvider {
    fun getClient(context: Context, pluginId: String): IMcpClient {
        val mcpRepository = MCPRepository(context)
        val pluginInfo = mcpRepository.getInstalledPluginInfo(pluginId)

        return if (pluginInfo?.type == "remote") {
            // Use the native client for remote services
            NativeMcpClient(context, pluginId)
        } else {
            // Use the bridge client for local services
            MCPBridgeClient(context, pluginId)
        }
    }
} 