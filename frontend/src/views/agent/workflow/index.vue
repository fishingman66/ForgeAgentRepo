<template>
  <div class="workflow-editor">
    <!-- 顶部工具栏 -->
    <div class="workflow-toolbar">
      <div class="toolbar-left">
        <el-button icon="Back" @click="goBack">返回</el-button>
        <span class="agent-name">{{ agentName || '加载中...' }}</span>
        <el-tag type="info" size="small" effect="plain">Agent 编排画布</el-tag>
        <el-tag v-if="replayRun" type="success" size="small">回放 #{{ String(replayRun.id).slice(-6) }} {{ traceStatusText(replayRun.status) }}</el-tag>
        <el-tag v-else-if="previewVersionNo !== null" type="info" size="small">版本预览 v{{ previewVersionNo }}</el-tag>
        <el-tag v-else-if="publishedReadonly" type="warning" size="small">草稿 · 已发布 v{{ agentVersion }}</el-tag>
        <el-tag v-else type="info" size="small">草稿</el-tag>
      </div>
      <div class="toolbar-right">
        <el-button icon="ZoomOut" circle @click="zoomOut" />
        <span class="zoom-text">{{ Math.round(zoom * 100) }}%</span>
        <el-button icon="ZoomIn" circle @click="zoomIn" />
        <el-button icon="FullScreen" @click="fitView">适应屏幕</el-button>
        <el-button icon="RefreshLeft" :disabled="!canUndo" @click="undo" />
        <el-button icon="RefreshRight" :disabled="!canRedo" @click="redo" />
        <el-button icon="Clock" @click="openVersionDrawer">版本</el-button>
        <el-button icon="List" @click="openRunDrawer">运行记录</el-button>
        <el-button v-if="previewVersionNo !== null" type="warning" icon="RefreshLeft" @click="handleRollbackCurrent">回滚此版本</el-button>
        <el-button v-if="previewVersionNo !== null || replayRun" icon="Close" @click="exitSpecialMode">退出{{ replayRun ? '回放' : '预览' }}</el-button>
        <el-button type="warning" icon="CircleCheck" @click="handleValidate">静态校验</el-button>
        <el-button type="success" icon="VideoPlay" @click="handleTrialRun">试运行</el-button>
        <el-button v-if="previewVersionNo === null && !replayRun" type="primary" icon="Document" :loading="saving" @click="handleSave">保存</el-button>
      </div>
    </div>

    <!-- 已发布 Agent 草稿编辑提示(P1/B2 修订) -->
    <el-alert
      v-if="publishedReadonly && previewVersionNo === null && !replayRun"
      type="warning"
      :closable="false"
      show-icon
      title="当前为已发布 Agent,正在编辑工作流草稿:保存后需重新发布才会生效;提示词/模型绑定等基础配置仍需先停用再修改"
      style="margin: 8px 16px 0"
    />

    <div class="workflow-body">
      <!-- 左栏:节点库 -->
      <div class="node-palette">
        <div class="palette-title">节点库</div>
        <div v-for="group in nodeGroups" :key="group.title" class="palette-group">
          <div class="group-title">{{ group.title }}</div>
          <div
            v-for="item in group.items"
            :key="item.type"
            class="palette-item"
            :class="{ disabled: readonly || ((item.type === 'start' || item.type === 'end') && nodes.some(n => n.type === item.type)) }"
            draggable="true"
            @dragstart="onDragStart($event, item)"
            @click="!readonly && addNode(item.type)"
          >
            <el-icon :size="16" class="node-icon" :color="item.color"><component :is="item.icon" /></el-icon>
            <div class="node-info">
              <div class="node-name">{{ item.name }}</div>
              <div class="node-desc">{{ item.desc }}</div>
            </div>
          </div>
        </div>
      </div>

      <!-- 中栏:画布 -->
      <div class="canvas-wrapper" ref="canvasWrapperRef" @wheel="onWheel">
        <div
          class="canvas"
          ref="canvasRef"
          :style="{ transform: `translate(${pan.x}px, ${pan.y}px) scale(${zoom})` }"
          @mousedown="onCanvasMouseDown"
          @mousemove="onCanvasMouseMove"
          @mouseup="onCanvasMouseUp"
          @mouseleave="onCanvasMouseUp"
          @dragover.prevent
          @drop="onDrop"
        >
          <div class="grid-bg"></div>
          <!-- 连线层 -->
          <svg class="edges-layer">
            <path
              v-for="edge in edges"
              :key="edge.id"
              :d="getEdgePath(edge)"
              :class="['edge-path', { selected: selectedEdgeId === edge.id }]"
              @click.stop="selectEdge(edge.id)"
            />
            <!-- W6:条件分支出边标注分支名 -->
            <text
              v-for="edge in edges.filter(e => e.branchName)"
              :key="edge.id + '-label'"
              class="edge-label"
              :x="edgeLabelPos(edge).x"
              :y="edgeLabelPos(edge).y"
            >{{ edge.branchName }}</text>
            <path
              v-if="tempEdge"
              :d="getTempEdgePath()"
              class="edge-path temp"
            />
          </svg>
          <!-- 节点层 -->
          <div
            v-for="node in nodes"
            :key="node.id"
            class="workflow-node"
            :class="[node.type, { selected: selectedNodeId === node.id, readonly }, replayNodeClass(node.id)]"
            :style="{ left: node.x + 'px', top: node.y + 'px' }"
            @mousedown.stop="onNodeMouseDown($event, node)"
            @click.stop="selectNode(node.id)"
          >
            <div class="node-header" :style="{ backgroundColor: getNodeColor(node.type) }">
              <el-icon :size="14" color="#fff"><component :is="getNodeIcon(node.type)" /></el-icon>
              <span class="node-title">{{ node.name }}</span>
              <el-icon v-if="!readonly" class="node-close" :size="12" @click.stop="removeNode(node.id)"><Close /></el-icon>
            </div>
            <div class="node-body">
              <div class="node-type-label">{{ getNodeTypeLabel(node.type) }}</div>
            </div>
            <!-- 输入端口 -->
            <div
              v-if="node.type !== 'start'"
              class="port input-port"
              @mousedown.stop="onPortMouseDown($event, node, 'input')"
              @mouseup.stop="onPortMouseUp($event, node, 'input')"
            ></div>
            <!-- 输出端口 -->
            <div
              v-if="node.type !== 'end'"
              class="port output-port"
              @mousedown.stop="onPortMouseDown($event, node, 'output')"
            ></div>
          </div>
        </div>
      </div>

      <!-- 右栏:配置面板 -->
      <div class="config-panel">
        <div v-if="selectedNode" class="panel-content">
          <!-- 回放模式:节点执行详情 -->
          <template v-if="replayRun && replayTraceMap.get(selectedNode.id)">
            <div class="panel-title">执行详情</div>
            <div class="replay-detail">
              <el-tag :type="replayTagType(replayTraceMap.get(selectedNode.id))" size="small">
                {{ traceStatusText(replayTraceMap.get(selectedNode.id) || '') }}
              </el-tag>
              <pre v-if="replayTraceOutput(selectedNode.id)" class="replay-output">{{ replayTraceOutput(selectedNode.id) }}</pre>
              <div v-else class="replay-no-output">无输出</div>
            </div>
            <el-divider style="margin: 12px 0" />
          </template>
          <div class="panel-title">节点配置</div>
          <el-form label-width="80px" size="small">
            <el-form-item label="节点名称">
              <el-input v-model="selectedNode.name" :disabled="readonly" maxlength="20" @change="pushHistory" />
            </el-form-item>
            <el-form-item label="节点类型">
              <el-input :model-value="getNodeTypeLabel(selectedNode.type)" disabled />
            </el-form-item>

            <!-- LLM 节点配置 -->
            <template v-if="selectedNode.type === 'llm'">
              <el-form-item label="模型">
                <el-select v-model="selectedNode.config.modelId" :disabled="readonly" placeholder="选择模型" clearable style="width: 100%">
                  <el-option v-for="m in modelOptions" :key="m.id" :label="`${m.name}(${m.modelName})`" :value="m.id" />
                </el-select>
              </el-form-item>
              <el-form-item label="提示词">
                <el-input v-model="selectedNode.config.prompt" :disabled="readonly" type="textarea" :rows="3" maxlength="2000" show-word-limit />
              </el-form-item>
              <el-form-item label="温度">
                <el-input-number v-model="selectedNode.config.temperature" :disabled="readonly" :min="0" :max="2" :step="0.1" :precision="2" style="width: 100%" />
              </el-form-item>
            </template>

            <!-- 工具节点配置 -->
            <template v-if="selectedNode.type === 'tool'">
              <el-form-item label="工具">
                <el-select v-model="selectedNode.config.toolId" :disabled="readonly" placeholder="选择工具" clearable style="width: 100%">
                  <el-option v-for="t in toolOptions" :key="t.id" :label="t.name" :value="t.id" />
                </el-select>
              </el-form-item>
              <el-form-item label="参数(JSON)">
                <el-input v-model="selectedNode.config.args" :disabled="readonly" type="textarea" :rows="2" placeholder='{"param1":"{{start.query}}"}' />
                <div class="field-hint" v-pre>工具入参,支持 {{变量}} 引用上游节点输出,如 {{start.query}}</div>
              </el-form-item>
            </template>

            <!-- 知识库节点配置 -->
            <template v-if="selectedNode.type === 'knowledge'">
              <el-form-item label="知识库">
                <el-select v-model="selectedNode.config.knowledgeId" :disabled="readonly" placeholder="选择知识库" clearable style="width: 100%">
                  <el-option v-for="k in knowledgeOptions" :key="k.id" :label="k.name" :value="k.id" />
                </el-select>
              </el-form-item>
              <el-form-item label="检索词">
                <el-input v-model="selectedNode.config.query" :disabled="readonly" maxlength="500" placeholder="{{start.query}} 或固定文本,留空则取入参 query" />
              </el-form-item>
              <el-form-item label="检索条数">
                <el-input-number v-model="selectedNode.config.topK" :disabled="readonly" :min="1" :max="20" style="width: 100%" />
              </el-form-item>
            </template>

            <!-- 审批节点配置 -->
            <template v-if="selectedNode.type === 'approval'">
              <el-form-item label="审批说明">
                <el-input v-model="selectedNode.config.message" :disabled="readonly" type="textarea" :rows="3" maxlength="500" show-word-limit placeholder="呈现给审批人的说明,支持 {{变量}} 引用" />
              </el-form-item>
              <el-form-item label="超时(分)">
                <el-input-number v-model="selectedNode.config.expireMinutes" :disabled="readonly" :min="1" :max="43200" style="width: 100%" />
                <div class="field-hint">默认 1440 分钟(24 小时);超时审批单自动关闭,工作流终止</div>
              </el-form-item>
              <el-alert type="info" :closable="false" show-icon title="试运行至此节点将暂停并生成审批单,在工具审批中心处理后自动继续" />
            </template>

            <!-- 条件节点配置 -->
            <template v-if="selectedNode.type === 'condition'">
              <el-form-item label="分支">
                <div v-for="(branch, idx) in selectedNode.config.branches" :key="idx" class="branch-item">
                  <el-input v-model="branch.name" :disabled="readonly" placeholder="分支名" size="small" style="width: 100px" />
                  <el-input v-model="branch.condition" :disabled="readonly" placeholder="条件表达式" size="small" style="flex: 1" />
                  <el-button v-if="!readonly" icon="Delete" size="small" circle @click="removeBranch(idx)" />
                </div>
                <el-button v-if="!readonly" icon="Plus" size="small" @click="addBranch">添加分支</el-button>
              </el-form-item>
            </template>

            <!-- 结束节点配置 -->
            <template v-if="selectedNode.type === 'end'">
              <el-form-item label="输出映射">
                <el-input v-model="selectedNode.config.output" :disabled="readonly" type="textarea" :rows="3" placeholder="最终回复内容,支持 {{变量}} 引用" />
              </el-form-item>
            </template>

            <!-- 循环节点配置 -->
            <template v-if="selectedNode.type === 'loop'">
              <el-form-item label="模型">
                <el-select v-model="selectedNode.config.modelId" :disabled="readonly" placeholder="选择模型" clearable style="width: 100%">
                  <el-option v-for="m in modelOptions" :key="m.id" :label="`${m.name}(${m.modelName})`" :value="m.id" />
                </el-select>
              </el-form-item>
              <el-form-item label="迭代集合">
                <el-input v-model="selectedNode.config.items" :disabled="readonly" type="textarea" :rows="3" maxlength="4000" placeholder='{{start.items}} 或 JSON 数组/每行一项' />
              </el-form-item>
              <el-form-item label="循环提示词">
                <el-input v-model="selectedNode.config.prompt" :disabled="readonly" type="textarea" :rows="4" maxlength="2000" show-word-limit placeholder="对每项执行的提示词,使用 {{item}} 引用当前项" />
              </el-form-item>
              <el-form-item label="最大次数">
                <el-input-number v-model="selectedNode.config.maxLoops" :disabled="readonly" :min="1" :max="20" style="width: 100%" />
              </el-form-item>
              <el-form-item label="温度">
                <el-input-number v-model="selectedNode.config.temperature" :disabled="readonly" :min="0" :max="2" :step="0.1" :precision="2" style="width: 100%" />
              </el-form-item>
              <el-alert type="info" :closable="false" show-icon title="遍历迭代集合逐项调用模型,结果按序拼接为该节点输出" />
            </template>

            <!-- 代码节点配置 -->
            <template v-if="selectedNode.type === 'code'">
              <el-form-item label="Python代码">
                <el-input v-model="selectedNode.config.code" :disabled="readonly" type="textarea" :rows="8" maxlength="20000" show-word-limit placeholder="print('hello')" />
              </el-form-item>
              <el-form-item label="超时(秒)">
                <el-input-number v-model="selectedNode.config.timeoutSeconds" :disabled="readonly" :min="1" :max="120" style="width: 100%" />
              </el-form-item>
              <el-alert type="info" :closable="false" show-icon title="在隔离 Docker 沙箱中执行(无网络,内存/CPU 限额),stdout/stderr 作为节点输出" />
            </template>

            <!-- HTTP 节点配置 -->
            <template v-if="selectedNode.type === 'http'">
              <el-form-item label="请求方法">
                <el-select v-model="selectedNode.config.method" :disabled="readonly" style="width: 100%">
                  <el-option v-for="m in ['GET', 'POST', 'PUT', 'DELETE', 'PATCH']" :key="m" :label="m" :value="m" />
                </el-select>
              </el-form-item>
              <el-form-item label="请求地址">
                <el-input v-model="selectedNode.config.url" :disabled="readonly" maxlength="500" placeholder="https://api.example.com/xxx?a={{start.key}}" />
              </el-form-item>
              <el-form-item label="请求头">
                <el-input v-model="selectedNode.config.headers" :disabled="readonly" type="textarea" :rows="2" placeholder='{"Authorization":"Bearer xxx"}' />
              </el-form-item>
              <el-form-item v-if="!['GET', 'DELETE'].includes(selectedNode.config.method || 'GET')" label="请求体">
                <el-input v-model="selectedNode.config.body" :disabled="readonly" type="textarea" :rows="4" maxlength="20000" placeholder='{"query":"{{start.query}}"}' />
              </el-form-item>
              <el-form-item label="超时(秒)">
                <el-input-number v-model="selectedNode.config.timeoutSeconds" :disabled="readonly" :min="1" :max="120" style="width: 100%" />
              </el-form-item>
              <el-alert type="info" :closable="false" show-icon title="非 2xx 状态码视为执行失败;响应体注册为节点输出" />
            </template>

            <!-- 通用:失败处理与重试(W5) -->
            <el-form-item v-if="selectedNode.type !== 'start' && selectedNode.type !== 'end' && selectedNode.type !== 'parallel'" label="失败处理">
              <el-select v-model="selectedNode.config.failStrategy" :disabled="readonly" style="width: 100%">
                <el-option label="终止工作流并透出错误" value="terminate" />
                <el-option label="继续执行并输出兜底值" value="continue" />
                <el-option label="跳转到指定节点" value="goto" />
              </el-select>
            </el-form-item>
            <el-form-item v-if="selectedNode.config.failStrategy === 'continue' && selectedNode.type !== 'start' && selectedNode.type !== 'end'" label="兜底输出">
              <el-input v-model="selectedNode.config.fallbackOutput" :disabled="readonly" type="textarea" :rows="2" placeholder="失败时注册为节点输出的内容,支持 {{变量}} 引用" />
            </el-form-item>
            <el-form-item v-if="selectedNode.config.failStrategy === 'goto' && selectedNode.type !== 'start' && selectedNode.type !== 'end'" label="跳转目标">
              <el-select v-model="selectedNode.config.failureTargetNodeKey" :disabled="readonly" style="width: 100%">
                <el-option v-for="n in nodes" :key="n.id" :label="`${n.name} (${getNodeTypeLabel(n.type)})`" :value="n.id" />
              </el-select>
            </el-form-item>
            <el-form-item v-if="selectedNode.type !== 'start' && selectedNode.type !== 'end' && selectedNode.type !== 'parallel'" label="重试次数">
              <el-input-number v-model="selectedNode.config.retryCount" :disabled="readonly" :min="0" :max="3" style="width: 100%" />
            </el-form-item>
            <el-form-item v-if="selectedNode.config.retryCount && selectedNode.config.retryCount > 0 && selectedNode.type !== 'start' && selectedNode.type !== 'end'" label="重试间隔">
              <el-input-number v-model="selectedNode.config.retryIntervalMs" :disabled="readonly" :min="0" :max="5000" :step="100" style="width: 100%" />
              <div class="field-hint" v-pre>毫秒,上限 5000ms</div>
            </el-form-item>
            <el-form-item v-if="selectedNode.type !== 'start' && selectedNode.type !== 'end' && selectedNode.type !== 'parallel'" label="执行超时">
              <el-input-number v-model="selectedNode.config.timeoutMs" :disabled="readonly" :min="0" :max="600000" :step="1000" style="width: 100%" />
              <div class="field-hint" v-pre>毫秒,0 为不限;超时的节点不重试、不跳转,防止重复副作用</div>
            </el-form-item>
          </el-form>
        </div>
        <div v-else class="panel-content">
          <div class="panel-title">Agent 级设置</div>
          <el-form label-width="80px" size="small">
            <el-form-item label="Agent名称">
              <el-input :model-value="agentName" disabled />
            </el-form-item>
            <el-form-item label="工作流ID">
              <el-input :model-value="String(agentId)" disabled />
            </el-form-item>
            <el-form-item label="节点数量">
              <el-input :model-value="String(nodes.length)" disabled />
            </el-form-item>
            <el-form-item label="连线数量">
              <el-input :model-value="String(edges.length)" disabled />
            </el-form-item>
          </el-form>
          <div class="panel-title" style="margin-top: 20px">校验结果</div>
          <div v-if="validateResult" class="validate-result">
            <el-alert
              v-if="validateResult.valid"
              type="success"
              :closable="false"
              show-icon
              title="校验通过"
            />
            <template v-else>
              <el-alert
                v-for="(item, idx) in validateResult.blockers"
                :key="'b' + idx"
                type="error"
                :closable="false"
                show-icon
                :title="item.message"
                style="margin-bottom: 8px"
                @click="focusIssue(item)"
              />
            </template>
            <el-alert
              v-for="(item, idx) in validateResult.warnings"
              :key="'w' + idx"
              type="warning"
              :closable="false"
              show-icon
              :title="item.message"
              style="margin-bottom: 8px"
              @click="focusIssue(item)"
            />
          </div>
          <el-empty v-else description="点击工具栏「静态校验」查看结果" :image-size="60" />
        </div>
      </div>
    </div>

    <!-- 试运行配置与实时结果弹窗(W8/W9) -->
    <el-dialog v-model="trialDialog.visible" title="试运行" width="640px" :close-on-click-modal="!trialDialog.running">
      <!-- 表单模式 -->
      <template v-if="trialDialog.mode === 'form'">
        <el-form label-width="90px" size="small">
          <el-form-item label="入参 JSON">
            <el-input v-model="trialForm.inputs" type="textarea" :rows="4" placeholder='{"query":"测试输入"}' />
            <div class="field-hint" v-pre>每个键注入为 start.<键> 变量,供 knowledge / llm / tool 节点以 {{start.键}} 引用</div>
          </el-form-item>
          <el-form-item label="空跑模式">
            <el-switch v-model="trialForm.dryRun" />
            <span class="field-hint" style="margin-left: 8px">LLM / 工具 / 知识库节点返回桩输出,不调用真实模型</span>
          </el-form-item>
          <el-form-item label="起跑节点">
            <el-select v-model="trialForm.startNodeKey" placeholder="从开始节点执行" clearable style="width: 100%">
              <el-option v-for="n in nodes.filter(x => x.type !== 'start')" :key="n.id" :label="`${n.name} (${getNodeTypeLabel(n.type)})`" :value="n.id" />
            </el-select>
            <div class="field-hint">上游节点视为已完成,适合从中间节点调试</div>
          </el-form-item>
          <el-form-item label="步数上限">
            <el-input-number v-model="trialForm.maxSteps" :min="1" :max="500" style="width: 100%" />
            <div class="field-hint">防条件环失控,默认 100</div>
          </el-form-item>
        </el-form>
      </template>
      <!-- 实时执行模式 -->
      <template v-else>
        <el-alert
          :type="trialLive.finalStatus === 'success' ? 'success' : trialLive.finalStatus === 'pending_approval' ? 'warning' : trialLive.finalStatus ? 'error' : 'info'"
          :closable="false"
          show-icon
          :title="trialLive.title"
          style="margin-bottom: 12px"
        />
        <el-alert
          v-if="trialLive.finalStatus === 'pending_approval'"
          type="warning"
          :closable="false"
          show-icon
          title="审批单已生成,到「工具审批中心」处理后工作流将自动继续执行"
          style="margin-bottom: 12px"
        />
        <div class="trace-title">节点执行轨迹{{ trialLive.running ? '(实时)' : '' }}</div>
        <el-timeline v-if="trialLive.trace.length" class="trace-timeline">
          <el-timeline-item
            v-for="(item, idx) in trialLive.trace"
            :key="idx"
            :type="item.status === 'success' ? 'success' : item.status === 'running' ? 'primary' : item.status === 'pending' || item.status === 'pending_approval' ? 'warning' : item.status === 'skipped' ? 'info' : 'danger'"
            :hollow="item.status === 'skipped'"
          >
            <div class="trace-node">
              <el-tag size="small" :color="getNodeColor(item.nodeType)" style="color: #fff; border: none">
                {{ getNodeTypeLabel(item.nodeType) }}
              </el-tag>
              <span class="trace-node-name">{{ item.nodeName }}</span>
              <span class="trace-node-status">{{ traceStatusText(item.status) }}</span>
            </div>
            <div v-if="item.output" class="trace-node-output">{{ item.output }}</div>
          </el-timeline-item>
        </el-timeline>
        <el-empty v-else description="等待执行事件..." :image-size="60" />
      </template>
      <template #footer>
        <template v-if="trialDialog.mode === 'form'">
          <el-button @click="trialDialog.visible = false">取消</el-button>
          <el-button type="primary" :loading="trialDialog.running" @click="startTrialRun">开始执行</el-button>
        </template>
        <template v-else>
          <el-button :disabled="trialLive.running" @click="closeTrialLive">关闭</el-button>
        </template>
      </template>
    </el-dialog>

    <!-- 版本历史抽屉 -->
    <el-drawer v-model="versionDrawer" title="版本历史" size="460px">
      <el-table :data="versionList" size="small" v-loading="versionLoading">
        <el-table-column label="版本" width="70">
          <template #default="{ row }">v{{ row.version }}</template>
        </el-table-column>
        <el-table-column prop="remark" label="备注" show-overflow-tooltip />
        <el-table-column label="时间" width="150">
          <template #default="{ row }">{{ formatTime(row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="previewVersionDetail((row as WorkflowVersionVO))">预览</el-button>
            <el-button v-if="!publishedReadonly" link type="warning" size="small" @click="handleRollback((row as WorkflowVersionVO))">回滚</el-button>
          </template>
        </el-table-column>
        <template #empty><el-empty description="暂无版本,保存后自动生成" :image-size="60" /></template>
      </el-table>
    </el-drawer>

    <!-- 运行记录抽屉 -->
    <el-drawer v-model="runDrawer" title="运行记录" size="520px">
      <el-table :data="runList" size="small" v-loading="runLoading">
        <el-table-column label="ID" width="90">
          <template #default="{ row }">#{{ String(row.id).slice(-6) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="replayTagType(row.status)" size="small">{{ traceStatusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="输入" show-overflow-tooltip>
          <template #default="{ row }">{{ row.inputs || '-' }}</template>
        </el-table-column>
        <el-table-column label="时间" width="150">
          <template #default="{ row }">{{ formatTime(row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="70" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="startReplay((row as WorkflowRunVO))">回放</el-button>
          </template>
        </el-table-column>
        <template #empty><el-empty description="暂无运行记录,试运行后自动生成" :image-size="60" /></template>
      </el-table>
      <el-pagination
        v-model:current-page="runPage"
        :total="runTotal"
        :page-size="runPageSize"
        layout="prev, pager, next"
        style="margin-top: 12px; justify-content: flex-end"
        @current-change="loadRunList"
      />
    </el-drawer>
  </div>
</template>

<script setup lang="ts" name="AgentWorkflow">
import { ref, reactive, computed, onMounted, onUnmounted, nextTick } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import {
  Back, ZoomIn, ZoomOut, FullScreen, RefreshLeft, RefreshRight,
  CircleCheck, VideoPlay, Document, Close, Plus, Delete, Clock, List
} from '@element-plus/icons-vue';
import { getAgent } from '@/api/agent/agent';
import {
  saveWorkflow, validateWorkflow, trialRunWorkflowAsync,
  consumeWorkflowRunEvents,
  listWorkflowVersion, getWorkflowVersion, rollbackWorkflowVersion,
  listWorkflowRun, getWorkflowRun
} from '@/api/agent/workflow';
import type {
  WorkflowValidateResult, WorkflowIssue,
  WorkflowVersionVO, WorkflowRunVO, WorkflowRunDetailVO
} from '@/api/agent/workflow';
import { listModel } from '@/api/agent/model';
import { listTool } from '@/api/agent/tool';
import { listKnowledge } from '@/api/agent/knowledge';
import type { ModelVO } from '@/api/agent/model/types';
import type { ToolVO } from '@/api/agent/tool/types';
import type { KnowledgeVO } from '@/api/agent/knowledge/types';

// ========== 类型定义 ==========
interface NodeConfig {
  modelId?: number | string;
  prompt?: string;
  temperature?: number;
  toolId?: number | string;
  /** 工具节点:入参 JSON(支持 {{变量}} 引用),与后端 executeToolNode 的 args 字段对应 */
  args?: string;
  knowledgeId?: number | string;
  topK?: number;
  /** 知识库节点:检索词(支持 {{变量}} 引用,留空回退 start.query) */
  query?: string;
  branches?: Array<{ name: string; condition: string }>;
  /** 审批节点:呈现给审批人的说明 */
  message?: string;
  /** G1:审批截止分钟数(默认 1440=24h,超时自动关闭并终止工作流) */
  expireMinutes?: number;
  output?: string;
  failStrategy?: string;
  /** 循环节点:迭代集合(支持 {{变量}} 引用,JSON 数组/换行/逗号分隔) */
  items?: string;
  /** 循环节点:单轮最大迭代次数(1-20) */
  maxLoops?: number;
  /** 代码节点:Python 源代码 */
  code?: string;
  /** 代码/HTTP 节点:超时秒数 */
  timeoutSeconds?: number;
  /** 代码节点:沙箱镜像 */
  image?: string;
  /** 代码节点:内存限额(如 128m) */
  memory?: string;
  /** 代码节点:CPU 限额(如 0.5) */
  cpus?: string;
  /** HTTP 节点:请求方法 */
  method?: string;
  /** HTTP 节点:请求地址(支持 {{变量}} 引用) */
  url?: string;
  /** HTTP 节点:请求头 JSON */
  headers?: string;
  /** HTTP 节点:请求体(支持 {{变量}} 引用) */
  body?: string;
  /** W5:重试次数(0-3,超时节点不重试) */
  retryCount?: number;
  /** W5:重试间隔毫秒(0-5000) */
  retryIntervalMs?: number;
  /** W5:节点执行超时毫秒(0 为不限) */
  timeoutMs?: number;
  /** W5:失败策略 continue 时的兜底输出(支持 {{变量}} 引用) */
  fallbackOutput?: string;
  /** W5:失败策略 goto 时的跳转目标节点 ID */
  failureTargetNodeKey?: string;
}

interface WorkflowNode {
  id: string;
  type: string;
  name: string;
  x: number;
  y: number;
  config: NodeConfig;
}

interface WorkflowEdge {
  id: string;
  source: string;
  target: string;
  label?: string;
  /** W6:条件节点出边关联的分支名(执行时按名选路,替代旧的下标对齐) */
  branchName?: string;
}

interface WorkflowGraph {
  nodes: WorkflowNode[];
  edges: WorkflowEdge[];
}

// ========== 基础状态 ==========
const route = useRoute();
const router = useRouter();
const agentId = ref<string>(route.params.id as string);
const agentName = ref('');
const agentVersion = ref('1.0.0');
/** 版本预览模式(非空表示正在预览的历史版本号) */
const previewVersionNo = ref<number | null>(null);
/** 执行回放模式(非空表示正在回放的运行实例) */
const replayRun = ref<WorkflowRunDetailVO | null>(null);
/** 只读:版本预览 / 执行回放 禁止编辑;已发布 Agent 允许编辑工作流草稿(P1/B2 修订) */
const readonly = computed(() => previewVersionNo.value !== null || replayRun.value !== null);
/** 已发布态:编辑的是草稿,保存后需重新发布生效 */
const publishedReadonly = ref(false);
const saving = ref(false);

const canvasWrapperRef = ref<HTMLElement>();
const canvasRef = ref<HTMLElement>();

const nodes = ref<WorkflowNode[]>([]);
const edges = ref<WorkflowEdge[]>([]);
const selectedNodeId = ref<string>('');
const selectedEdgeId = ref<string>('');

const zoom = ref(1);
const pan = reactive({ x: 0, y: 0 });

// 撤销/重做
const historyStack = ref<string[]>([]);
const historyIndex = ref(-1);

// 连线临时状态
const tempEdge = ref<{ sourceId: string; sourceType: string; mouseX: number; mouseY: number } | null>(null);
const dragState = reactive({
  isDragging: false,
  dragNodeId: '',
  dragStartX: 0,
  dragStartY: 0,
  nodeStartX: 0,
  nodeStartY: 0,
  isPanning: false,
  panStartX: 0,
  panStartY: 0
});

// 试运行(W8/W9:表单配置 + 异步执行 + 实时轨迹)
const trialDialog = ref<{ visible: boolean; mode: 'form' | 'live'; running: boolean }>({
  visible: false,
  mode: 'form',
  running: false
});
const trialForm = ref({
  inputs: '{"query":"测试输入"}',
  dryRun: false,
  startNodeKey: '',
  maxSteps: 100
});
/** 实时执行状态:轨迹按事件追加,finalStatus 非空表示已终态 */
const trialLive = ref<{
  running: boolean;
  title: string;
  finalStatus: string;
  trace: Array<{ nodeId: string; nodeName: string; nodeType: string; status: string; output?: string }>;
}>({ running: false, title: '已提交,等待执行事件...', finalStatus: '', trace: [] });
/** 实时执行期间节点 ID → 状态映射(画布点亮) */
const liveStatusMap = computed(() => {
  const m = new Map<string, string>();
  trialLive.value.trace.forEach((t) => m.set(t.nodeId, t.status));
  return m;
});

// 校验结果
const validateResult = ref<WorkflowValidateResult | null>(null);

// 选项数据
const modelOptions = ref<ModelVO[]>([]);
const toolOptions = ref<ToolVO[]>([]);
const knowledgeOptions = ref<KnowledgeVO[]>([]);

// ========== 节点库定义 ==========
const nodeGroups = [
  {
    title: '输入',
    items: [
      { type: 'start', name: '开始', desc: '工作流入口,声明入参', icon: 'VideoPlay', color: '#67c23a' }
    ]
  },
  {
    title: '推理',
    items: [
      { type: 'llm', name: 'LLM', desc: '调用大模型生成内容', icon: 'ChatDotRound', color: '#409eff' }
    ]
  },
  {
    title: '数据',
    items: [
      { type: 'knowledge', name: '知识库检索', desc: '从知识库检索上下文', icon: 'Collection', color: '#e6a23c' },
      { type: 'code', name: '代码执行', desc: 'Docker 沙箱运行 Python', icon: 'Document', color: '#722ed1' }
    ]
  },
  {
    title: '调用',
    items: [
      { type: 'tool', name: '工具调用', desc: '调用已配置的工具', icon: 'Tools', color: '#909399' },
      { type: 'http', name: 'HTTP 请求', desc: '调用外部 HTTP 接口', icon: 'Link', color: '#13c2c2' }
    ]
  },
  {
    title: '控制',
    items: [
      { type: 'approval', name: '人工审批', desc: '暂停等待人工审批后继续', icon: 'Stamp', color: '#9254de' },
      { type: 'condition', name: '条件分支', desc: '按条件分流执行路径', icon: 'Branch', color: '#f56c6c' },
      { type: 'loop', name: '循环迭代', desc: '遍历集合逐项执行提示词', icon: 'Refresh', color: '#fa8c16' },
      { type: 'parallel', name: '并行分支', desc: '多分支并发执行后汇聚', icon: 'Share', color: '#eb2f96' },
      { type: 'join', name: '汇聚', desc: '并行分支的确定性汇合点', icon: 'Connection', color: '#fa541c' },
      { type: 'end', name: '结束', desc: '工作流终态,输出结果', icon: 'CircleCheck', color: '#67c23a' }
    ]
  }
];

const getNodeIcon = (type: string) => {
  const map: Record<string, string> = {
    start: 'VideoPlay',
    llm: 'ChatDotRound',
    tool: 'Tools',
    knowledge: 'Collection',
    approval: 'Stamp',
    condition: 'Branch',
    loop: 'Refresh',
    code: 'Document',
    http: 'Link',
    parallel: 'Share',
    join: 'Connection',
    end: 'CircleCheck'
  };
  return map[type] || 'Box';
};

const getNodeColor = (type: string) => {
  const map: Record<string, string> = {
    start: '#67c23a',
    llm: '#409eff',
    tool: '#909399',
    knowledge: '#e6a23c',
    approval: '#9254de',
    condition: '#f56c6c',
    loop: '#fa8c16',
    code: '#722ed1',
    http: '#13c2c2',
    parallel: '#eb2f96',
    join: '#fa541c',
    end: '#67c23a'
  };
  return map[type] || '#909399';
};

const getNodeTypeLabel = (type: string) => {
  const map: Record<string, string> = {
    start: '开始',
    llm: 'LLM',
    tool: '工具调用',
    knowledge: '知识库检索',
    approval: '人工审批',
    condition: '条件分支',
    loop: '循环迭代',
    code: '代码执行',
    http: 'HTTP 请求',
    parallel: '并行分支',
    join: '汇聚',
    end: '结束'
  };
  return map[type] || type;
};

const traceStatusText = (status: string) => {
  const map: Record<string, string> = {
    success: '成功',
    fail: '失败',
    failed: '失败',
    running: '执行中',
    skipped: '跳过',
    pending: '待审批',
    pending_approval: '待审批',
    failed_continue: '失败继续',
    failed_goto: '失败跳转'
  };
  return map[status] || status;
};

// ========== 计算属性 ==========
const selectedNode = computed(() => nodes.value.find(n => n.id === selectedNodeId.value) || null);
const canUndo = computed(() => historyIndex.value > 0);
const canRedo = computed(() => historyIndex.value < historyStack.value.length - 1);

// ========== 版本历史与执行回放 ==========
const versionDrawer = ref(false);
const versionLoading = ref(false);
const versionList = ref<WorkflowVersionVO[]>([]);
const runDrawer = ref(false);
const runLoading = ref(false);
const runList = ref<WorkflowRunVO[]>([]);
const runPage = ref(1);
const runPageSize = 10;
const runTotal = ref(0);

/** 回放模式下节点ID -> 执行状态映射 */
const replayTraceMap = computed(() => {
  const m = new Map<string, string>();
  (replayRun.value?.trace || []).forEach((t) => m.set(t.nodeId, t.status));
  return m;
});

const replayNodeClass = (nodeId: string) => {
  // W9:实时执行优先点亮,回放模式次之
  const s = liveStatusMap.value.get(nodeId) || replayTraceMap.value.get(nodeId);
  return s ? `replay-${s}` : '';
};

const replayTraceOutput = (nodeId: string) => {
  return (replayRun.value?.trace || []).find((t) => t.nodeId === nodeId)?.output || '';
};

const replayTagType = (status?: string): 'success' | 'danger' | 'warning' | 'info' => {
  const map: Record<string, 'success' | 'danger' | 'warning' | 'info'> = {
    success: 'success',
    fail: 'danger',
    failed: 'danger',
    skipped: 'info',
    pending: 'warning',
    pending_approval: 'warning',
    running: 'info'
  };
  return map[status || ''] || 'info';
};

const formatTime = (t?: string) => {
  if (!t) return '-';
  const d = new Date(t);
  return isNaN(d.getTime()) ? t : d.toLocaleString('zh-CN', { hour12: false });
};

/** 加载图 JSON 到画布(预览/回放共用):不写入撤销历史,退出时由 loadAgent 重建 */
const loadGraphJson = (json: string) => {
  try {
    const graph: WorkflowGraph = JSON.parse(json);
    nodes.value = graph.nodes || [];
    edges.value = graph.edges || [];
    normalizeLegacyConfig();
  } catch (e) {
    ElMessage.error('图快照解析失败');
    return;
  }
  selectedNodeId.value = '';
  selectedEdgeId.value = '';
  historyStack.value = [];
  historyIndex.value = -1;
  nextTick(() => fitView());
};

const openVersionDrawer = async () => {
  versionDrawer.value = true;
  versionLoading.value = true;
  try {
    const res = await listWorkflowVersion(agentId.value);
    versionList.value = res.data || [];
  } finally {
    versionLoading.value = false;
  }
};

/** 预览历史版本:画布切换为该版本图快照,进入只读预览模式 */
const previewVersionDetail = async (row: WorkflowVersionVO) => {
  const res = await getWorkflowVersion(row.id);
  const detail = res.data;
  if (!detail?.workflowJson) {
    ElMessage.warning('该版本缺少图快照');
    return;
  }
  replayRun.value = null;
  previewVersionNo.value = detail.version;
  loadGraphJson(detail.workflowJson);
  versionDrawer.value = false;
};

/** 回滚指定版本:草稿被覆盖并生成新版本 */
const handleRollback = async (row: WorkflowVersionVO) => {
  try {
    await ElMessageBox.confirm(
      `确定回滚到 v${row.version} 吗?当前草稿内容将被该版本覆盖,回滚会自动生成一个新版本。`,
      '回滚确认',
      { type: 'warning', confirmButtonText: '回滚', cancelButtonText: '取消' }
    );
  } catch {
    return;
  }
  const res = await rollbackWorkflowVersion(row.id);
  ElMessage.success(`已回滚到 v${row.version},生成新版本 v${res.data}`);
  versionDrawer.value = false;
  previewVersionNo.value = null;
  await loadAgent();
};

/** 工具栏快捷入口:回滚当前正在预览的版本 */
const handleRollbackCurrent = () => {
  const v = versionList.value.find((x) => x.version === previewVersionNo.value);
  if (v) {
    handleRollback(v);
  } else {
    ElMessage.warning('版本信息缺失,请从版本抽屉中回滚');
  }
};

const openRunDrawer = async () => {
  runDrawer.value = true;
  runPage.value = 1;
  await loadRunList();
};

const loadRunList = async () => {
  runLoading.value = true;
  try {
    const res = await listWorkflowRun(agentId.value, runPage.value, runPageSize);
    runList.value = res.data?.rows || [];
    runTotal.value = Number(res.data?.total || 0);
  } finally {
    runLoading.value = false;
  }
};

/** 回放运行实例:画布加载执行时刻图快照,节点按轨迹状态着色 */
const startReplay = async (row: WorkflowRunVO) => {
  const res = await getWorkflowRun(row.id);
  const detail = res.data;
  if (!detail?.workflowJson) {
    ElMessage.warning('该运行实例缺少图快照');
    return;
  }
  previewVersionNo.value = null;
  replayRun.value = detail;
  loadGraphJson(detail.workflowJson);
  runDrawer.value = false;
};

/** 退出预览/回放,恢复编辑态草稿 */
const exitSpecialMode = () => {
  previewVersionNo.value = null;
  replayRun.value = null;
  loadAgent();
};

/** W0:迁移修复前的历史画布数据,旧字段 paramMapping 改为 args(后端执行侧同时兼容) */
const normalizeLegacyConfig = () => {
  nodes.value.forEach((n) => {
    if (n.type === 'tool' && n.config) {
      const legacy = (n.config as Record<string, unknown>).paramMapping;
      if (!n.config.args && typeof legacy === 'string') {
        n.config.args = legacy;
      }
      delete (n.config as Record<string, unknown>).paramMapping;
    }
  });
};

// ========== 历史记录 ==========
const pushHistory = () => {
  if (readonly.value) return;
  const snapshot = JSON.stringify({ nodes: nodes.value, edges: edges.value });
  historyStack.value = historyStack.value.slice(0, historyIndex.value + 1);
  historyStack.value.push(snapshot);
  historyIndex.value = historyStack.value.length - 1;
};

const undo = () => {
  if (!canUndo.value) return;
  historyIndex.value--;
  restoreSnapshot(historyStack.value[historyIndex.value]);
};

const redo = () => {
  if (!canRedo.value) return;
  historyIndex.value++;
  restoreSnapshot(historyStack.value[historyIndex.value]);
};

const restoreSnapshot = (snapshot: string) => {
  const data = JSON.parse(snapshot);
  nodes.value = data.nodes;
  edges.value = data.edges;
  selectedNodeId.value = '';
  selectedEdgeId.value = '';
};

// ========== 节点操作 ==========
const onDragStart = (e: DragEvent, item: { type: string }) => {
  if (readonly.value) return;
  e.dataTransfer?.setData('text/plain', item.type);
};

const addNode = (type: string, x?: number, y?: number) => {
  if (readonly.value) return;
  // 开始/结束节点全图唯一
  if ((type === 'start' || type === 'end') && nodes.value.some(n => n.type === type)) {
    ElMessage.warning(`${getNodeTypeLabel(type)}节点全图仅允许一个`);
    return;
  }
  const id = `node_${Date.now()}_${Math.random().toString(36).slice(2, 7)}`;
  const name = getNodeTypeLabel(type) + '_' + (nodes.value.filter(n => n.type === type).length + 1);
  const defaultConfig: NodeConfig = {
    failStrategy: 'terminate'
  };
  if (type === 'tool') {
    defaultConfig.args = '{}';
  }
  if (type === 'knowledge') {
    defaultConfig.query = '{{start.query}}';
    defaultConfig.topK = 5;
  }
  if (type === 'condition') {
    defaultConfig.branches = [{ name: '分支1', condition: '' }];
  }
  if (type === 'approval') {
    defaultConfig.message = '';
    defaultConfig.expireMinutes = 1440;
  }
  if (type === 'loop') {
    defaultConfig.items = '';
    defaultConfig.prompt = '';
    defaultConfig.maxLoops = 10;
  }
  if (type === 'code') {
    defaultConfig.code = '';
    defaultConfig.timeoutSeconds = 30;
  }
  if (type === 'http') {
    defaultConfig.method = 'GET';
    defaultConfig.url = '';
    defaultConfig.headers = '{}';
    defaultConfig.body = '';
    defaultConfig.timeoutSeconds = 30;
  }
  const newNode: WorkflowNode = {
    id,
    type,
    name,
    x: x ?? 200 + Math.random() * 200,
    y: y ?? 150 + Math.random() * 150,
    config: defaultConfig
  };
  nodes.value.push(newNode);
  pushHistory();
  return newNode;
};

const removeNode = (id: string) => {
  if (readonly.value) return;
  const node = nodes.value.find(n => n.id === id);
  if (!node) return;
  if (node.type === 'start' || node.type === 'end') {
    ElMessage.warning('开始/结束节点不可删除');
    return;
  }
  nodes.value = nodes.value.filter(n => n.id !== id);
  edges.value = edges.value.filter(e => e.source !== id && e.target !== id);
  if (selectedNodeId.value === id) selectedNodeId.value = '';
  pushHistory();
};

const selectNode = (id: string) => {
  selectedNodeId.value = id;
  selectedEdgeId.value = '';
};

const selectEdge = (id: string) => {
  selectedEdgeId.value = id;
  selectedNodeId.value = '';
};

const removeSelectedEdge = () => {
  if (readonly.value || !selectedEdgeId.value) return;
  removeEdge(selectedEdgeId.value);
  ElMessage.success('连线已删除');
};

// ========== 画布拖拽与连线 ==========
const onNodeMouseDown = (e: MouseEvent, node: WorkflowNode) => {
  if (readonly.value) return;
  if (e.button !== 0) return;
  dragState.isDragging = true;
  dragState.dragNodeId = node.id;
  dragState.dragStartX = e.clientX;
  dragState.dragStartY = e.clientY;
  dragState.nodeStartX = node.x;
  dragState.nodeStartY = node.y;
};

const onCanvasMouseDown = (e: MouseEvent) => {
  if (e.button !== 0) return;
  // 点击空白处取消选中
  selectedNodeId.value = '';
  selectedEdgeId.value = '';
  // 开始平移
  dragState.isPanning = true;
  dragState.panStartX = e.clientX - pan.x;
  dragState.panStartY = e.clientY - pan.y;
};

const onCanvasMouseMove = (e: MouseEvent) => {
  if (dragState.isDragging && dragState.dragNodeId) {
    const dx = (e.clientX - dragState.dragStartX) / zoom.value;
    const dy = (e.clientY - dragState.dragStartY) / zoom.value;
    const node = nodes.value.find(n => n.id === dragState.dragNodeId);
    if (node) {
      node.x = dragState.nodeStartX + dx;
      node.y = dragState.nodeStartY + dy;
    }
  } else if (dragState.isPanning) {
    pan.x = e.clientX - dragState.panStartX;
    pan.y = e.clientY - dragState.panStartY;
  } else if (tempEdge.value && canvasRef.value) {
    const rect = canvasRef.value.getBoundingClientRect();
    tempEdge.value.mouseX = (e.clientX - rect.left - pan.x) / zoom.value;
    tempEdge.value.mouseY = (e.clientY - rect.top - pan.y) / zoom.value;
  }
};

const onCanvasMouseUp = () => {
  if (dragState.isDragging) {
    pushHistory();
  }
  dragState.isDragging = false;
  dragState.dragNodeId = '';
  dragState.isPanning = false;
  tempEdge.value = null;
};

const onDrop = (e: DragEvent) => {
  if (readonly.value) return;
  const type = e.dataTransfer?.getData('text/plain');
  if (!type || !canvasRef.value) return;
  // 开始/结束节点全图唯一
  if ((type === 'start' || type === 'end') && nodes.value.some(n => n.type === type)) {
    ElMessage.warning(`${getNodeTypeLabel(type)}节点全图仅允许一个`);
    return;
  }
  const rect = canvasRef.value.getBoundingClientRect();
  const x = (e.clientX - rect.left - pan.x) / zoom.value;
  const y = (e.clientY - rect.top - pan.y) / zoom.value;
  addNode(type, x, y);
};

// ========== 端口连线 ==========
const onPortMouseDown = (e: MouseEvent, node: WorkflowNode, portType: 'input' | 'output') => {
  if (readonly.value) return;
  e.stopPropagation();
  if (portType === 'output') {
    tempEdge.value = { sourceId: node.id, sourceType: node.type, mouseX: 0, mouseY: 0 };
  }
};

const onPortMouseUp = (e: MouseEvent, node: WorkflowNode, portType: 'input' | 'output') => {
  if (readonly.value) return;
  if (!tempEdge.value) return;
  if (portType === 'input' && tempEdge.value.sourceId !== node.id) {
    addEdge(tempEdge.value.sourceId, node.id);
  }
  tempEdge.value = null;
};

const addEdge = (source: string, target: string) => {
  const id = `edge_${Date.now()}_${Math.random().toString(36).slice(2, 7)}`;
  const edge: WorkflowEdge = { id, source, target };
  // W6:条件节点出边自动关联分支名(取配置中尚未被其他出边占用的第一个分支)
  const srcNode = nodes.value.find(n => n.id === source);
  if (srcNode?.type === 'condition') {
    const used = new Set(
      edges.value.filter(e => e.source === source && e.branchName).map(e => e.branchName!)
    );
    const branch = (srcNode.config.branches || []).find(b => !used.has(b.name));
    if (branch) {
      edge.branchName = branch.name;
    }
  }
  edges.value.push(edge);
  pushHistory();
};

const removeEdge = (id: string) => {
  if (readonly.value) return;
  edges.value = edges.value.filter(e => e.id !== id);
  if (selectedEdgeId.value === id) selectedEdgeId.value = '';
  pushHistory();
};

// ========== 边路径计算 ==========
const getNodeCenter = (node: WorkflowNode) => {
  return { x: node.x + 80, y: node.y + 30 };
};

const getEdgePath = (edge: WorkflowEdge) => {
  const sourceNode = nodes.value.find(n => n.id === edge.source);
  const targetNode = nodes.value.find(n => n.id === edge.target);
  if (!sourceNode || !targetNode) return '';
  const s = getNodeCenter(sourceNode);
  const t = getNodeCenter(targetNode);
  const midX = (s.x + t.x) / 2;
  return `M ${s.x} ${s.y} C ${midX} ${s.y}, ${midX} ${t.y}, ${t.x} ${t.y}`;
};

const getTempEdgePath = () => {
  if (!tempEdge.value) return '';
  const sourceNode = nodes.value.find(n => n.id === tempEdge.value!.sourceId);
  if (!sourceNode) return '';
  const s = getNodeCenter(sourceNode);
  const t = { x: tempEdge.value.mouseX, y: tempEdge.value.mouseY };
  const midX = (s.x + t.x) / 2;
  return `M ${s.x} ${s.y} C ${midX} ${s.y}, ${midX} ${t.y}, ${t.x} ${t.y}`;
};

/** W6:条件分支出边的分支名标签位置(贝塞尔中点附近) */
const edgeLabelPos = (edge: WorkflowEdge) => {
  const sourceNode = nodes.value.find(n => n.id === edge.source);
  const targetNode = nodes.value.find(n => n.id === edge.target);
  if (!sourceNode || !targetNode) return { x: 0, y: 0 };
  const s = getNodeCenter(sourceNode);
  const t = getNodeCenter(targetNode);
  return { x: (s.x + t.x) / 2, y: (s.y + t.y) / 2 - 6 };
};

// ========== 缩放与平移 ==========
const onWheel = (e: WheelEvent) => {
  e.preventDefault();
  const delta = e.deltaY > 0 ? -0.1 : 0.1;
  zoom.value = Math.min(2, Math.max(0.3, zoom.value + delta));
};

const zoomIn = () => { zoom.value = Math.min(2, zoom.value + 0.1); };
const zoomOut = () => { zoom.value = Math.max(0.3, zoom.value - 0.1); };
const fitView = () => {
  if (nodes.value.length === 0) {
    pan.x = 0;
    pan.y = 0;
    zoom.value = 1;
    return;
  }
  const minX = Math.min(...nodes.value.map(n => n.x));
  const maxX = Math.max(...nodes.value.map(n => n.x + 160));
  const minY = Math.min(...nodes.value.map(n => n.y));
  const maxY = Math.max(...nodes.value.map(n => n.y + 60));
  const width = maxX - minX;
  const height = maxY - minY;
  const wrapper = canvasWrapperRef.value;
  if (!wrapper) return;
  const scaleX = wrapper.clientWidth / (width + 100);
  const scaleY = wrapper.clientHeight / (height + 100);
  zoom.value = Math.min(2, Math.max(0.3, Math.min(scaleX, scaleY)));
  pan.x = -minX * zoom.value + 50;
  pan.y = -minY * zoom.value + 50;
};

// ========== 条件节点分支 ==========
const addBranch = () => {
  if (!selectedNode.value || selectedNode.value.type !== 'condition') return;
  if (!selectedNode.value.config.branches) {
    selectedNode.value.config.branches = [];
  }
  selectedNode.value.config.branches.push({ name: `分支${selectedNode.value.config.branches.length + 1}`, condition: '' });
  pushHistory();
};

const removeBranch = (idx: number) => {
  if (!selectedNode.value?.config.branches) return;
  selectedNode.value.config.branches.splice(idx, 1);
  pushHistory();
};

// ========== 校验 ==========
const buildGraphJson = (): string => {
  return JSON.stringify({ nodes: nodes.value, edges: edges.value });
};

const handleValidate = async () => {
  const res = await validateWorkflow({ agentId: agentId.value, workflowJson: buildGraphJson() });
  validateResult.value = res.data || null;
  if (validateResult.value?.valid) {
    ElMessage.success('校验通过');
  } else {
    ElMessage.warning('存在阻断项,请根据右侧面板提示修正');
  }
};

const focusIssue = (issue: WorkflowIssue) => {
  if (issue.nodeId) {
    selectedNodeId.value = issue.nodeId;
    const node = nodes.value.find(n => n.id === issue.nodeId);
    if (node && canvasWrapperRef.value) {
      pan.x = -node.x * zoom.value + canvasWrapperRef.value.clientWidth / 2;
      pan.y = -node.y * zoom.value + canvasWrapperRef.value.clientHeight / 2;
    }
  }
};

// ========== 保存与试运行 ==========
const handleSave = async () => {
  const validateRes = await validateWorkflow({ agentId: agentId.value, workflowJson: buildGraphJson() });
  if (!validateRes.data?.valid) {
    validateResult.value = validateRes.data;
    ElMessage.error('校验未通过,请先修正阻断项');
    return;
  }
  saving.value = true;
  try {
    const res = await saveWorkflow({ agentId: agentId.value, workflowJson: buildGraphJson() });
    ElMessage.success(publishedReadonly.value
      ? `已保存草稿 v${res.data},重新发布后生效`
      : `保存成功,已生成版本 v${res.data}`);
    if (versionDrawer.value) {
      openVersionDrawer();
    }
  } finally {
    saving.value = false;
  }
};

/** 打开试运行配置弹窗(W8:入参/空跑/起跑节点/步数上限) */
const handleTrialRun = () => {
  trialForm.value.inputs = trialForm.value.inputs || '{"query":"测试输入"}';
  trialForm.value.startNodeKey = '';
  trialDialog.value.mode = 'form';
  trialDialog.value.running = false;
  trialDialog.value.visible = true;
};

/** 校验入参 JSON 是否为合法对象 */
const validateTrialInputs = (): boolean => {
  if (!trialForm.value.inputs || !trialForm.value.inputs.trim()) {
    ElMessage.warning('入参不能为空');
    return false;
  }
  try {
    const parsed = JSON.parse(trialForm.value.inputs);
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
      ElMessage.warning('入参必须是 JSON 对象');
      return false;
    }
    return true;
  } catch {
    ElMessage.warning('入参 JSON 格式不正确');
    return false;
  }
};

/** W9:异步提交执行并订阅逐节点事件流,画布按真实执行顺序点亮 */
const startTrialRun = async () => {
  if (!validateTrialInputs()) return;
  trialDialog.value.running = true;
  let runId: number | string;
  try {
    const res = await trialRunWorkflowAsync({
      agentId: agentId.value,
      workflowJson: buildGraphJson(),
      inputs: trialForm.value.inputs,
      dryRun: trialForm.value.dryRun,
      startNodeKey: trialForm.value.startNodeKey || undefined,
      maxSteps: trialForm.value.maxSteps
    });
    runId = res.data;
  } catch {
    trialDialog.value.running = false;
    return;
  }
  // 切换到实时执行模式
  trialDialog.value.mode = 'live';
  trialDialog.value.running = false;
  trialLive.value.running = true;
  trialLive.value.finalStatus = '';
  trialLive.value.title = '执行中...';
  trialLive.value.trace = [];
  const upsert = (nodeId: string, nodeName: string, nodeType: string, status: string, output?: string) => {
    const exist = trialLive.value.trace.find((t) => t.nodeId === nodeId);
    if (exist) {
      exist.status = status;
      if (output !== undefined) exist.output = output;
    } else {
      trialLive.value.trace.push({ nodeId, nodeName, nodeType, status, output });
    }
  };
  try {
    await consumeWorkflowRunEvents(runId, 0, {
      onNodeStart: (nodeId, nodeName, nodeType) => upsert(nodeId, nodeName, nodeType, 'running'),
      onNodeDone: (nodeId, nodeName, nodeType, status, output) => {
        // failed_continue / failed_goto 按 traceStatusText 展示;进度条颜色复用现有映射
        upsert(nodeId, nodeName, nodeType, status === 'success' ? 'success' : status, output);
      },
      onNodeError: (nodeId, nodeName, nodeType, error) => upsert(nodeId, nodeName, nodeType, 'fail', error),
      onApprovalPending: (nodeId) => upsert(nodeId, '', 'approval', 'pending'),
      onDone: (status, message) => {
        trialLive.value.finalStatus = status;
        trialLive.value.title = message || (status === 'success' ? '执行完成' : status === 'pending_approval' ? '等待人工审批' : '执行失败');
      }
    });
  } catch (e) {
    ElMessage.error('执行事件流连接失败,可稍后在运行记录中回放');
    trialLive.value.title = '事件流中断,结果以运行记录为准';
  } finally {
    trialLive.value.running = false;
    // 执行结束后刷新运行记录列表缓存,下次打开即最新
    runList.value = [];
    runTotal.value = 0;
  }
};

/** 关闭实时执行弹窗 */
const closeTrialLive = () => {
  trialDialog.value.visible = false;
  trialDialog.value.mode = 'form';
};

// ========== 生命周期 ==========
const loadAgent = async () => {
  const res = await getAgent(agentId.value);
  const agent = res.data;
  if (!agent) {
    ElMessage.error('Agent 不存在');
    return;
  }
  agentName.value = agent.name;
  publishedReadonly.value = agent.status === '1';
  if (agent.workflowJson) {
    try {
      const graph: WorkflowGraph = JSON.parse(agent.workflowJson);
      nodes.value = graph.nodes || [];
      edges.value = graph.edges || [];
      normalizeLegacyConfig();
    } catch (e) {
      initDefaultGraph();
    }
  } else {
    initDefaultGraph();
  }
  pushHistory();
  nextTick(() => fitView());
};

const initDefaultGraph = () => {
  nodes.value = [
    { id: 'start', type: 'start', name: '开始', x: 80, y: 200, config: { failStrategy: 'terminate' } },
    { id: 'end', type: 'end', name: '结束', x: 520, y: 200, config: { failStrategy: 'terminate', output: '{{start.query}}' } }
  ];
  // W3:默认图预连线,新画布不连线直接保存会被连通性校验拒绝
  edges.value = [{ id: 'edge_start_end', source: 'start', target: 'end' }];
};

const loadOptions = async () => {
  const [models, tools, kbs] = await Promise.all([
    listModel({ pageNum: 1, pageSize: 100 }),
    listTool({ pageNum: 1, pageSize: 100 }),
    listKnowledge({ pageNum: 1, pageSize: 100 })
  ]);
  modelOptions.value = models.data?.rows || [];
  toolOptions.value = tools.data?.rows || [];
  knowledgeOptions.value = kbs.data?.rows || [];
};

const goBack = () => {
  router.push('/agent/agent');
};

const onKeyDown = (e: KeyboardEvent) => {
  if (readonly.value) return;
  // 焦点在输入控件时不拦截:避免编辑配置面板时误删节点/劫持输入框撤销
  const target = e.target as HTMLElement | null;
  const tag = target?.tagName;
  if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || target?.isContentEditable) return;
  if (e.key === 'Delete' || e.key === 'Backspace') {
    if (selectedNodeId.value) {
      const node = nodes.value.find(n => n.id === selectedNodeId.value);
      if (node && node.type !== 'start' && node.type !== 'end') {
        removeNode(selectedNodeId.value);
        ElMessage.success('节点已删除');
      }
    } else if (selectedEdgeId.value) {
      removeEdge(selectedEdgeId.value);
      ElMessage.success('连线已删除');
    }
  }
  // Ctrl+Z / Ctrl+Shift+Z / Ctrl+Y
  if ((e.ctrlKey || e.metaKey) && e.key === 'z' && !e.shiftKey) {
    e.preventDefault();
    undo();
  }
  if ((e.ctrlKey || e.metaKey) && (e.key === 'y' || (e.key === 'z' && e.shiftKey))) {
    e.preventDefault();
    redo();
  }
};

onMounted(() => {
  loadAgent();
  loadOptions();
  document.addEventListener('keydown', onKeyDown);
});

onUnmounted(() => {
  document.removeEventListener('keydown', onKeyDown);
});
</script>

<style scoped>
.workflow-editor {
  display: flex;
  flex-direction: column;
  height: 100vh;
  background: #f5f7fa;
}
.workflow-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 16px;
  background: #fff;
  border-bottom: 1px solid #e4e7ed;
  z-index: 10;
}
.toolbar-left {
  display: flex;
  align-items: center;
  gap: 12px;
}
.agent-name {
  font-weight: 600;
  font-size: 15px;
}
.toolbar-right {
  display: flex;
  align-items: center;
  gap: 8px;
}
.zoom-text {
  font-size: 12px;
  color: #606266;
  min-width: 44px;
  text-align: center;
}
.workflow-body {
  display: flex;
  flex: 1;
  overflow: hidden;
}
.node-palette {
  width: 220px;
  background: #fff;
  border-right: 1px solid #e4e7ed;
  overflow-y: auto;
  padding: 12px;
}
.palette-title {
  font-weight: 600;
  font-size: 14px;
  margin-bottom: 12px;
  color: #303133;
}
.palette-group {
  margin-bottom: 16px;
}
.group-title {
  font-size: 12px;
  color: #909399;
  margin-bottom: 8px;
  font-weight: 600;
}
.palette-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px;
  margin-bottom: 6px;
  border: 1px solid #e4e7ed;
  border-radius: 6px;
  cursor: grab;
  transition: all 0.2s;
  background: #fafafa;
}
.palette-item:hover {
  border-color: #409eff;
  box-shadow: 0 2px 8px rgba(64, 158, 255, 0.15);
}
.palette-item.disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.palette-item .node-icon {
  flex-shrink: 0;
}
.palette-item .node-info {
  min-width: 0;
}
.palette-item .node-name {
  font-size: 13px;
  font-weight: 500;
  color: #303133;
}
.palette-item .node-desc {
  font-size: 11px;
  color: #909399;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.canvas-wrapper {
  flex: 1;
  overflow: hidden;
  position: relative;
}
.canvas {
  position: absolute;
  width: 3000px;
  height: 2000px;
  transform-origin: 0 0;
}
.grid-bg {
  position: absolute;
  inset: 0;
  background-image: radial-gradient(circle, #dcdfe6 1px, transparent 1px);
  background-size: 20px 20px;
}
.edges-layer {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  pointer-events: none;
}
.edge-path {
  fill: none;
  stroke: #c0c4cc;
  stroke-width: 2;
  pointer-events: stroke;
  cursor: pointer;
  transition: stroke 0.2s;
}
.edge-path:hover {
  stroke: #409eff;
}
.edge-path.selected {
  stroke: #409eff;
  stroke-width: 3;
}
.edge-path.temp {
  stroke: #409eff;
  stroke-dasharray: 5, 5;
}
.edge-label {
  font-size: 11px;
  fill: #909399;
  text-anchor: middle;
  dominant-baseline: middle;
  pointer-events: none;
  user-select: none;
}
.workflow-node.replay-running {
  border-color: #409eff;
  box-shadow: 0 0 0 3px rgba(64, 158, 255, 0.25);
  animation: wf-running-pulse 1.2s ease-in-out infinite;
}
@keyframes wf-running-pulse {
  0%, 100% { box-shadow: 0 0 0 3px rgba(64, 158, 255, 0.25); }
  50% { box-shadow: 0 0 0 6px rgba(64, 158, 255, 0.45); }
}
.workflow-node {
  position: absolute;
  width: 160px;
  background: #fff;
  border: 2px solid #e4e7ed;
  border-radius: 8px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.06);
  cursor: move;
  user-select: none;
  transition: border-color 0.2s, box-shadow 0.2s;
}
.workflow-node:hover {
  border-color: #409eff;
  box-shadow: 0 4px 12px rgba(64, 158, 255, 0.15);
}
.workflow-node.selected {
  border-color: #409eff;
  box-shadow: 0 0 0 3px rgba(64, 158, 255, 0.2);
}
.workflow-node.readonly {
  cursor: default;
}
.workflow-node.readonly:hover {
  border-color: #e4e7ed;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.06);
}
/* 回放模式:节点按执行状态着色 */
.workflow-node.replay-success {
  box-shadow: 0 0 0 2px #67c23a;
}
.workflow-node.replay-fail,
.workflow-node.replay-failed {
  box-shadow: 0 0 0 2px #f56c6c;
}
.workflow-node.replay-skipped {
  box-shadow: 0 0 0 2px #c0c4cc;
  opacity: 0.65;
}
.workflow-node.replay-pending,
.workflow-node.replay-pending_approval {
  box-shadow: 0 0 0 2px #e6a23c;
}
.replay-detail {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.replay-output {
  margin: 0;
  padding: 8px;
  background: #f5f7fa;
  border-radius: 4px;
  font-size: 12px;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 180px;
  overflow-y: auto;
}
.replay-no-output {
  color: #909399;
  font-size: 12px;
}
.node-header {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 10px;
  border-radius: 6px 6px 0 0;
}
.node-title {
  font-size: 12px;
  font-weight: 600;
  color: #fff;
  flex: 1;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.node-close {
  cursor: pointer;
  opacity: 0.8;
}
.node-close:hover {
  opacity: 1;
}
.node-body {
  padding: 8px 10px;
}
.node-type-label {
  font-size: 11px;
  color: #909399;
}
.port {
  position: absolute;
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: #fff;
  border: 2px solid #409eff;
  top: 50%;
  transform: translateY(-50%);
  cursor: crosshair;
  z-index: 5;
}
.input-port {
  left: -5px;
}
.output-port {
  right: -5px;
}
.config-panel {
  width: 300px;
  background: #fff;
  border-left: 1px solid #e4e7ed;
  overflow-y: auto;
  padding: 16px;
}
.panel-title {
  font-weight: 600;
  font-size: 14px;
  margin-bottom: 16px;
  color: #303133;
  padding-bottom: 8px;
  border-bottom: 1px solid #e4e7ed;
}
.branch-item {
  display: flex;
  gap: 6px;
  align-items: center;
  margin-bottom: 8px;
}
.field-hint {
  font-size: 12px;
  color: #909399;
  line-height: 1.4;
  margin-top: 4px;
}
.validate-result {
  max-height: 300px;
  overflow-y: auto;
}
.trace-title {
  font-weight: 600;
  font-size: 13px;
  margin-bottom: 12px;
  color: #303133;
}
.trace-timeline {
  padding-left: 4px;
}
.trace-node {
  display: flex;
  align-items: center;
  gap: 8px;
}
.trace-node-name {
  font-size: 13px;
  color: #303133;
  font-weight: 500;
}
.trace-node-status {
  font-size: 12px;
  color: #67c23a;
}
.trace-node-output {
  margin-top: 6px;
  padding: 8px 10px;
  background: #f5f7fa;
  border-radius: 4px;
  font-size: 12px;
  line-height: 1.6;
  color: #606266;
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 160px;
  overflow-y: auto;
}
</style>
