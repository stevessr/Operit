package com.ai.assistance.operit.ui.features.packages.screens

import android.annotation.SuppressLint
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.ai.assistance.operit.ui.components.CustomScaffold
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ai.assistance.operit.data.mcp.MCPLocalServer
import com.ai.assistance.operit.data.mcp.MCPRepository
import com.ai.assistance.operit.data.mcp.plugins.MCPDeployer
import com.ai.assistance.operit.ui.features.packages.components.dialogs.MCPServerDetailsDialog
import com.ai.assistance.operit.ui.features.packages.screens.mcp.components.MCPCommandsEditDialog
import com.ai.assistance.operit.ui.features.packages.screens.mcp.components.MCPDeployConfirmDialog
import com.ai.assistance.operit.ui.features.packages.screens.mcp.components.MCPDeployProgressDialog
import com.ai.assistance.operit.ui.features.packages.screens.mcp.components.MCPInstallProgressDialog
import com.ai.assistance.operit.ui.features.packages.screens.mcp.viewmodel.MCPDeployViewModel
import com.ai.assistance.operit.ui.features.packages.screens.mcp.viewmodel.MCPViewModel
import com.ai.assistance.operit.data.mcp.plugins.MCPBridge
import com.ai.assistance.operit.data.mcp.plugins.MCPBridgeClient
import com.ai.assistance.operit.data.mcp.plugins.ServiceInfo
import com.google.gson.JsonParser
import android.util.Log
import androidx.compose.ui.res.stringResource
import com.ai.assistance.operit.R

import java.util.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.ai.assistance.operit.data.mcp.InstallResult
import com.ai.assistance.operit.data.mcp.InstallProgress
import com.ai.assistance.operit.ui.features.startup.screens.PluginLoadingState
import com.ai.assistance.operit.ui.features.startup.screens.PluginLoadingScreenWithState
import com.ai.assistance.operit.data.mcp.plugins.MCPStarter

/** MCP配置屏幕 - 极简风格界面，专注于插件快速部署 */
@SuppressLint("StateFlowValueCalledInComposition")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MCPConfigScreen() {
    val context = LocalContext.current
    val mcpLocalServer = remember { MCPLocalServer.getInstance(context) }
    val mcpRepository = remember { MCPRepository(context) }

    val scope = rememberCoroutineScope()

    // 插件加载状态管理
    val pluginLoadingState = remember { PluginLoadingState() }
    
    // 设置应用上下文
    LaunchedEffect(Unit) {
        pluginLoadingState.setAppContext(context)
        pluginLoadingState.setOnSkipCallback {
            // 跳过回调可以为空，或者添加一些逻辑
        }
    }

    // 实例化ViewModel
    val viewModel = remember {
        MCPViewModel.Factory(mcpRepository).create(MCPViewModel::class.java)
    }
    val deployViewModel = remember {
        MCPDeployViewModel.Factory(context, mcpRepository).create(MCPDeployViewModel::class.java)
    }


    // 状态收集
    val serverStatus = mcpLocalServer.serverStatus.collectAsState().value
    val installProgress by viewModel.installProgress.collectAsState()
    val installResult by viewModel.installResult.collectAsState()
    val currentInstallingPlugin by viewModel.currentServer.collectAsState()
    val installedPlugins =
            mcpRepository.installedPluginIds.collectAsState(initial = emptySet()).value
    val snackbarHostState = remember { SnackbarHostState() }

    // 计算是否有任何服务器在运行
    val isAnyServerRunning = serverStatus.values.any { it.active }

    // 部署状态
    val deploymentStatus by deployViewModel.deploymentStatus.collectAsState()
    val outputMessages by deployViewModel.outputMessages.collectAsState()
    val currentDeployingPlugin by deployViewModel.currentDeployingPlugin.collectAsState()
    val environmentVariables by deployViewModel.environmentVariables.collectAsState()
    


    // 标记是否已经执行过初始化时的自动启动
    var initialAutoStartPerformed = remember { mutableStateOf(false) }

    // 在应用启动时检查自动启动设置，而不是等待UI完全加载
    LaunchedEffect(Unit) {
        // 仅在首次加载时执行一次
        if (!initialAutoStartPerformed.value) {
            android.util.Log.d("MCPConfigScreen", "初始化 - 检查服务器状态")

            // 从桥接器同步最新的服务运行状态
            mcpRepository.syncBridgeStatus()
            // 刷新列表以确保UI更新
            mcpRepository.refreshPluginList()

            // 只记录服务器状态，不再重复启动服务器(已由 Application 中的 initAndAutoStartPlugins 控制)
            if (isAnyServerRunning) {
                android.util.Log.d("MCPConfigScreen", "MCP服务器已在运行")
            } else {
                android.util.Log.d("MCPConfigScreen", "MCP服务器未运行")
            }

            // 读取并记录已安装的MCP插件列表，但不执行任何操作
            android.util.Log.d("MCPConfigScreen", "已安装的MCP插件列表:")
            installedPlugins.forEach { pluginId ->
                try {
                    val serverStatus = mcpLocalServer.getServerStatus(pluginId)
                    val isEnabled = serverStatus?.isEnabled != false // 默认为true
                    android.util.Log.d("MCPConfigScreen", "插件ID: $pluginId, 已启用: $isEnabled")
                } catch (e: Exception) {
                    android.util.Log.e("MCPConfigScreen", "无法读取插件 $pluginId 的启用状态: ${e.message}")
                }
            }

            initialAutoStartPerformed.value = true
        }
    }

    // 界面状态
    var selectedPluginId by remember { mutableStateOf<String?>(null) }
    var pluginConfigJson by remember { mutableStateOf("") }
    var selectedPluginForDetails by remember {
        mutableStateOf<com.ai.assistance.operit.ui.features.packages.screens.mcp.model.MCPServer?>(
                null
        )
    }
    var pluginToDeploy by remember { mutableStateOf<String?>(null) }

    // 添加新的状态变量来跟踪对话框展示
    var showConfirmDialog by remember { mutableStateOf(false) }
    var showCustomCommandsDialog by remember { mutableStateOf(false) }

    // 添加导入对话框状态
    var showImportDialog by remember { mutableStateOf(false) }
    var repoUrlInput by remember { mutableStateOf("") }
    var pluginNameInput by remember { mutableStateOf("") }
    var pluginDescriptionInput by remember { mutableStateOf("") }
    var isImporting by remember { mutableStateOf(false) }
    // 新增：导入方式选择和压缩包路径
    var importTabIndex by remember { mutableStateOf(0) } // 0: 仓库导入, 1: 压缩包导入
    var zipFilePath by remember { mutableStateOf("") }
    var showFilePickerDialog by remember { mutableStateOf(false) }

    // 新增：远程服务相关状态
    var remoteEndpointInput by remember { mutableStateOf("") }
    var remoteConnectionType by remember { mutableStateOf("httpStream") }
    var remoteConnectionTypeExpanded by remember { mutableStateOf(false) }

    // 新增：远程服务编辑对话框状态
    var showRemoteEditDialog by remember { mutableStateOf(false) }
    var editingRemoteServer by remember { mutableStateOf<com.ai.assistance.operit.data.mcp.MCPServer?>(null) }


    // Effect to fetch and display tools when MCP servers start
    val isPluginLoading by pluginLoadingState.isVisible.collectAsState()
    val wasPluginLoading = remember { mutableStateOf(isPluginLoading) }
    var toolRefreshTrigger by remember { mutableStateOf(0) }

    LaunchedEffect(isPluginLoading) {
        if (wasPluginLoading.value && !isPluginLoading) {
            // Loading has just finished, trigger a refresh.
            toolRefreshTrigger++
        }
        wasPluginLoading.value = isPluginLoading
    }
    
    // 存储每个插件的工具信息
    var pluginToolsMap by remember { mutableStateOf<Map<String, List<String>>>(emptyMap()) }

    LaunchedEffect(isAnyServerRunning, installedPlugins, toolRefreshTrigger) {
        // Only run when servers are running
        if (isAnyServerRunning) {
            if (installedPlugins.isEmpty()) {
                Log.d("MCPConfigScreen", "Waiting for installed plugins list before fetching tools.")
                return@LaunchedEffect
            }

            // Give services a moment to initialize after starting
            delay(1000)

            Log.d("MCPConfigScreen", "Fetching tools for running services...")
            
            val toolsMap = mutableMapOf<String, List<String>>()

            try {
                // 遍历已安装的插件，获取每个插件的工具信息
                for (pluginId in installedPlugins) {
                    try {
                        val client = com.ai.assistance.operit.data.mcp.McpClientProvider.getClient(context, pluginId)
                        val serviceInfo = client.getServiceInfo()
                        
                        if (serviceInfo != null && serviceInfo.active && serviceInfo.toolNames.isNotEmpty()) {
                            toolsMap[pluginId] = serviceInfo.toolNames
                            Log.d("MCPConfigScreen", "Plugin $pluginId has ${serviceInfo.toolNames.size} tools: ${serviceInfo.toolNames.joinToString(", ")}")
                        } else {
                            Log.d("MCPConfigScreen", "Plugin $pluginId: no tools or not active")
                        }
                    } catch (e: Exception) {
                        Log.e("MCPConfigScreen", "Error getting tools for plugin $pluginId: ${e.message}")
                    }
                }

                // 更新工具映射
                pluginToolsMap = toolsMap
                
                if (toolsMap.isNotEmpty()) {
                    val totalTools = toolsMap.values.sumOf { it.size }
                    snackbarHostState.showSnackbar(context.getString(R.string.tools_loaded, totalTools), duration = SnackbarDuration.Short)
                    Log.i("MCPConfigScreen", "Loaded $totalTools tools from ${toolsMap.size} plugins")
                } else {
                    Log.i("MCPConfigScreen", "No tools found for any running plugins.")
                }
            } catch (e: Exception) {
                Log.e("MCPConfigScreen", "Error fetching tools", e)
                snackbarHostState.showSnackbar(context.getString(R.string.tools_load_error, e.message))
            }
        } else {
            pluginToolsMap = emptyMap() // 清空工具映射
        }
    }


    // 获取选中插件的配置
    LaunchedEffect(selectedPluginId) {
        selectedPluginId?.let { pluginConfigJson = mcpLocalServer.getPluginConfig(it) }
    }

    // 监听部署状态变化，当成功时显示提示
    LaunchedEffect(deploymentStatus) {
        if (deploymentStatus is MCPDeployer.DeploymentStatus.Success) {
            currentDeployingPlugin?.let { pluginId ->
                snackbarHostState.showSnackbar(context.getString(R.string.plugin_deployed_success, getPluginDisplayName(pluginId, mcpRepository)))
            }
        }
    }

    // 插件详情对话框
    if (selectedPluginForDetails != null) {
        val installedPath = viewModel.getInstalledPath(selectedPluginForDetails!!.id)
        MCPServerDetailsDialog(
                server = selectedPluginForDetails!!,
                onDismiss = { selectedPluginForDetails = null },
                onInstall = { /* 不需要安装功能 */},
                onUninstall = { server ->
                    viewModel.uninstallServer(server)
                    selectedPluginForDetails = null
                },
                installedPath = installedPath,
                pluginConfig = pluginConfigJson,
                onSaveConfig = {
                    selectedPluginId?.let { pluginId ->
                        scope.launch {
                        mcpLocalServer.savePluginConfig(pluginId, pluginConfigJson)
                            snackbarHostState.showSnackbar(context.getString(R.string.config_saved))
                        }
                    }
                },
                onUpdateConfig = { newConfig -> pluginConfigJson = newConfig }
        )
    }

    // 部署确认对话框 - 新增
    if (showConfirmDialog && pluginToDeploy != null) {
        com.ai.assistance.operit.ui.features.packages.screens.mcp.components.MCPDeployConfirmDialog(
                pluginName = getPluginDisplayName(pluginToDeploy!!, mcpRepository),
                onDismissRequest = {
                    showConfirmDialog = false
                    pluginToDeploy = null
                },
                onConfirm = {
                    // 在协程内部复制当前的pluginId避免外部状态变化导致空指针异常
                    val pluginId = pluginToDeploy!!
                    
                    // 确保已获取命令
                    scope.launch {
                        if (deployViewModel.generatedCommands.value.isEmpty()) {
                            deployViewModel.getDeployCommands(pluginId)
                        }
                        // 使用默认命令部署
                        deployViewModel.deployPlugin(pluginId)
                    }

                    // 重置状态
                    showConfirmDialog = false
                    pluginToDeploy = null
                },
                onCustomize = {
                    // 先关闭确认对话框，然后显示命令编辑对话框
                    showConfirmDialog = false
                    showCustomCommandsDialog = true

                    // 立即尝试获取命令，不要等到命令编辑对话框渲染后再获取
                    scope.launch {
                        val pluginId = pluginToDeploy
                        if (pluginId != null && deployViewModel.generatedCommands.value.isEmpty()) {
                            deployViewModel.getDeployCommands(pluginId)
                        }
                    }
                }
        )
    }

    // 命令编辑对话框 - 修改为只在选择自定义后显示
    if (showCustomCommandsDialog && pluginToDeploy != null) {
        // 检查命令是否已生成
        val commandsAvailable =
                deployViewModel.generatedCommands.collectAsState().value.isNotEmpty()

        // 显示命令编辑对话框，暂时移除isLoading参数
        com.ai.assistance.operit.ui.features.packages.screens.mcp.components.MCPCommandsEditDialog(
                pluginName = getPluginDisplayName(pluginToDeploy!!, mcpRepository),
                commands = deployViewModel.generatedCommands.value,
                // 注释掉isLoading参数直到MCPCommandsEditDialog.kt的修改生效
                // isLoading = !commandsAvailable,
                onDismissRequest = {
                    showCustomCommandsDialog = false
                    pluginToDeploy = null
                },
                onConfirm = { customCommands ->
                    // 在协程内部复制插件ID以避免空指针异常
                    val pluginId = pluginToDeploy!!
                    deployViewModel.deployPluginWithCommands(pluginId, customCommands)

                    // 重置状态
                    showCustomCommandsDialog = false
                    pluginToDeploy = null
                }
        )

        // 如果还没有获取命令，异步获取
        LaunchedEffect(pluginToDeploy) {
            if (!commandsAvailable) {
                deployViewModel.getDeployCommands(pluginToDeploy!!)
            }
        }
    }

    // 新增：远程服务编辑对话框
    if (showRemoteEditDialog && editingRemoteServer != null) {
        RemoteServerEditDialog(
            server = editingRemoteServer!!,
            onDismiss = {
                showRemoteEditDialog = false
                editingRemoteServer = null
            },
            onSave = { updatedServer ->
                viewModel.updateRemoteServer(updatedServer)
                showRemoteEditDialog = false
                editingRemoteServer = null
                scope.launch {
                    snackbarHostState.showSnackbar(context.getString(R.string.remote_service_updated, updatedServer.name))
                }
            }
        )
    }


    // 部署进度对话框
    if (currentDeployingPlugin != null) {
        MCPDeployProgressDialog(
                deploymentStatus = deploymentStatus,
                onDismissRequest = { deployViewModel.resetDeploymentState() },
                onRetry = {
                    currentDeployingPlugin?.let { pluginId ->
                        deployViewModel.deployPlugin(pluginId)
                    }
                },
                pluginName = currentDeployingPlugin?.let { getPluginDisplayName(it, mcpRepository) } ?: "",
                outputMessages = outputMessages,
                environmentVariables = environmentVariables,
                onEnvironmentVariablesChange = { newEnvVars ->
                    deployViewModel.setEnvironmentVariables(newEnvVars)
                }
        )
    }

    // 安装进度对话框
    if (installProgress != null && currentInstallingPlugin != null) {
        // 将值存储在本地变量中以避免智能转换问题
        val currentInstallResult = installResult
        // 判断当前是否是卸载操作
        val isUninstallOperation = 
            if (currentInstallResult is InstallResult.Success) {
                currentInstallResult.pluginPath.isEmpty()
            } else {
                false
            }
        
        MCPInstallProgressDialog(
                installProgress = installProgress,
                onDismissRequest = { viewModel.resetInstallState() },
                result = installResult,
                                        serverName = currentInstallingPlugin?.name ?: stringResource(R.string.mcp_plugin),
                // 添加操作类型参数：卸载/安装
                operationType = if (isUninstallOperation) stringResource(R.string.uninstall) else stringResource(R.string.install)
        )
    }

    // 导入插件对话框
    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text(stringResource(R.string.import_or_connect_mcp_service)) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 添加顶部导入方式选择
                    ScrollableTabRow(
                        selectedTabIndex = importTabIndex,
                        edgePadding = 0.dp,
                        divider = {}
                    ) {
                        Tab(
                            selected = importTabIndex == 0,
                            onClick = { importTabIndex = 0 },
                            text = { 
                                Text(
                                    stringResource(R.string.import_from_repo),
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1
                                ) 
                            }
                        )
                        Tab(
                            selected = importTabIndex == 1,
                            onClick = { importTabIndex = 1 },
                            text = { 
                                Text(
                                    stringResource(R.string.import_from_zip),
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1
                                ) 
                            }
                        )
                        Tab(
                            selected = importTabIndex == 2,
                            onClick = { importTabIndex = 2 },
                            text = { 
                                Text(
                                    stringResource(R.string.connect_remote_service),
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1
                                ) 
                            }
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    when (importTabIndex) {
                        0 -> {
                            // 从仓库导入
                            Text(stringResource(R.string.enter_repo_info), style = MaterialTheme.typography.bodyMedium)
                            
                            OutlinedTextField(
                                value = repoUrlInput,
                                onValueChange = { repoUrlInput = it },
                                label = { Text(stringResource(R.string.repo_link)) },
                                placeholder = { Text("https://github.com/username/repo") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                            )
                        }
                        1 -> {
                            // 从压缩包导入
                            Text(stringResource(R.string.select_mcp_plugin_zip), style = MaterialTheme.typography.bodyMedium)
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = zipFilePath,
                                    onValueChange = { /* 只读 */ },
                                    label = { Text(stringResource(R.string.plugin_zip_file)) },
                                    placeholder = { Text(stringResource(R.string.select_zip_file)) },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    readOnly = true
                                )
                                
                                IconButton(onClick = { showFilePickerDialog = true }) {
                                    Icon(Icons.Default.Folder, contentDescription = stringResource(R.string.select_file))
                                }
                            }
                        }
                        2 -> {
                            // 连接远程服务
                            Text(stringResource(R.string.enter_remote_service_info), style = MaterialTheme.typography.bodyMedium)

                            OutlinedTextField(
                                value = remoteEndpointInput,
                                onValueChange = { remoteEndpointInput = it },
                                label = { Text(stringResource(R.string.host_address)) },
                                placeholder = { Text("http://127.0.0.1:8752") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                            )
                            
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            val connectionTypes = listOf("httpStream", "sse")
                            ExposedDropdownMenuBox(
                                expanded = remoteConnectionTypeExpanded,
                                onExpandedChange = { remoteConnectionTypeExpanded = !remoteConnectionTypeExpanded },
                            ) {
                                OutlinedTextField(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .menuAnchor(),
                                    value = remoteConnectionType,
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text(stringResource(R.string.connection_type)) },
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = remoteConnectionTypeExpanded) },
                                )
                                ExposedDropdownMenu(
                                    expanded = remoteConnectionTypeExpanded,
                                    onDismissRequest = { remoteConnectionTypeExpanded = false },
                                ) {
                                    connectionTypes.forEach { selectionOption ->
                                        DropdownMenuItem(
                                            text = { Text(selectionOption) },
                                            onClick = {
                                                remoteConnectionType = selectionOption
                                                remoteConnectionTypeExpanded = false
                                            },
                                            contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    
                    Divider(modifier = Modifier.padding(vertical = 4.dp))
                    Text(stringResource(R.string.service_metadata), style = MaterialTheme.typography.titleSmall)
                    
                    OutlinedTextField(
                        value = pluginNameInput,
                        onValueChange = { pluginNameInput = it },
                        label = { Text(stringResource(R.string.plugin_name)) },
                        placeholder = { Text(stringResource(R.string.my_mcp_plugin)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    
                    OutlinedTextField(
                        value = pluginDescriptionInput,
                        onValueChange = { pluginDescriptionInput = it },
                        label = { Text(stringResource(R.string.plugin_description)) },
                        placeholder = { Text(stringResource(R.string.this_is_mcp_plugin)) },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 3
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val isRemote = importTabIndex == 2
                        val isRepoImport = importTabIndex == 0 && repoUrlInput.isNotBlank() && pluginNameInput.isNotBlank()
                        val isZipImport = importTabIndex == 1 && zipFilePath.isNotBlank() && pluginNameInput.isNotBlank()
                        val isRemoteConnect = isRemote && remoteEndpointInput.isNotBlank() && pluginNameInput.isNotBlank()

                        if (isRepoImport || isZipImport || isRemoteConnect) {
                            // 检查插件ID是否冲突
                            val proposedId = pluginNameInput.replace(" ", "_").lowercase()
                            if (mcpRepository.isPluginInstalled(proposedId)) {
                                scope.launch {
                                    snackbarHostState.showSnackbar(context.getString(R.string.plugin_already_exists, pluginNameInput))
                                }
                                return@Button
                            }

                            isImporting = true
                            // 生成一个唯一的ID，移除 "import_" 前缀
                            val importId = proposedId
                            
                            // 创建服务器对象
                            val server = com.ai.assistance.operit.data.mcp.MCPServer(
                                id = importId,
                                name = pluginNameInput,
                                description = pluginDescriptionInput,
                                logoUrl = "",
                                stars = 0,
                                category = if(isRemote) context.getString(R.string.remote_service) else context.getString(R.string.import_plugin),
                                requiresApiKey = false,
                                author = "",
                                isVerified = false,
                                isInstalled = isRemote, // 远程服务视为"已安装"
                                version = "1.0.0",
                                updatedAt = "",
                                longDescription = pluginDescriptionInput,
                                repoUrl = if (importTabIndex == 0) repoUrlInput else "",
                                type = if(isRemote) "remote" else "local",
                                endpoint = if(isRemote) remoteEndpointInput else null,
                                connectionType = if(isRemote) remoteConnectionType else "httpStream"
                            )
                            
                            if(isRemote){
                                // 对于远程服务，直接保存到仓库
                                viewModel.addRemoteServer(server)
                                scope.launch {
                                    snackbarHostState.showSnackbar(context.getString(R.string.remote_service_added, server.name))
                                }
                            } else {
                                // 本地插件走安装流程
                                val mcpServer = com.ai.assistance.operit.ui.features.packages.screens.mcp.model.MCPServer(
                                id = server.id,
                                name = server.name,
                                description = server.description,
                                logoUrl = server.logoUrl,
                                stars = server.stars,
                                category = server.category,
                                requiresApiKey = server.requiresApiKey,
                                author = server.author,
                                isVerified = server.isVerified,
                                isInstalled = server.isInstalled,
                                version = server.version,
                                updatedAt = server.updatedAt,
                                longDescription = server.longDescription,
                                    repoUrl = server.repoUrl,
                                    type = server.type,
                                    endpoint = server.endpoint,
                                    connectionType = server.connectionType
                            )
                            
                            if (importTabIndex == 0) {
                                viewModel.installServerWithObject(mcpServer)
                            } else {
                                viewModel.installServerFromZip(mcpServer, zipFilePath)
                                }
                            }
                            
                            // 清空输入并关闭对话框
                            repoUrlInput = ""
                            pluginNameInput = ""
                            pluginDescriptionInput = ""
                            zipFilePath = ""
                            remoteEndpointInput = ""
                            remoteConnectionType = "httpStream"
                            remoteConnectionTypeExpanded = false
                            showImportDialog = false
                            isImporting = false
                        } else {
                            val errorMessage = when (importTabIndex) {
                                0 -> context.getString(R.string.enter_repo_link_and_name)
                                1 -> context.getString(R.string.select_zip_and_enter_name)
                                else -> context.getString(R.string.enter_complete_remote_info)
                            }
                            scope.launch {
                                snackbarHostState.showSnackbar(errorMessage)
                            }
                        }
                    },
                    enabled = !isImporting && 
                             ((importTabIndex == 0 && repoUrlInput.isNotBlank() && pluginNameInput.isNotBlank()) ||
                              (importTabIndex == 1 && zipFilePath.isNotBlank() && pluginNameInput.isNotBlank()) ||
                              (importTabIndex == 2 && remoteEndpointInput.isNotBlank() && pluginNameInput.isNotBlank()))
                ) {
                    if (isImporting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(if(importTabIndex == 2) stringResource(R.string.connect) else stringResource(R.string.import_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { 
                    repoUrlInput = ""
                    pluginNameInput = ""
                    pluginDescriptionInput = ""
                    zipFilePath = ""
                    remoteEndpointInput = ""
                    remoteConnectionType = "httpStream"
                    remoteConnectionTypeExpanded = false
                    showImportDialog = false 
                }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
    
    // 文件选择对话框
    if (showFilePickerDialog) {
        AlertDialog(
            onDismissRequest = { showFilePickerDialog = false },
            title = { Text(stringResource(R.string.select_mcp_plugin_zip_title)) },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.use_system_file_picker))
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            // 触发系统文件选择器
                            val intent = android.content.Intent(android.content.Intent.ACTION_GET_CONTENT)
                            intent.type = "application/zip"
                            val chooser = android.content.Intent.createChooser(intent, context.getString(R.string.choose_mcp_plugin_zip))
                            
                            // 使用Activity启动选择器
                            val activity = context as? android.app.Activity
                            activity?.startActivityForResult(chooser, 1001)
                            
                            // 设置监听器接收选择结果
                            val activityResultCallback = object : androidx.activity.result.ActivityResultCallback<androidx.activity.result.ActivityResult> {
                                override fun onActivityResult(result: androidx.activity.result.ActivityResult) {
                                    if (result.resultCode == android.app.Activity.RESULT_OK) {
                                        result.data?.data?.let { uri ->
                                            // 获取文件路径
                                            val cursor = context.contentResolver.query(uri, null, null, null, null)
                                            cursor?.use {
                                                if (it.moveToFirst()) {
                                                    val displayName = it.getString(it.getColumnIndexOrThrow(android.provider.OpenableColumns.DISPLAY_NAME))
                                                    zipFilePath = displayName
                                                    
                                                    // 保存URI以便后续处理
                                                    viewModel.setSelectedZipUri(uri)
                                                }
                                            }
                                        }
                                    }
                                    showFilePickerDialog = false
                                }
                            }
                            
                            // 注册回调
                            val registry = (context as androidx.activity.ComponentActivity).activityResultRegistry
                            val launcher = registry.register("zip_picker", androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(), activityResultCallback)
                            launcher.launch(chooser)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.open_file_picker))
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showFilePickerDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    CustomScaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            floatingActionButton = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 启动插件按钮
                    FloatingActionButton(
                        onClick = {
                            pluginLoadingState.reset() // 确保每次都重置状态
                            pluginLoadingState.show()
                            scope.launch {
                                val starter = MCPStarter(context)
                                starter.startAllDeployedPlugins(pluginLoadingState)
                            }
                        },
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(56.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.start_plugin))
                    }
                    
                    // 导入按钮
                    FloatingActionButton(
                        onClick = {
                            showImportDialog = true
                        },
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(56.dp)
                    ) {
                        Icon(Icons.Default.Download, contentDescription = stringResource(R.string.import_action))
                    }
                }
            }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize()) {
            // 插件加载屏幕覆盖层
            PluginLoadingScreenWithState(
                loadingState = pluginLoadingState,
                modifier = Modifier.fillMaxSize()
            )
            // 主界面内容
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(8.dp)
            ) {
                // 状态指示器
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.mcp_management),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f)
                            )
                            
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .background(
                                            color = if (isAnyServerRunning) Color.Green else Color.Red,
                                            shape = RoundedCornerShape(4.dp)
                                        )
                                )
                                Text(
                                    text = if (isAnyServerRunning) stringResource(R.string.running) else stringResource(R.string.not_running),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                
                // 插件列表标题
                if (installedPlugins.isNotEmpty()) {
                    
                    // 插件列表
                    items(installedPlugins.toList()) { pluginId ->
                        val pluginInfo = remember(pluginId) {
                            mcpRepository.getInstalledPluginInfo(pluginId)
                        }
                        val isRemote = pluginInfo?.type == "remote"

                        // 获取插件服务器状态
                        val serverStatus = mcpLocalServer.getServerStatus(pluginId)

                        // 获取插件部署成功状态
                        val deploySuccessState = remember(pluginId) {
                            mutableStateOf(serverStatus?.deploySuccess == true)
                        }

                        // 获取插件启用状态
                        val pluginEnabledState = remember(pluginId) {
                            mutableStateOf(serverStatus?.isEnabled != false) // 默认为true
                        }

                        // 获取插件运行状态
                        val pluginRunningState = remember(pluginId) {
                            mutableStateOf(serverStatus?.active == true)
                        }

                        // 获取插件最后部署时间
                        val lastDeployTimeState = remember(pluginId) {
                            mutableStateOf(serverStatus?.lastDeployTime ?: 0L)
                        }
                        
                        // 监听服务器状态变化
                        LaunchedEffect(pluginId) {
                            mcpLocalServer.serverStatus.collect { statusMap ->
                                val status = statusMap[pluginId]
                                deploySuccessState.value = status?.deploySuccess == true
                                pluginEnabledState.value = status?.isEnabled != false
                                pluginRunningState.value = status?.active == true
                                lastDeployTimeState.value = status?.lastDeployTime ?: 0L
                            }
                        }

                        PluginListItem(
                                pluginId = pluginId,
                                displayName = getPluginDisplayName(pluginId, mcpRepository),
                                isOfficial = pluginId.startsWith("official_"),
                                isRemote = isRemote, // 传递插件类型
                                toolNames = pluginToolsMap[pluginId] ?: emptyList(), // 传递工具信息
                                onClick = {
                                    selectedPluginId = pluginId
                                    pluginConfigJson = mcpLocalServer.getPluginConfig(pluginId)
                                    selectedPluginForDetails = getPluginAsServer(pluginId, mcpRepository, context)
                                },
                                onDeploy = {
                                    pluginToDeploy = pluginId
                                    showConfirmDialog = true // 显示确认对话框而不是直接进入命令编辑
                                },
                                onEdit = {
                                    // 设置要编辑的服务器并显示对话框
                                    val serverToEdit = getPluginAsServer(pluginId, mcpRepository,context)
                                    if(serverToEdit != null){
                                        editingRemoteServer = com.ai.assistance.operit.data.mcp.MCPServer(
                                            id = serverToEdit.id,
                                            name = serverToEdit.name,
                                            description = serverToEdit.description,
                                            logoUrl = serverToEdit.logoUrl,
                                            stars = serverToEdit.stars,
                                            category = serverToEdit.category,
                                            requiresApiKey = serverToEdit.requiresApiKey,
                                            author = serverToEdit.author,
                                            isVerified = serverToEdit.isVerified,
                                            isInstalled = serverToEdit.isInstalled,
                                            version = serverToEdit.version,
                                            updatedAt = serverToEdit.updatedAt,
                                            longDescription = serverToEdit.longDescription,
                                            repoUrl = serverToEdit.repoUrl,
                                            type = serverToEdit.type,
                                            endpoint = serverToEdit.endpoint,
                                            connectionType = serverToEdit.connectionType
                                        )
                                        showRemoteEditDialog = true
                                    }
                                },
                                isEnabled = pluginEnabledState.value,
                                onEnabledChange = { isChecked ->
                                    scope.launch {
                                        mcpLocalServer.updateServerStatus(pluginId, isEnabled = isChecked)
                                    }
                                },
                                isRunning = pluginRunningState.value,
                                isDeployed = deploySuccessState.value,
                                lastDeployTime = lastDeployTimeState.value
                        )
                        Divider(modifier = Modifier.padding(horizontal = 4.dp))
                    }
                } else {
                    // 无插件提示
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            )
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Extension,
                                        contentDescription = null,
                                        modifier = Modifier.size(48.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        stringResource(R.string.no_plugins),
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        stringResource(R.string.use_import_function_to_add),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// 从插件ID中提取显示名称
private fun getPluginDisplayName(pluginId: String, mcpRepository: MCPRepository): String {
    val pluginInfo = mcpRepository.getInstalledPluginInfo(pluginId)
    val originalName = pluginInfo?.name

    if (originalName != null && originalName.isNotBlank()) {
        return originalName
    }

    return when {
        pluginId.contains("/") -> pluginId.split("/").last().replace("-", " ").replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
        pluginId.startsWith("official_") ->
            pluginId.removePrefix("official_").replace("_", " ").replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
        else -> pluginId
    }
}

// 获取插件元数据
private fun getPluginAsServer(
    pluginId: String,
    mcpRepository: MCPRepository,
    context: Context
): com.ai.assistance.operit.ui.features.packages.screens.mcp.model.MCPServer? {
    val pluginInfo = mcpRepository.getInstalledPluginInfo(pluginId)

    // 尝试从内存中的服务器列表查找
    val existingServer = mcpRepository.mcpServers.value.find { it.id == pluginId }

    // 如果在列表中找到，并且类型是远程，直接使用
    if (existingServer != null && existingServer.type == "remote") {
        return com.ai.assistance.operit.ui.features.packages.screens.mcp.model.MCPServer(
            id = existingServer.id,
            name = existingServer.name,
            description = existingServer.description,
            logoUrl = existingServer.logoUrl,
            stars = existingServer.stars,
            category = existingServer.category,
            requiresApiKey = existingServer.requiresApiKey,
            author = existingServer.author,
            isVerified = existingServer.isVerified,
            isInstalled = true,
            version = existingServer.version,
            updatedAt = existingServer.updatedAt,
            longDescription = existingServer.longDescription,
            repoUrl = existingServer.repoUrl,
            type = existingServer.type,
            endpoint = existingServer.endpoint,
            connectionType = existingServer.connectionType
        )
    }

    val displayName = getPluginDisplayName(pluginId, mcpRepository)

    return com.ai.assistance.operit.ui.features.packages.screens.mcp.model.MCPServer(
        id = pluginId,
        name = displayName,
        description = pluginInfo?.description ?: context.getString(R.string.local_installed_plugin),
        logoUrl = "",
        stars = 0,
        category = context.getString(R.string.installed_plugins),
        requiresApiKey = false,
        author = pluginInfo?.author ?: context.getString(R.string.local_installation),
        isVerified = false,
        isInstalled = true,
        version = pluginInfo?.version ?: context.getString(R.string.local_version),
        updatedAt = "",
        longDescription = pluginInfo?.longDescription
            ?: (pluginInfo?.description ?: context.getString(R.string.local_installed_plugin)),
        repoUrl = pluginInfo?.repoUrl ?: "",
        type = pluginInfo?.type ?: "local",
        endpoint = pluginInfo?.endpoint,
        connectionType = pluginInfo?.connectionType
    )
}

@Composable
private fun PluginListItem(
    pluginId: String,
    displayName: String,
    isOfficial: Boolean,
    isRemote: Boolean,
    toolNames: List<String>,
    onClick: () -> Unit,
    onDeploy: () -> Unit,
    onEdit: () -> Unit,
    isEnabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    isRunning: Boolean = false,
    isDeployed: Boolean = false,
    lastDeployTime: Long = 0L
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            // 主要信息行：图标 + 名称 + 开关
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 紧凑的插件图标
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Extension,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(16.dp)
                    )

                    // 运行状态指示点
                    if (isRunning) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .align(Alignment.TopEnd)
                                .offset(x = 2.dp, y = (-2).dp)
                                .background(
                                    color = Color(0xFF4CAF50),
                                    shape = RoundedCornerShape(3.dp)
                                )
                        )
                    }
                }

                Spacer(modifier = Modifier.width(10.dp))

                // 插件名称和状态
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = displayName,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        
                        // 状态标签
                        if (isOfficial) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                            ) {
                                Text(
                                    text = stringResource(R.string.official),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    fontSize = 9.sp
                                )
                            }
                        }
                        
                        if (isRemote) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.1f)
                            ) {
                                Text(
                                    text = stringResource(R.string.remote),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    fontSize = 9.sp
                                )
                            }
                        }
                        
                        if (isDeployed && !isRemote) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.1f)
                            ) {
                                Text(
                                    text = stringResource(R.string.deployed),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.tertiary,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    fontSize = 9.sp
                                )
                            }
                        }
                    }
                    
                    // 部署时间（如果有）
                    if (lastDeployTime > 0 && !isRemote) {
                        val dateStr = java.text.SimpleDateFormat(
                            "MM-dd HH:mm",
                            java.util.Locale.getDefault()
                        ).format(java.util.Date(lastDeployTime))
                        Text(
                            text = stringResource(R.string.deploy_date, dateStr),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            fontSize = 10.sp
                        )
                    }
                }

                // 紧凑的开关
                Switch(
                    checked = isEnabled,
                    onCheckedChange = onEnabledChange,
                    modifier = Modifier.scale(0.8f),
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.primary,
                        checkedTrackColor = MaterialTheme.colorScheme.primaryContainer,
                        uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                        uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                )
            }

            // 工具标签区域（如果有）
            if (toolNames.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(toolNames.take(5)) { toolName ->
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
                        ) {
                            Text(
                                text = toolName,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                fontSize = 9.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    
                    // 如果工具数量超过5个，显示"更多"
                    if (toolNames.size > 5) {
                        item {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
                            ) {
                                Text(
                                    text = "+${toolNames.size - 5}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    fontSize = 9.sp
                                )
                            }
                        }
                    }
                }
            }

            // 操作按钮区域 
            if (!isRemote || isRemote) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 主要操作按钮
                    if (!isRemote) {
                        OutlinedButton(
                            onClick = onDeploy,
                            modifier = Modifier.weight(1f).height(32.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = if (isDeployed) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Text(
                                text = if (isDeployed) stringResource(R.string.redeploy) else stringResource(R.string.deploy),
                                style = MaterialTheme.typography.labelMedium,
                                fontSize = 12.sp
                            )
                        }
                    }
                    
                    // 编辑按钮
                    OutlinedButton(
                        onClick = onEdit,
                        modifier = Modifier.weight(1f).height(32.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.edit),
                            style = MaterialTheme.typography.labelMedium,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteServerEditDialog(
    server: com.ai.assistance.operit.data.mcp.MCPServer,
    onDismiss: () -> Unit,
    onSave: (com.ai.assistance.operit.data.mcp.MCPServer) -> Unit
) {
    var name by remember { mutableStateOf(server.name) }
    var description by remember { mutableStateOf(server.description) }
    var author by remember { mutableStateOf(server.author) }
    var endpoint by remember { mutableStateOf(server.endpoint ?: "") }
    var connectionType by remember { mutableStateOf(server.connectionType ?: "httpStream") }
    val connectionTypes = listOf("httpStream", "sse")
    var expanded by remember { mutableStateOf(false) }
    val isRemote = server.type == "remote"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if(isRemote) stringResource(R.string.edit_remote_service) else stringResource(R.string.edit_plugin_info)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.name)) },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.description)) },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = author,
                    onValueChange = { author = it },
                    label = { Text(stringResource(R.string.author)) },
                    modifier = Modifier.fillMaxWidth()
                )
                if(isRemote) {
                    OutlinedTextField(
                        value = endpoint,
                        onValueChange = { endpoint = it },
                        label = { Text(stringResource(R.string.host_address)) },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                    )

                    ExposedDropdownMenuBox(
                        expanded = expanded,
                        onExpandedChange = { expanded = !expanded },
                    ) {
                        OutlinedTextField(
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(),
                            value = connectionType,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(R.string.connection_type)) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        )
                        ExposedDropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false },
                        ) {
                            connectionTypes.forEach { selectionOption ->
                                DropdownMenuItem(
                                    text = { Text(selectionOption) },
                                    onClick = {
                                        connectionType = selectionOption
                                        expanded = false
                                    },
                                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val updatedServer = server.copy(
                        name = name,
                        description = description,
                        author = author,
                        endpoint = if(isRemote) endpoint else server.endpoint,
                        connectionType = if(isRemote) connectionType else server.connectionType
                    )
                    onSave(updatedServer)
                },
                enabled = name.isNotBlank() && if(isRemote) endpoint.isNotBlank() else true
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
