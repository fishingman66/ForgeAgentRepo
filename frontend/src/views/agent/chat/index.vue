<template>
  <div class="app-container agent-chat-page">
    <div class="chat-layout">
      <!-- 左侧:会话列表 -->
      <div class="session-panel">
        <div class="session-header">
          <el-select v-model="selectedAgentId" placeholder="选择 Agent" filterable style="width: 100%">
            <el-option v-for="a in agentOptions" :key="a.id" :label="a.name" :value="a.id" />
          </el-select>
          <el-input v-model="sessionKeyword" placeholder="搜索会话" clearable prefix-icon="Search" class="session-search" @input="onSearchInput" />
          <el-button type="primary" icon="Plus" class="new-session-btn" :disabled="!selectedAgentId" @click="handleNewSession">新建会话</el-button>
        </div>
        <div v-loading="sessionLoading" class="session-list">
          <el-empty v-if="sessions.length === 0" description="暂无会话" :image-size="80" />
          <div
            v-for="s in sessions"
            :key="s.id"
            class="session-item"
            :class="{ active: s.id === currentSessionId }"
            @click="openSession(s)"
          >
            <div class="session-title">
              <el-icon v-if="s.pinned === '1'" class="pin-flag"><Top /></el-icon>{{ s.title }}
            </div>
            <div class="session-meta">
              <span>{{ s.agentName || '-' }}</span>
              <span>{{ s.messageCount ?? 0 }} 条</span>
              <span v-if="s.lastMsgTime" class="session-time">{{ formatRelativeTime(s.lastMsgTime) }}</span>
            </div>
            <div class="session-ops" @click.stop>
              <el-tooltip :content="s.pinned === '1' ? '取消置顶' : '置顶'" placement="top" :show-after="150">
                <el-button size="small" text :type="s.pinned === '1' ? 'warning' : ''" :icon="s.pinned === '1' ? 'Bottom' : 'Top'" @click="handlePin(s)" />
              </el-tooltip>
              <el-button size="small" text icon="Edit" @click="handleRename(s)" />
              <el-button size="small" text type="danger" icon="Delete" @click="handleDeleteSession(s)" />
            </div>
          </div>
        </div>
      </div>

      <!-- 右侧:消息区 -->
      <div class="chat-panel">
        <div class="chat-header">
          <span class="chat-title">{{ currentAgentName || '请选择 Agent 并新建会话' }}</span>
          <el-tag v-if="currentSessionId" size="small" type="info" effect="plain">会话 #{{ currentSessionId }}</el-tag>
          <el-button
            v-if="currentSessionId && messages.length"
            class="export-btn" size="small" text icon="Download" :disabled="sending"
            @click="handleExport"
          >导出</el-button>
        </div>

        <!-- P0:断线续传进行中的轻提示 -->
        <div v-if="reconnectHint" class="reconnect-hint">
          <span class="hint-dot"></span>{{ reconnectHint }}
        </div>

        <!-- UX-03:Token 用量实时反馈吸顶栏(累计/本轮/上下文剩余,点击查看明细) -->
        <div v-if="currentSessionId" class="usage-bar">
          <el-popover placement="bottom-start" trigger="click" width="260">
            <template #reference>
              <div class="usage-summary">
                <span class="usage-total">累计 {{ fmtTokens(sessionUsage?.totalTokens) }} tokens</span>
                <span v-if="sessionUsage?.lastRoundTokens" class="usage-round">本轮 +{{ fmtTokens(sessionUsage.lastRoundTokens) }}</span>
                <template v-if="sessionUsage?.contextRemainingPercent != null">
                  <el-progress
                    class="usage-progress" :percentage="Math.round(sessionUsage.contextRemainingPercent)"
                    :stroke-width="6" :color="usageBarColor" :show-text="false"
                  />
                  <span class="usage-remain" :style="{ color: usageBarColor }">剩余 {{ sessionUsage.contextRemainingPercent }}%</span>
                  <span v-if="sessionUsage.contextRemainingPercent < 10" class="usage-warn">建议开启新会话</span>
                </template>
              </div>
            </template>
            <div class="usage-detail">
              <div>输入:{{ fmtTokens(sessionUsage?.promptTokens) }} tokens</div>
              <div>输出:{{ fmtTokens(sessionUsage?.completionTokens) }} tokens</div>
              <div v-if="sessionUsage?.contextWindow">上下文窗口:{{ fmtTokens(sessionUsage.contextWindow) }} tokens</div>
              <div v-if="sessionUsage?.costEstimate != null">
                累计成本估算:{{ sessionUsage.costEstimate }} {{ sessionUsage.currency || 'USD' }}
              </div>
              <div v-else class="usage-cost-empty">模型未配置定价,暂无成本估算</div>
            </div>
          </el-popover>
        </div>

        <div ref="msgContainer" class="message-list" @scroll="onMsgScroll">
          <div v-if="hasMoreMessages" class="load-earlier">
            <el-button size="small" text :loading="loadingEarlier" @click="loadEarlierMessages">加载更早消息</el-button>
          </div>
          <el-empty v-if="messages.length === 0 && !welcomeText" description="发起新会话开始对话" />
          <div v-if="welcomeText" class="msg-row assistant">
            <div class="bubble assistant-bubble welcome">{{ welcomeText }}</div>
          </div>
          <div v-if="currentSessionId && messages.length === 0 && currentSuggestions.length" class="suggest-chips">
            <el-button
              v-for="(q, i) in currentSuggestions" :key="i"
              size="small" round plain type="primary" class="suggest-chip"
              @click="handleSuggest(q)"
            >{{ q }}</el-button>
          </div>
          <div v-for="m in messages" :key="m.id" class="msg-row" :class="m.role">
            <div class="bubble-wrap">
              <div v-if="m.teamTrace?.length" class="team-trace">
                <div class="trace-title">团队协作轨迹({{ m.teamTrace.length }} 棒)</div>
                <el-timeline class="trace-timeline">
                  <el-timeline-item
                    v-for="(t, i) in m.teamTrace"
                    :key="i"
                    :type="t.role === 'leader' ? 'primary' : 'success'"
                    :hollow="t.role !== 'leader'"
                  >
                    <div class="trace-head">
                      <span class="trace-agent">{{ t.agentName }}</span>
                      <el-tag size="small" :type="t.role === 'leader' ? 'primary' : 'info'" effect="plain">
                        {{ t.role === 'leader' ? '主Agent 综合' : '成员处理' }}
                      </el-tag>
                      <span v-if="t.status === 'processing'" class="trace-processing">处理中<span class="dot-flash">...</span></span>
                      <span v-else-if="t.totalTokens != null" class="trace-tokens">{{ t.totalTokens }} tokens</span>
                    </div>
                    <div class="trace-content" :class="{ 'trace-loading': t.status === 'processing' }">
                      <template v-if="t.status === 'processing' && !t.content">
                        <span class="typing-dots"><span /><span /><span /></span>
                      </template>
                      <div v-else class="md-body" v-html="renderMd(t.content)"></div>
                      <span v-if="t.status === 'processing' && t.content" class="cursor-blink">|</span>
                    </div>
                  </el-timeline-item>
                </el-timeline>
              </div>
              <!-- 体检 E1:工作流节点时间线(流式进行中实时点亮) -->
              <div v-if="m.wfTrace?.length" class="team-trace">
                <div class="trace-title">工作流执行轨迹({{ m.wfTrace.length }} 个节点)</div>
                <el-timeline class="trace-timeline">
                  <el-timeline-item
                    v-for="(w, i) in m.wfTrace"
                    :key="'wf-' + i"
                    :type="w.status === 'fail' ? 'danger' : w.status === 'done' ? 'success' : 'warning'"
                    :hollow="w.status === 'processing'"
                  >
                    <div class="trace-head">
                      <span class="trace-agent">{{ w.nodeName || w.nodeId }}</span>
                      <el-tag size="small" type="warning" effect="plain">{{ wfTypeLabel(w.nodeType) }}</el-tag>
                      <span v-if="w.status === 'processing'" class="trace-processing">执行中<span class="dot-flash">...</span></span>
                      <span v-else-if="w.status === 'fail'" style="color: var(--el-color-danger)">执行失败</span>
                      <span v-else style="color: var(--el-color-success)">完成</span>
                    </div>
                  </el-timeline-item>
                </el-timeline>
              </div>
              <!-- 问数:SQL 执行结果卡片(表格 + 图表切换) -->
              <div v-if="m.role === 'assistant' && m.sqlResults?.length" class="sql-results">
                <div v-for="(r, ri) in m.sqlResults" :key="'sql-' + ri" class="sql-card" :class="{ failed: r.success === false }">
                  <div class="sql-head">
                    <el-tag size="small" type="warning" effect="plain">问数</el-tag>
                    <span class="sql-ds">{{ r.datasource }}</span>
                    <span v-if="r.success !== false" class="sql-meta">
                      {{ r.rowCount }} 行 · {{ r.elapsedMs }}ms<template v-if="r.truncated"> · 已截断</template>
                    </span>
                    <span v-else class="sql-meta">执行失败</span>
                    <el-button-group v-if="r.success !== false && r.columns?.length" class="sql-actions">
                      <el-button size="small" text :type="!modeOf(m, ri) ? 'primary' : ''" @click="setChartMode(m, ri, '')">表格</el-button>
                      <el-button v-for="ct in ['bar', 'line', 'pie']" :key="ct" size="small" text :type="modeOf(m, ri) === ct ? 'primary' : ''" @click="setChartMode(m, ri, ct)">
                        {{ { bar: '柱状', line: '折线', pie: '饼图' }[ct] }}
                      </el-button>
                      <el-button size="small" text icon="Download" @click="exportSqlCsv(m, ri)">导出</el-button>
                    </el-button-group>
                  </div>
                  <pre class="sql-text">{{ r.sql }}</pre>
                  <div v-if="r.success === false" class="sql-error">{{ r.error || '执行失败' }}</div>
                  <template v-else-if="r.columns?.length">
                    <div v-if="!modeOf(m, ri)" class="sql-table">
                      <el-table :data="tableRows(r)" size="small" border max-height="260">
                        <el-table-column
                          v-for="(c, ci) in r.columns" :key="c + '-' + ci"
                          :label="c" :prop="String(ci)" min-width="90" show-overflow-tooltip
                        />
                      </el-table>
                      <div v-if="r.rowCapped" class="sql-cap">仅展示前 {{ r.rows?.length }} 行,共 {{ r.rowCount }} 行</div>
                    </div>
                    <div v-else class="sql-chart">
                      <div :ref="(el) => setChartRef(chartKey(m, ri), el)" class="chart-canvas"></div>
                    </div>
                  </template>
                </div>
              </div>
              <div class="bubble" :class="m.role === 'user' ? 'user-bubble' : 'assistant-bubble'" @click="onMdCopyClick">
                <div v-if="m.attachments?.length || m.previewThumbs?.length" class="msg-thumbs">
                  <template v-for="att in m.attachments" :key="'att-' + att.id">
                    <img
                      v-if="att.kind !== 'file'"
                      class="thumb-img"
                      :src="att.thumb"
                      alt="图片附件"
                      @click="previewAttachment(att)"
                    />
                    <div v-else class="file-chip" :title="att.name" @click="openFileAttachment(att)">
                      <el-icon class="file-chip-icon"><Document /></el-icon>
                      <span class="file-chip-name">{{ att.name }}</span>
                    </div>
                  </template>
                  <img v-for="(u, i) in m.previewThumbs" :key="'pv-' + i" class="thumb-img" :src="u" alt="待发送图片" />
                </div>
                <span v-if="m.typing" class="typing-dots"><i></i><i></i><i></i></span>
                <pre v-else-if="m.structured" class="json-block">{{ prettyJson(m.content) }}</pre>
                <!-- 助手消息渲染 Markdown(标题/列表/表格/代码高亮);用户消息保持纯文本 -->
                <div v-else-if="m.role === 'assistant' && m.content" class="md-body" v-html="renderMd(m.content)"></div>
                <template v-else-if="m.content">{{ m.content }}</template>
              </div>
              <div v-if="m.role === 'assistant' && m.citations?.length" class="citation-box">
                <div class="citation-title">引用来源({{ m.citations.length }})</div>
                <div v-for="c in m.citations" :key="c.index" class="citation-item">
                  <el-tag size="small" effect="plain">{{ c.index }}</el-tag>
                  <span class="citation-name">
                    {{ c.fileName || '知识库片段' }}
                    <template v-if="c.pageNo"> · 第 {{ c.pageNo }} 页</template>
                    <template v-else-if="c.sheetName"> · 工作表 {{ c.sheetName }}</template>
                    <template v-else-if="c.chunkIndex != null"> · 分块 {{ c.chunkIndex }}</template>
                  </span>
                  <span v-if="c.score != null" class="citation-score">{{ c.score }}</span>
                  <el-tooltip :content="c.snippet || '无内容摘要'" placement="top" :show-after="150">
                    <el-icon class="citation-eye"><View /></el-icon>
                  </el-tooltip>
                </div>
              </div>
              <div v-if="m.role === 'assistant' && !m.typing" class="msg-ops">
                <el-tooltip v-if="isLastMessage(m)" content="重新生成" placement="top" :show-after="200">
                  <button type="button" class="op-btn" :disabled="sending" @click="handleRegenerate">
                    <svg viewBox="0 0 24 24">
                      <path d="M17.65 6.35A7.958 7.958 0 0 0 12 4c-4.42 0-7.99 3.58-8 8s3.58 8 8 8c3.73 0 6.84-2.55 7.73-6h-2.08A5.99 5.99 0 0 1 12 18c-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z" />
                    </svg>
                  </button>
                </el-tooltip>
                <el-tooltip content="复制" placement="top" :show-after="200">
                  <button type="button" class="op-btn" @click="handleCopy(m)">
                    <svg viewBox="0 0 24 24">
                      <path d="M16 1H4c-1.1 0-2 .9-2 2v14h2V3h12V1zm3 4H8c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h11c1.1 0 2-.9 2-2V7c0-1.1-.9-2-2-2zm0 16H8V7h11v14z" />
                    </svg>
                  </button>
                </el-tooltip>
                <el-tooltip content="点赞" placement="top" :show-after="200">
                  <button
                    type="button" class="op-btn"
                    :class="{ 'is-active-accent': m.feedback === 'like' }"
                    @click="handleFeedback(m, 'like')"
                  >
                    <svg viewBox="0 0 24 24">
                      <path d="M1 21h4V9H1v12zM23 10c0-1.1-.9-2-2-2h-6.31l.95-4.57.03-.32c0-.41-.17-.79-.44-1.06L14.17 1 7.59 7.59C7.22 7.95 7 8.45 7 9v10c0 1.1.9 2 2 2h9c.83 0 1.54-.5 1.84-1.22l3.02-7.05c.09-.23.14-.47.14-.73v-2z" />
                    </svg>
                  </button>
                </el-tooltip>
                <el-popover :visible="feedbackPanelMsgId === m.id" placement="top" width="320">
                  <template #reference>
                    <el-tooltip content="点踩" placement="top" :show-after="200">
                      <button
                        type="button" class="op-btn"
                        :class="{ 'is-active-danger': m.feedback === 'dislike' }"
                        @click="openFeedbackPanel(m)"
                      >
                        <svg viewBox="0 0 24 24">
                          <path d="M15 3H6c-.83 0-1.54.5-1.84 1.22l-3.02 7.05c-.09.23-.14.47-.14.73v2c0 1.1.9 2 2 2h6.31l-.95 4.57-.03.32c0 .41.17.79.44 1.06L9.83 23l6.59-6.59c.36-.36.58-.86.58-1.41V5c0-1.1-.9-2-2-2zm4 0v12h4V3h-4z" />
                        </svg>
                      </button>
                    </el-tooltip>
                  </template>
                  <div class="feedback-panel">
                    <div class="feedback-tags">
                      <el-check-tag
                        v-for="t in FEEDBACK_TAGS" :key="t"
                        :checked="feedbackForm.tags.includes(t)" class="feedback-tag"
                        @change="toggleFeedbackTag(t)"
                      >{{ t }}</el-check-tag>
                    </div>
                    <el-input
                      v-model="feedbackForm.comment" type="textarea" :rows="2"
                      maxlength="200" show-word-limit placeholder="补充说明(选填,200字内)"
                    />
                    <div class="feedback-actions">
                      <el-button v-if="m.sqlResults?.length" size="small" text type="warning" @click="openExampleDialog(m)">转为示例库</el-button>
                      <span style="flex: 1"></span>
                      <el-button v-if="m.feedback === 'dislike'" size="small" text type="danger" @click="clearFeedback(m)">取消点踩</el-button>
                      <el-button size="small" @click="feedbackPanelMsgId = ''">关闭</el-button>
                      <el-button size="small" type="primary" :loading="feedbackSaving" @click="submitFeedback(m)">提交</el-button>
                    </div>
                  </div>
                </el-popover>
                <el-tooltip v-if="m.runId" content="查看运行链路" placement="top" :show-after="200">
                  <button type="button" class="op-btn" @click="openRunTrace(m.runId)">
                    <svg viewBox="0 0 24 24">
                      <path d="M4 5h16v4H4V5zm0 5h10v4H4v-4zm0 5h13v4H4v-4z" />
                    </svg>
                  </button>
                </el-tooltip>
                <span v-if="m.createTime" class="op-time">{{ formatMsgTime(m.createTime) }}</span>
              </div>
              <div v-if="m.role === 'user' && !m.typing" class="msg-ops user-ops">
                <span v-if="m.createTime" class="op-time">{{ formatMsgTime(m.createTime) }}</span>
                <el-tooltip content="复制" placement="top" :show-after="200">
                  <button type="button" class="op-btn" @click="handleCopy(m)">
                    <svg viewBox="0 0 24 24">
                      <path d="M16 1H4c-1.1 0-2 .9-2 2v14h2V3h12V1zm3 4H8c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h11c1.1 0 2-.9 2-2V7c0-1.1-.9-2-2-2zm0 16H8V7h11v14z" />
                    </svg>
                  </button>
                </el-tooltip>
                <el-tooltip content="修改(回填输入框编辑后重发)" placement="top" :show-after="200">
                  <button type="button" class="op-btn" :disabled="sending" @click="handleEdit(m)">
                    <svg viewBox="0 0 24 24">
                      <path d="M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25zM20.71 7.04c.39-.39.39-1.02 0-1.41l-2.34-2.34c-.39-.39-1.02-.39-1.41 0l-1.83 1.83 3.75 3.75 1.83-1.83z" />
                    </svg>
                  </button>
                </el-tooltip>
                <el-tooltip v-if="isRecallable(m)" content="撤回" placement="top" :show-after="200">
                  <button type="button" class="op-btn" :disabled="sending" @click="handleRecall(m)">
                    <svg viewBox="0 0 24 24">
                      <path d="M12.5 8c-2.65 0-5.05.99-6.9 2.6L2 7v9h9l-3.62-3.62c1.39-1.16 3.16-1.88 5.12-1.88 3.54 0 6.55 2.31 7.6 5.5l2.37-.78C21.08 11.03 17.15 8 12.5 8z" />
                    </svg>
                  </button>
                </el-tooltip>
              </div>
              <div v-if="m.structured && !m.typing" class="structured-tag">
                <el-tag size="small" type="warning" effect="plain">结构化输出</el-tag>
              </div>
            </div>
          </div>
        </div>

        <!-- 用户上滑阅读历史时的"回到底部"悬浮入口 -->
        <transition name="el-fade-in">
          <button v-if="!autoFollow" type="button" class="back-bottom-btn" @click="jumpToBottom">
            <el-icon><Bottom /></el-icon>
            <span v-if="newBelowCount > 0">{{ newBelowCount }} 条新消息</span>
          </button>
        </transition>

        <div class="input-area" @dragover.prevent @drop.prevent="handleDrop">
          <div v-if="pendingAttachments.length" class="pending-images">
            <div v-for="p in pendingAttachments" :key="p.key" class="pending-item" :class="[p.status, p.kind]">
              <img v-if="p.kind === 'image'" class="pending-thumb" :src="p.preview" alt="待发送图片" />
              <div v-else class="pending-file">
                <el-icon class="pending-file-icon"><Document /></el-icon>
                <div class="pending-file-meta">
                  <div class="pending-file-name" :title="p.file.name">{{ p.file.name }}</div>
                  <div class="pending-file-sub">{{ pendingStatusText(p) }}</div>
                </div>
              </div>
              <div v-if="p.status === 'uploading'" class="pending-mask">上传中…</div>
              <div v-else-if="p.status === 'parsing'" class="pending-mask">解析中…</div>
              <div v-else-if="p.status === 'failed'" class="pending-mask failed">{{ p.error || '上传失败' }}</div>
              <div class="pending-ops">
                <el-icon v-if="p.status === 'failed'" class="pending-op" title="重试" @click="retryPending(p)"><RefreshRight /></el-icon>
                <el-icon class="pending-op" title="移除" @click="removePending(p)"><Close /></el-icon>
              </div>
            </div>
          </div>
          <el-input
            ref="inputRef"
            v-model="inputText"
            type="textarea"
            :rows="3"
            resize="none"
            maxlength="4000"
            show-word-limit
            placeholder="输入消息,Enter 发送,Shift+Enter 换行"
            @keydown.enter="onInputEnter"
            @paste="handlePaste"
          />
          <div v-if="pendingIssueTip" class="pending-tip">{{ pendingIssueTip }}</div>
          <div class="input-actions">
            <span class="send-tip">消息由 Agent 绑定的大模型实时生成</span>
            <div class="action-buttons">
              <el-tooltip content="上传文档或图片(PDF/Word/Excel/CSV/TXT 等)" placement="top">
                <span class="attach-btn-wrap">
                  <el-button
                    class="attach-btn"
                    icon="Paperclip"
                    :disabled="pendingAttachments.length >= 4 || sending"
                    @click="triggerPick"
                  />
                </span>
              </el-tooltip>
              <el-button v-if="sending && currentSessionId" type="warning" plain icon="SwitchButton" :loading="interrupting" @click="handleInterrupt">中断</el-button>
              <el-button type="primary" :loading="sending" :disabled="!canSend" @click="handleSend">发送</el-button>
            </div>
          </div>
          <input
            ref="fileInputRef"
            class="hidden-file"
            type="file"
            accept="image/png,image/jpeg,.pdf,.docx,.xlsx,.csv,.txt,.md,.json,.html"
            multiple
            @change="handleFileChange"
          />
        </div>
      </div>
    </div>

    <el-dialog
      v-model="previewVisible"
      :title="previewName || '图片预览'"
      width="720px"
      append-to-body
      class="attach-preview-dialog"
      @closed="handlePreviewClosed"
    >
      <img v-if="previewUrl" class="preview-img" :src="previewUrl" alt="原图" />
    </el-dialog>

    <!-- UX-02 问数纠错:转为示例库(正确 SQL 留空待人工填写) -->
    <el-dialog v-model="exampleDialog.visible" title="转为问数示例" width="600px" destroy-on-close>
      <el-form label-width="90px">
        <el-form-item label="示例问题">
          <el-input v-model="exampleDialog.question" type="textarea" :rows="2" maxlength="500" show-word-limit />
        </el-form-item>
        <el-form-item label="错误SQL">
          <el-input :model-value="exampleDialog.wrongSql" type="textarea" :rows="3" readonly placeholder="无" />
        </el-form-item>
        <el-form-item label="正确SQL">
          <el-input v-model="exampleDialog.sqlContent" type="textarea" :rows="5" placeholder="该问题对应的正确 SELECT 语句" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="exampleDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="exampleDialog.saving" @click="submitExample">保存示例</el-button>
      </template>
    </el-dialog>

    <!-- P0:运行链路下钻 -->
    <RunTraceDrawer v-model="traceVisible" :run-id="traceRunId" />
  </div>
</template>

<script setup lang="ts" name="AgentChat">
import * as echarts from 'echarts';
import { listPublishedAgent } from '@/api/agent/agent';
import type { AgentVO } from '@/api/agent/agent/types';
import {
  addSession,
  delSession,
  feedbackMessage,
  fetchChatAttachmentObjectUrl,
  getChatAttachmentMeta,
  getSessionUsage,
  interruptChat,
  listMessages,
  listSession,
  pinSession,
  recallMessage,
  regenerateChat,
  renameSession,
  sendChat,
  streamChat,
  streamRegenerate,
  uploadChatAttachment
} from '@/api/agent/session';
import type { ChatAttachment, ChatSendForm, ChatSendResult, MessageVO, SessionUsageBar, SessionVO } from '@/api/agent/session/types';
import { addDatasourceExample } from '@/api/agent/datasource';
import { ElMessageBox } from 'element-plus';
import RunTraceDrawer from './components/RunTraceDrawer.vue';
import MarkdownIt from 'markdown-it';
import hljs from 'highlight.js/lib/common';
import 'highlight.js/styles/github.css';

const { proxy } = getCurrentInstance() as ComponentInternalInstance;

/** ============ Markdown 渲染(仅助手消息;html 关闭防注入,内容全部转义) ============ */
const md = new MarkdownIt({ html: false, linkify: true, breaks: true });

/** 代码高亮:未知语言或高亮失败时降级为转义文本 */
const highlightCode = (str: string, lang: string): string => {
  if (lang && hljs.getLanguage(lang)) {
    try {
      return hljs.highlight(str, { language: lang, ignoreIllegals: true }).value;
    } catch {
      // 降级为无高亮
    }
  }
  return md.utils.escapeHtml(str);
};

// 链接统一新窗口打开,避免点跳走丢掉会话
const defaultLinkOpen =
  md.renderer.rules.link_open || ((tokens, idx, options, _env, self) => self.renderToken(tokens, idx, options));
md.renderer.rules.link_open = (tokens, idx, options, env, self) => {
  tokens[idx].attrSet('target', '_blank');
  tokens[idx].attrSet('rel', 'noopener noreferrer');
  return defaultLinkOpen(tokens, idx, options, env, self);
};

// 代码块包一层头部(语言标签 + 复制按钮),复制由气泡容器事件委托统一处理
md.renderer.rules.fence = (tokens, idx) => {
  const token = tokens[idx];
  const lang = (token.info || '').trim().split(/\s+/)[0] || '';
  return (
    `<div class="md-code"><div class="md-code-head"><span class="md-code-lang">${md.utils.escapeHtml(lang || '代码')}</span>` +
    `<button type="button" class="md-copy-btn">复制代码</button></div>` +
    `<pre><code class="hljs">${highlightCode(token.content, lang)}</code></pre></div>`
  );
};

/** 渲染缓存:流式期间整个列表的 v-html 绑定都会重算,缓存避免旧消息重复解析 */
const mdCache = new Map<string, string>();
const renderMd = (src: string | undefined | null): string => {
  if (!src) return '';
  const cached = mdCache.get(src);
  if (cached !== undefined) return cached;
  const html = md.render(src);
  if (mdCache.size > 200) mdCache.clear();
  mdCache.set(src, html);
  return html;
};

/** 代码块复制(事件委托):点击 .md-copy-btn 时复制同代码块文本 */
const onMdCopyClick = async (e: MouseEvent) => {
  const btn = (e.target as HTMLElement).closest?.('.md-copy-btn') as HTMLElement | null;
  if (!btn) return;
  const code = btn.closest('.md-code')?.querySelector('pre code');
  try {
    await navigator.clipboard.writeText(code?.textContent ?? '');
    btn.textContent = '已复制';
  } catch {
    btn.textContent = '复制失败';
  }
  setTimeout(() => (btn.textContent = '复制代码'), 1500);
};

/** 本地消息视图:在服务端消息之上增加 typing 占位标志与第四步扩展元数据 */
type TeamTraceLine = {
  agentId?: number | string | null;
  agentName: string;
  role: string;
  content: string;
  totalTokens?: number | null;
  /** 流式状态:pending=并行占位, processing=进行中, done=已完成 */
  status?: 'pending' | 'processing' | 'done';
};
/** 引用溯源条目(后端 citations JSON 字段) */
type CitationItem = {
  index: number;
  fileName?: string;
  chunkIndex?: number | null;
  score?: number | null;
  snippet?: string;
  pageNo?: number;
  sheetName?: string;
};
/** 问数 SQL 执行明细(后端 extJson.sqlResults) */
type SqlResultItem = {
  tool: string;
  datasourceId?: number | string | null;
  datasource: string;
  sql: string;
  columns?: string[];
  rows?: unknown[][];
  rowCount: number;
  rowCapped?: boolean;
  truncated?: boolean;
  elapsedMs?: number;
  success?: boolean;
  error?: string;
};
/** 体检 E1:工作流节点时间线(流式实时态,与团队接力轨迹同模式;历史消息不保留) */
type WfTraceLine = {
  nodeId: string;
  nodeName: string;
  nodeType: string;
  status: 'processing' | 'done' | 'fail';
};
type ChatMessage = Omit<MessageVO, 'attachments' | 'citations'> & {
  typing?: boolean;
  /** P0:本轮运行 ID,用于链路下钻与断线续传 */
  runId?: string;
  structured?: boolean;
  teamTrace?: TeamTraceLine[];
  wfTrace?: WfTraceLine[];
  citations?: CitationItem[];
  sqlResults?: SqlResultItem[];
  /** 图片附件(服务端 attachments JSON 数组解析结果) */
  attachments?: ChatAttachment[];
  /** 乐观渲染:本地待发送图片预览 objectURL */
  previewThumbs?: string[];
};

/** 解析消息 extJson:结构化输出标记 + 团队协作轨迹 + 问数 SQL 明细;citations 解析引用溯源列表 */
const parseExt = (m: MessageVO): ChatMessage => {
  const { citations: _citations, attachments: _attachments, ...rest } = m;
  const out: ChatMessage = { ...rest };
  if (m.extJson) {
    try {
      const ext = JSON.parse(m.extJson);
      out.runId = typeof ext.runId === 'string' ? ext.runId : undefined;
      out.structured = ext.structured === true;
      out.teamTrace = Array.isArray(ext.teamTrace) && ext.teamTrace.length ? ext.teamTrace : undefined;
      out.sqlResults = Array.isArray(ext.sqlResults) && ext.sqlResults.length ? ext.sqlResults : undefined;
    } catch {
      // 忽略非 JSON 扩展
    }
  }
  if (m.citations) {
    try {
      const arr = JSON.parse(m.citations);
      if (Array.isArray(arr) && arr.length) out.citations = arr;
    } catch {
      // 忽略非法 JSON
    }
  }
  if (m.attachments) {
    try {
      const arr = JSON.parse(m.attachments);
      if (Array.isArray(arr) && arr.length) out.attachments = arr;
    } catch {
      // 忽略非法 JSON
    }
  }
  return out;
};

/** 结构化输出美化为缩进 JSON(非 JSON 内容原样返回) */
const prettyJson = (content: string): string => {
  try {
    return JSON.stringify(JSON.parse(content), null, 2);
  } catch {
    return content;
  }
};

const agentOptions = ref<AgentVO[]>([]);
const selectedAgentId = ref<string | number>('');
const sessions = ref<SessionVO[]>([]);
const sessionLoading = ref(false);
const sessionKeyword = ref('');
let searchTimer: ReturnType<typeof setTimeout> | null = null;
const currentSessionId = ref<string | number>('');
const currentAgentId = ref<string | number>('');
const currentAgentName = ref('');
const welcomeText = ref('');
const messages = ref<ChatMessage[]>([]);

/** P0:运行链路下钻与断线续传提示 */
const traceVisible = ref(false);
const traceRunId = ref('');
const reconnectHint = ref('');
const openRunTrace = (runId: string) => {
  traceRunId.value = runId;
  traceVisible.value = true;
};
const inputText = ref('');
const inputRef = ref();
const sending = ref(false);
const interrupting = ref(false);
const msgContainer = ref<HTMLElement>();
/** 消息分页:是否还有更早消息、是否正在加载更早消息 */
const hasMoreMessages = ref(false);
const loadingEarlier = ref(false);
/** 消息分页每页条数 */
const MESSAGE_PAGE_SIZE = 50;

/** 待发送图片:选中后立即上传,uploading/failed 时不允许发送 */
type PendingAttachment = {
  key: string;
  file: File;
  kind: 'image' | 'file';
  /** 图片本地预览 objectURL */
  preview?: string;
  status: 'uploading' | 'parsing' | 'ready' | 'failed';
  attachment?: ChatAttachment;
  error?: string;
  /** 文档解析状态轮询定时器 */
  pollTimer?: number;
};
const pendingAttachments = ref<PendingAttachment[]>([]);
const fileInputRef = ref<HTMLInputElement>();
/** 允许的图片 MIME 与扩展名(扩展名兜底,部分环境 file.type 可能为空) */
const IMAGE_MIME = ['image/png', 'image/jpeg'];
const IMAGE_EXT = ['png', 'jpg', 'jpeg'];
const FILE_EXT = ['pdf', 'docx', 'xlsx', 'csv', 'txt', 'md', 'json', 'html'];
const MAX_IMAGE_SIZE = 5 * 1024 * 1024;
const MAX_FILE_SIZE = 10 * 1024 * 1024;
const MAX_ATTACHMENT_COUNT = 4;

/** 当前选中 Agent 是否支持图片输入(拿不到信息按不支持处理) */
const visionEnabled = computed(() => agentOptions.value.find((a) => a.id === selectedAgentId.value)?.supportVision === '1');

/** 就绪(已上传并解析完成)的待发送附件 */
const readyAttachments = computed(() => pendingAttachments.value.filter((p) => p.status === 'ready'));
/** 存在上传中/解析中/失败的附件时给出提示并阻断发送 */
const pendingIssueTip = computed(() => {
  if (pendingAttachments.value.some((p) => p.status === 'failed')) return '有附件上传或解析失败,可重试或移除';
  if (pendingAttachments.value.some((p) => p.status === 'uploading')) return '有附件未上传完成,请稍候或重试';
  if (pendingAttachments.value.some((p) => p.status === 'parsing')) return '有文件正在解析,请稍候再发送';
  return '';
});
const canSend = computed(() => (!!inputText.value.trim() || readyAttachments.value.length > 0) && !sending.value && !pendingIssueTip.value);

/** 待发送附件状态文案 */
const pendingStatusText = (p: PendingAttachment) => {
  if (p.status === 'uploading') return '上传中…';
  if (p.status === 'parsing') return '解析中…';
  if (p.status === 'failed') return p.error || '失败';
  const att = p.attachment;
  const size = att?.size ? `${(att.size / 1024).toFixed(0)}KB` : '';
  if (att?.kind === 'file' && att.tokenCount) {
    return `已就绪 · 约 ${att.tokenCount} tokens${att.pageCount ? ` · ${att.pageCount} 页/表` : ''}`;
  }
  return size ? `已就绪 · ${size}` : '已就绪';
};

let scrollFrame = 0;
/** 是否跟随流式输出自动滚动(用户上滑即暂停跟随) */
const autoFollow = ref(true);
/** 用户离开底部期间累计的新内容条数(展示在回到底部按钮上) */
const newBelowCount = ref(0);
/** 滚动到底部:按帧合并请求;用户上滑阅读历史时不强制拉回,改为累计新消息提示 */
const scrollToBottom = (force = false) => {
  if (!force && !autoFollow.value) {
    newBelowCount.value++;
    return;
  }
  if (scrollFrame) return;
  scrollFrame = requestAnimationFrame(() => {
    scrollFrame = 0;
    if (msgContainer.value) {
      msgContainer.value.scrollTop = msgContainer.value.scrollHeight;
    }
  });
};

/** 消息区滚动监听:距底部 80px 内视为跟随状态,回到底部即清零新消息提示 */
const onMsgScroll = () => {
  const el = msgContainer.value;
  if (!el) return;
  const dist = el.scrollHeight - el.scrollTop - el.clientHeight;
  autoFollow.value = dist < 80;
  if (autoFollow.value) newBelowCount.value = 0;
};

/** 点击回到底部悬浮按钮 */
const jumpToBottom = () => {
  autoFollow.value = true;
  newBelowCount.value = 0;
  scrollToBottom(true);
};

const loadAgents = async () => {
  const res = await listPublishedAgent();
  agentOptions.value = res.data || [];
  if (agentOptions.value.length > 0 && !selectedAgentId.value) {
    selectedAgentId.value = agentOptions.value[0].id;
  }
};

const loadSessions = async () => {
  sessionLoading.value = true;
  try {
    const res = await listSession({ pageNum: 1, pageSize: 50, keyword: sessionKeyword.value.trim() || undefined });
    sessions.value = res.data?.rows || [];
  } finally {
    sessionLoading.value = false;
  }
};

/** 会话搜索输入防抖 */
const onSearchInput = () => {
  if (searchTimer) clearTimeout(searchTimer);
  searchTimer = setTimeout(() => loadSessions(), 300);
};

/** 当前 Agent 的推荐问题(JSON 数组解析,异常/非字符串项过滤) */
const currentSuggestions = computed<string[]>(() => {
  const raw = agentOptions.value.find((a) => a.id === currentAgentId.value)?.suggestedQuestions;
  if (!raw) return [];
  try {
    const arr = JSON.parse(raw);
    if (!Array.isArray(arr)) return [];
    return arr.filter((q: unknown) => typeof q === 'string' && q.trim()).slice(0, 6);
  } catch {
    return [];
  }
});

const openSession = async (s: SessionVO) => {
  // 切换会话:清空上一会话的待发送图片,避免误带到新会话
  clearPendingAttachments();
  currentSessionId.value = s.id;
  currentAgentId.value = s.agentId;
  currentAgentName.value = s.agentName || '';
  // 同步 Agent 下拉选中值:图片能力校验与附件上传归属均依赖 selectedAgentId
  selectedAgentId.value = s.agentId;
  const res = await listMessages(s.id, { limit: MESSAGE_PAGE_SIZE });
  // 快速连点会话时,慢返回的旧会话结果不得覆盖新会话
  if (String(s.id) !== String(currentSessionId.value)) return;
  const list = (res.data || []).map(parseExt);
  messages.value = list;
  hasMoreMessages.value = list.length >= MESSAGE_PAGE_SIZE;
  welcomeText.value = '';
  scrollToBottom(true);
  refreshUsage();
};

/** ============ UX-03 Token 用量实时反馈 ============ */
const sessionUsage = ref<SessionUsageBar | null>(null);

/** 千分位格式化;空值显示 —(接口失败不影响主对话流) */
const fmtTokens = (v?: number | null) => (v == null ? '—' : v.toLocaleString('en-US'));

/** 上下文剩余颜色:>50% 绿 / 20-50% 黄 / <20% 红 */
const usageBarColor = computed(() => {
  const p = sessionUsage.value?.contextRemainingPercent;
  if (p == null) return 'var(--el-color-success)';
  if (p > 50) return 'var(--el-color-success)';
  if (p >= 20) return 'var(--el-color-warning)';
  return 'var(--el-color-danger)';
});

/** 拉取会话用量;失败静默,保留旧值 */
const refreshUsage = async () => {
  const sid = currentSessionId.value;
  if (!sid) {
    sessionUsage.value = null;
    return;
  }
  try {
    const res = await getSessionUsage(sid);
    // 会话已切换则丢弃过期响应
    if (String(sid) === String(currentSessionId.value)) {
      sessionUsage.value = res.data || null;
    }
  } catch {
    // 接口失败不影响主对话流
  }
};

/** 兜底轮询:每 15s 刷新一次(后端有 15s 缓存,无额外压力) */
let usageTimer: ReturnType<typeof setInterval> | null = null;

/** 进行中的 SSE 流句柄:组件卸载时中止,避免回调写已销毁组件 */
let streamAbort: AbortController | null = null;

/** 加载更早消息:以当前最早消息 id 为游标向前翻页,并保持滚动位置不跳动 */
const loadEarlierMessages = async () => {
  const sessionId = currentSessionId.value;
  if (!sessionId || loadingEarlier.value || !hasMoreMessages.value) return;
  loadingEarlier.value = true;
  try {
    const beforeId = messages.value[0]?.id;
    const res = await listMessages(sessionId, { beforeId, limit: MESSAGE_PAGE_SIZE });
    // await 期间会话已切换:丢弃旧会话分页结果
    if (String(sessionId) !== String(currentSessionId.value)) return;
    const earlier = (res.data || []).map(parseExt);
    if (!earlier.length) {
      hasMoreMessages.value = false;
      return;
    }
    const container = msgContainer.value;
    const prevHeight = container?.scrollHeight || 0;
    const prevTop = container?.scrollTop || 0;
    messages.value = [...earlier, ...messages.value];
    hasMoreMessages.value = earlier.length >= MESSAGE_PAGE_SIZE;
    nextTick(() => {
      // 新增内容插入到顶部后,保持原先可视区域内容位置不变
      if (container) container.scrollTop = prevTop + (container.scrollHeight - prevHeight);
    });
  } catch {
    // 失败已由全局提示
  } finally {
    loadingEarlier.value = false;
  }
};

const handleNewSession = async () => {
  if (!selectedAgentId.value) return;
  // 新建会话:清空上一会话的待发送图片
  clearPendingAttachments();
  try {
    const res = await addSession(selectedAgentId.value);
    const agent = agentOptions.value.find((a) => a.id === selectedAgentId.value);
    currentSessionId.value = res.data!.id;
    currentAgentId.value = selectedAgentId.value;
    currentAgentName.value = res.data!.agentName || agent?.name || '';
    welcomeText.value = agent?.welcomeMsg || '会话已创建,请输入消息';
    messages.value = [];
    hasMoreMessages.value = false;
    sessionUsage.value = null;
    refreshUsage();
    await loadSessions();
    scrollToBottom(true);
  } catch (e) {
    // 失败已由全局提示
  }
};

/** 置顶/取消置顶会话 */
const handlePin = async (s: SessionVO) => {
  const next = s.pinned === '1' ? '0' : '1';
  try {
    await pinSession(s.id, next as '0' | '1');
    s.pinned = next;
    proxy?.$modal.msgSuccess(next === '1' ? '已置顶' : '已取消置顶');
    loadSessions();
  } catch (e) {
    // 失败已由全局提示
  }
};

/** 点击推荐问题直接发送 */
const handleSuggest = (q: string) => {
  if (sending.value) return;
  inputText.value = q;
  handleSend();
};

/** 按游标逐页拉取会话全量消息(导出用,避免只导出当前已加载的分页) */
const fetchAllMessages = async (sessionId: string | number): Promise<MessageVO[]> => {
  const all: MessageVO[] = [];
  let beforeId: string | number | undefined;
  for (;;) {
    const res = await listMessages(sessionId, { beforeId, limit: 200 });
    const page = res.data || [];
    if (!page.length) break;
    all.unshift(...page);
    if (page.length < 200 || all.length >= 2000) break;
    beforeId = page[0].id;
  }
  return all;
};

/** 导出当前会话为 Markdown 文件下载 */
const handleExport = async () => {
  if (!currentSessionId.value) return;
  const title = sessions.value.find((s) => s.id === currentSessionId.value)?.title || '会话导出';
  let list = messages.value;
  try {
    const all = await fetchAllMessages(currentSessionId.value);
    if (all.length) list = all.map(parseExt);
  } catch {
    // 拉取失败时退回当前已加载消息
  }
  if (!list.length) return;
  const lines: string[] = [
    `# ${currentAgentName.value || 'AI 会话'} · ${title}`,
    '',
    `> 导出时间:${new Date().toLocaleString()}`,
    ''
  ];
  for (const m of list) {
    lines.push(m.role === 'user' ? '## 用户' : '## 助手', '', m.content || '', '');
    if (m.attachments?.length) {
      lines.push(`(图片附件 ${m.attachments.length} 张)`, '');
    }
  }
  const blob = new Blob([lines.join('\n')], { type: 'text/markdown;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = `${title}.md`;
  a.click();
  URL.revokeObjectURL(url);
  proxy?.$modal.msgSuccess('已导出 Markdown');
};

/** 是否允许的图片(png/jpg:MIME 判断 + 扩展名兜底) */
const isAllowedImage = (file: File): boolean => {
  const type = (file.type || '').toLowerCase();
  if (IMAGE_MIME.includes(type)) return true;
  return IMAGE_EXT.includes(fileExtOf(file));
};

/** 是否允许的文档扩展名 */
const isAllowedFile = (file: File): boolean => FILE_EXT.includes(fileExtOf(file));

const fileExtOf = (file: File): string => {
  return file.name.includes('.') ? file.name.split('.').pop()!.toLowerCase() : '';
};

/** 上传单个待发送附件;文档上传成功后再轮询解析状态 */
const uploadPending = async (item: PendingAttachment) => {
  stopPoll(item);
  item.status = 'uploading';
  item.error = '';
  try {
    const res = await uploadChatAttachment(item.file, { agentId: selectedAgentId.value, sessionId: currentSessionId.value || undefined });
    item.attachment = res.data!;
    if (item.kind === 'file' && res.data?.parseStatus === '0') {
      item.status = 'parsing';
      startPoll(item);
    } else if (item.kind === 'file' && res.data?.parseStatus === '2') {
      item.status = 'failed';
      item.error = res.data?.failReason || '解析失败';
    } else {
      item.status = 'ready';
    }
  } catch (e: any) {
    item.status = 'failed';
    item.error = e?.message || '上传失败';
  }
};

/** 轮询文档解析状态:完成置 ready,失败置 failed */
const startPoll = (item: PendingAttachment) => {
  let attempts = 0;
  const tick = async () => {
    if (!item.attachment || item.status !== 'parsing') return;
    try {
      const res = await getChatAttachmentMeta(item.attachment.id);
      item.attachment = res.data!;
      if (res.data?.parseStatus === '1') {
        item.status = 'ready';
        stopPoll(item);
        return;
      }
      if (res.data?.parseStatus === '2') {
        item.status = 'failed';
        item.error = res.data?.failReason || '解析失败';
        stopPoll(item);
        return;
      }
    } catch {
      // 单次查询失败不终止,继续轮询
    }
    attempts += 1;
    if (attempts >= 60) {
      item.status = 'failed';
      item.error = '解析超时';
      stopPoll(item);
      return;
    }
    item.pollTimer = window.setTimeout(tick, 1500);
  };
  item.pollTimer = window.setTimeout(tick, 1500);
};

const stopPoll = (item: PendingAttachment) => {
  if (item.pollTimer) {
    window.clearTimeout(item.pollTimer);
    item.pollTimer = undefined;
  }
};

/** 选中附件加入待发送列表并立即上传(校验不合格的直接丢弃并提示) */
const addPendingAttachments = (files: File[]) => {
  for (const file of files) {
    const isImage = isAllowedImage(file);
    const isFile = isAllowedFile(file);
    if (!isImage && !isFile) {
      proxy?.$modal.msgWarning(`不支持的文件格式:${file.name}`);
      continue;
    }
    if (isImage && !visionEnabled.value) {
      proxy?.$modal.msgWarning(`当前模型未开启图片输入能力,已跳过图片:${file.name}`);
      continue;
    }
    if (isImage && file.size > MAX_IMAGE_SIZE) {
      proxy?.$modal.msgWarning(`单张图片不能超过 5MB:${file.name}`);
      continue;
    }
    if (!isImage && file.size > MAX_FILE_SIZE) {
      proxy?.$modal.msgWarning(`单个文件不能超过 10MB:${file.name}`);
      continue;
    }
    if (pendingAttachments.value.length >= MAX_ATTACHMENT_COUNT) {
      proxy?.$modal.msgWarning(`单条消息最多 ${MAX_ATTACHMENT_COUNT} 个附件`);
      break;
    }
    const item: PendingAttachment = {
      key: `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
      file,
      kind: isImage ? 'image' : 'file',
      preview: isImage ? URL.createObjectURL(file) : undefined,
      status: 'uploading'
    };
    pendingAttachments.value.push(item);
    uploadPending(item);
  }
};

const retryPending = (item: PendingAttachment) => {
  if (item.status === 'uploading' || item.status === 'parsing') return;
  uploadPending(item);
};

const removePending = (item: PendingAttachment) => {
  stopPoll(item);
  if (item.preview) URL.revokeObjectURL(item.preview);
  pendingAttachments.value = pendingAttachments.value.filter((p) => p.key !== item.key);
};

const clearPendingAttachments = () => {
  pendingAttachments.value.forEach((p) => {
    stopPoll(p);
    if (p.preview) URL.revokeObjectURL(p.preview);
  });
  pendingAttachments.value = [];
};

const triggerPick = () => {
  fileInputRef.value?.click();
};

const handleFileChange = (e: Event) => {
  const input = e.target as HTMLInputElement;
  const files = Array.from(input.files || []);
  input.value = '';
  if (files.length) addPendingAttachments(files);
};

/** 粘贴图片:读取剪贴板中的 image 项 */
const handlePaste = (e: ClipboardEvent) => {
  const items = e.clipboardData?.items;
  if (!items) return;
  const files: File[] = [];
  for (const it of Array.from(items)) {
    if (it.type.startsWith('image/')) {
      const f = it.getAsFile();
      if (f) files.push(f);
    }
  }
  if (!files.length) return;
  if (!visionEnabled.value) {
    proxy?.$modal.msgWarning('当前模型未开启图片输入能力');
    return;
  }
  e.preventDefault();
  addPendingAttachments(files);
};

/** 拖拽上传:文档始终允许,图片需模型支持 */
const handleDrop = (e: DragEvent) => {
  const files = Array.from(e.dataTransfer?.files || []);
  if (!files.length) return;
  e.preventDefault();
  addPendingAttachments(files);
};

/** 原图预览:鉴权拉取 objectURL 后弹窗展示 */
const previewVisible = ref(false);
const previewUrl = ref('');
const previewName = ref('');
const previewAttachment = async (att: ChatAttachment) => {
  try {
    const url = await fetchChatAttachmentObjectUrl(att.id);
    if (previewUrl.value) URL.revokeObjectURL(previewUrl.value);
    previewUrl.value = url;
    previewName.value = att.name;
    previewVisible.value = true;
  } catch (e) {
    proxy?.$modal.msgError('原图加载失败');
  }
};
const handlePreviewClosed = () => {
  if (previewUrl.value) {
    URL.revokeObjectURL(previewUrl.value);
    previewUrl.value = '';
  }
};

/** 下载/打开文件附件(鉴权拉取后触发浏览器下载) */
const openFileAttachment = async (att: ChatAttachment) => {
  try {
    const url = await fetchChatAttachmentObjectUrl(att.id);
    const a = document.createElement('a');
    a.href = url;
    a.download = att.name;
    a.click();
    setTimeout(() => URL.revokeObjectURL(url), 10000);
  } catch {
    proxy?.$modal.msgError('文件加载失败');
  }
};

/** 输入框回车发送:Enter/Ctrl+Enter 发送,Shift+Enter 换行;输入法组合态(候选词确认)不拦截 */
const onInputEnter = (e: KeyboardEvent) => {
  if (e.isComposing || e.shiftKey) return;
  e.preventDefault();
  handleSend();
};

/** 体检 E1:工作流节点类型标签 */
const wfTypeLabel = (t: string) =>
  (({ start: '开始', llm: '推理', tool: '工具', knowledge: '检索', condition: '条件', approval: '审批', end: '结束', loop: '循环', code: '代码', http: 'HTTP', parallel: '并行', join: '汇聚' })[t] || t || '节点');

/** 体检 E1:工作流时间线状态维护(发送与重新生成两处流式回调共用;按 nodeId 去重) */
const applyWfEvent = (botMsg: ChatMessage, kind: 'start' | 'done' | 'error',
                      nodeId: string, nodeName: string, nodeType: string) => {
  if (!botMsg.wfTrace) botMsg.wfTrace = [];
  let line = botMsg.wfTrace.find((w) => w.nodeId === nodeId);
  if (!line) {
    line = { nodeId, nodeName, nodeType, status: 'processing' };
    botMsg.wfTrace.push(line);
  }
  if (nodeName) line.nodeName = nodeName;
  line.status = kind === 'done' ? 'done' : kind === 'error' ? 'fail' : 'processing';
};

/** 体检 E1:工作流事件回调集(展开进 StreamCallbacks) */
const wfCallbacks = (botMsg: ChatMessage) => ({
  onWfStart: () => {
    scrollToBottom();
  },
  onWfNodeStart: (nodeId: string, nodeName: string, nodeType: string) => {
    applyWfEvent(botMsg, 'start', nodeId, nodeName, nodeType);
    scrollToBottom();
  },
  onWfNodeDone: (nodeId: string, nodeName: string, nodeType: string) => {
    applyWfEvent(botMsg, 'done', nodeId, nodeName, nodeType);
  },
  onWfNodeError: (nodeId: string, nodeName: string, nodeType: string) => {
    applyWfEvent(botMsg, 'error', nodeId, nodeName, nodeType);
  }
});

const handleSend = async () => {
  const content = inputText.value.trim();
  if (sending.value) return;
  const pendingTip = pendingIssueTip.value;
  if (pendingTip) {
    proxy?.$modal.msgWarning(pendingTip);
    return;
  }
  const ready = readyAttachments.value;
  if (!content && ready.length === 0) return;
  if (!currentSessionId.value && !selectedAgentId.value) {
    proxy?.$modal.msgWarning('请先选择 Agent');
    return;
  }
  sending.value = true;
  inputText.value = '';
  // 乐观渲染:用户消息立即上屏(带本地预览图),AI 气泡先以打字点动画等待首个增量
  const tmpUserId = `tmp-user-${Date.now()}`;
  const tmpBotId = `tmp-bot-${Date.now()}`;
  const previewThumbs = ready.filter((p) => p.kind === 'image' && p.preview).map((p) => p.preview!);
  const attachmentIds = ready.map((p) => String(p.attachment!.id));
  messages.value.push(
    { id: tmpUserId, role: 'user', sessionId: currentSessionId.value || '', content, previewThumbs },
    { id: tmpBotId, role: 'assistant', sessionId: currentSessionId.value || '', content: '', typing: true }
  );
  welcomeText.value = '';
  scrollToBottom(true);
  const baseBody = currentSessionId.value ? { sessionId: currentSessionId.value, content } : { agentId: selectedAgentId.value, content };
  // 无附件时不携带 attachmentIds,避免脏值
  const sendBody: ChatSendForm = attachmentIds.length ? { ...baseBody, attachmentIds } : baseBody;
  const applyResult = (data: ChatSendResult) => {
    // 流式期间用户已切换/新建会话:结果已由后端落库,不得污染当前消息列表
    if (String(data.sessionId) !== String(currentSessionId.value)) return;
    currentSessionId.value = data.sessionId;
    // 用服务端返回的两条消息替换临时占位
    const startIdx = messages.value.findIndex((m) => m.id === tmpUserId);
    if (startIdx > -1) {
      messages.value.splice(startIdx, 2, parseExt(data.userMessage), parseExt(data.assistantMessage));
    } else {
      messages.value.push(parseExt(data.userMessage), parseExt(data.assistantMessage));
    }
    clearPendingAttachments();
    const agent = agentOptions.value.find((a) => a.id === selectedAgentId.value);
    currentAgentName.value = currentAgentName.value || agent?.name || '';
    loadSessions();
    // 会话标题由模型异步生成,延迟刷新一次会话列表
    setTimeout(() => loadSessions(), 5000);
  };
  const rollback = () => {
    // 失败:撤回两条临时消息并恢复输入内容(保留待发送图片,不丢图)
    messages.value = messages.value.filter((m) => m.id !== tmpUserId && m.id !== tmpBotId);
    inputText.value = content;
  };
  try {
    let streamStarted = false;
    const botMsg = messages.value.find((m) => m.id === tmpBotId)!;
    streamAbort = new AbortController();
    const ok = await streamChat(sendBody, {
      onRunId: (rid) => {
        // 收到 runId 即视为流已建立,避免续传失败时误回退阻塞式重发造成重复落库
        streamStarted = true;
        botMsg.runId = rid;
      },
      onReconnect: (attempt) => {
        reconnectHint.value = `连接中断,正在恢复(${attempt}/3)…`;
      },
      onMeta: (sid) => {
        streamStarted = true;
        currentSessionId.value = sid;
      },
      onDelta: (delta) => {
        streamStarted = true;
        botMsg.typing = false;
        botMsg.content += delta;
        scrollToBottom();
      },
      onTeamAgentStart: (index, total, agentName, role) => {
        streamStarted = true;
        botMsg.typing = false;
        if (!botMsg.teamTrace) botMsg.teamTrace = [];
        // P2/C2:并行模式各棒 start 会乱序到达,按 index 占位再赋值,不能 push
        while (botMsg.teamTrace.length < total) {
          botMsg.teamTrace.push({ agentName: '', role: '', content: '', status: 'pending' });
        }
        botMsg.teamTrace[index] = { agentName, role, content: '', status: 'processing' };
        scrollToBottom();
      },
      onTeamAgentDelta: (index, text) => {
        const line = botMsg.teamTrace?.[index];
        if (line) {
          line.content += text;
          // 主 Agent(最后一棒)的增量同时作为对话正文展示
          if (line.role === 'leader') {
            botMsg.content += text;
          }
          scrollToBottom();
        }
      },
      onTeamAgentDone: (index, content, totalTokens) => {
        const line = botMsg.teamTrace?.[index];
        if (line) {
          line.content = content;
          line.totalTokens = totalTokens;
          line.status = 'done';
        }
      },
      ...wfCallbacks(botMsg),
      onDone: (data) => {
        reconnectHint.value = '';
        applyResult(data);
      },
      onUsage: () => refreshUsage(),
      onError: (msg) => {
        if (streamStarted && botMsg.content) {
          // 已有部分输出:保留内容并标注中断
          botMsg.typing = false;
          botMsg.content += '\n[生成中断]';
          proxy?.$modal.msgError(msg || '生成失败');
        }
      }
    }, streamAbort.signal);
    if (!ok && !streamStarted) {
      // 组件已卸载(流被中止):不再回退阻塞式重发,避免消息重复落库
      if (streamAbort.signal.aborted) return;
      // 流式不可用(网络/代理不支持 SSE),回退阻塞式发送
      const res = await sendChat(sendBody);
      applyResult(res.data!);
      refreshUsage();
    }
  } catch (e) {
    rollback();
  } finally {
    reconnectHint.value = '';
    sending.value = false;
    scrollToBottom();
  }
};

const handleInterrupt = async () => {
  if (!currentSessionId.value || interrupting.value) return;
  interrupting.value = true;
  try {
    const res = await interruptChat(currentSessionId.value);
    proxy?.$modal.msgSuccess(res.msg || '已发出中断指令');
  } catch (e) {
    // WARN(无进行中对话)已由全局拦截器提示
  } finally {
    interrupting.value = false;
  }
};

/** 格式化消息时间:今天 HH:mm / 昨天 HH:mm / MM-DD HH:mm / 跨年 YYYY-MM-DD HH:mm */
/** 后端统一输出北京时间裸串(yyyy-MM-dd HH:mm:ss),显式按 +08:00 解析,避免不同浏览器引擎对无时区串的解释差异 */
const parseServerTime = (time: string): Date => {
  if (/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}/.test(time)) {
    const d = new Date(time.replace(' ', 'T').slice(0, 19) + '+08:00');
    if (!Number.isNaN(d.getTime())) return d;
  }
  return new Date(time);
};

const formatMsgTime = (time: string): string => {
  const d = parseServerTime(time);
  if (Number.isNaN(d.getTime())) return '';
  const now = new Date();
  const pad = (n: number) => String(n).padStart(2, '0');
  const hm = `${pad(d.getHours())}:${pad(d.getMinutes())}`;
  const sameDay = d.toDateString() === now.toDateString();
  if (sameDay) return hm;
  const yesterday = new Date(now);
  yesterday.setDate(now.getDate() - 1);
  if (d.toDateString() === yesterday.toDateString()) return `昨天 ${hm}`;
  const md = `${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
  if (d.getFullYear() === now.getFullYear()) return `${md} ${hm}`;
  return `${d.getFullYear()}-${md} ${hm}`;
};

/** 格式化相对时间:刚刚 / x 分钟前 / x 小时前 / 昨天 HH:mm / MM-DD */
const formatRelativeTime = (time: string): string => {
  const d = parseServerTime(time);
  if (Number.isNaN(d.getTime())) return '';
  const now = new Date();
  const diff = now.getTime() - d.getTime();
  const pad = (n: number) => String(n).padStart(2, '0');
  if (diff < 60_000) return '刚刚';
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)} 分钟前`;
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)} 小时前`;
  const yesterday = new Date(now);
  yesterday.setDate(now.getDate() - 1);
  if (d.toDateString() === yesterday.toDateString()) return `昨天 ${pad(d.getHours())}:${pad(d.getMinutes())}`;
  if (d.getFullYear() === now.getFullYear()) return `${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
};

/** 复制消息内容到剪贴板 */
const handleCopy = async (m: ChatMessage) => {
  const text = m.structured ? prettyJson(m.content || '') : (m.content || '');
  if (!text) return;
  try {
    await navigator.clipboard.writeText(text);
    proxy?.$modal.msgSuccess('已复制到剪贴板');
  } catch {
    // 降级:使用 textarea + execCommand
    const ta = document.createElement('textarea');
    ta.value = text;
    ta.style.position = 'fixed';
    ta.style.opacity = '0';
    document.body.appendChild(ta);
    ta.select();
    try {
      document.execCommand('copy');
      proxy?.$modal.msgSuccess('已复制到剪贴板');
    } catch {
      proxy?.$modal.msgError('复制失败,请手动选择文本复制');
    }
    document.body.removeChild(ta);
  }
};

/** 修改用户消息:把内容回填到输入框,由用户编辑后重新发送 */
const handleEdit = (m: ChatMessage) => {
  if (!m.content) return;
  inputText.value = m.content;
  nextTick(() => inputRef.value?.focus());
};

/** 是否为消息列表末条(重新生成仅作用于最后一条助手回复) */
const isLastMessage = (m: ChatMessage) => {
  const list = messages.value;
  return list.length > 0 && list[list.length - 1].id === m.id;
};

/** 重新生成最后一条助手回复:移除旧回复,流式重建(后端同步删除原回复) */
const handleRegenerate = async () => {
  if (!currentSessionId.value || sending.value) return;
  sending.value = true;
  const lastAssistant = messages.value.toReversed().find((m) => m.role === 'assistant');
  if (lastAssistant) {
    messages.value = messages.value.filter((m) => m.id !== lastAssistant.id);
  }
  const tmpBotId = `tmp-bot-${Date.now()}`;
  messages.value.push({ id: tmpBotId, role: 'assistant', sessionId: currentSessionId.value, content: '', typing: true });
  scrollToBottom();
  const botMsg = messages.value.find((m) => m.id === tmpBotId)!;
  const replaceDone = (data: ChatSendResult) => {
    const idx = messages.value.findIndex((m) => m.id === tmpBotId);
    if (idx > -1) messages.value.splice(idx, 1, parseExt(data.assistantMessage));
    loadSessions();
    // 标题为异步生成,延迟刷新一次会话列表
    setTimeout(() => loadSessions(), 5000);
  };
  const fail = (msg?: string) => {
    if (botMsg.content) {
      botMsg.typing = false;
      botMsg.content += '\n[生成中断]';
    } else {
      messages.value = messages.value.filter((m) => m.id !== tmpBotId);
      // 恢复此前本地移除的旧回复,避免请求失败时旧回复永久消失
      if (lastAssistant) {
        messages.value = [...messages.value, lastAssistant];
      }
    }
    proxy?.$modal.msgError(msg || '重新生成失败');
  };
  try {
    let streamStarted = false;
    streamAbort = new AbortController();
    const ok = await streamRegenerate(currentSessionId.value, {
      onRunId: (rid) => {
        streamStarted = true;
        botMsg.runId = rid;
      },
      onReconnect: (attempt) => {
        reconnectHint.value = `连接中断,正在恢复(${attempt}/3)…`;
      },
      onDelta: (delta) => {
        streamStarted = true;
        botMsg.typing = false;
        botMsg.content += delta;
        scrollToBottom();
      },
      onTeamAgentStart: (index, total, agentName, role) => {
        streamStarted = true;
        botMsg.typing = false;
        if (!botMsg.teamTrace) botMsg.teamTrace = [];
        while (botMsg.teamTrace.length < total) {
          botMsg.teamTrace.push({ agentName: '', role: '', content: '', status: 'pending' });
        }
        botMsg.teamTrace[index] = { agentName, role, content: '', status: 'processing' };
        scrollToBottom();
      },
      onTeamAgentDelta: (index, text) => {
        const line = botMsg.teamTrace?.[index];
        if (line) {
          line.content += text;
          if (line.role === 'leader') botMsg.content += text;
          scrollToBottom();
        }
      },
      onTeamAgentDone: (index, content, totalTokens) => {
        const line = botMsg.teamTrace?.[index];
        if (line) {
          line.content = content;
          line.totalTokens = totalTokens;
          line.status = 'done';
        }
      },
      ...wfCallbacks(botMsg),
      onDone: (data) => {
        reconnectHint.value = '';
        replaceDone(data);
      },
      onUsage: () => refreshUsage(),
      onError: (msg) => {
        if (streamStarted && botMsg.content) {
          botMsg.typing = false;
          botMsg.content += '\n[生成中断]';
          proxy?.$modal.msgError(msg || '重新生成失败');
        } else {
          fail(msg);
        }
      }
    }, streamAbort.signal);
    if (!ok && !streamStarted) {
      // 组件已卸载(流被中止):不再回退阻塞式重发,避免消息重复落库
      if (streamAbort.signal.aborted) return;
      // 流式不可用,回退阻塞式重新生成
      const res = await regenerateChat(currentSessionId.value);
      replaceDone(res.data!);
      refreshUsage();
    }
  } catch (e) {
    fail();
  } finally {
    reconnectHint.value = '';
    sending.value = false;
    scrollToBottom();
  }
};

/** 是否为可撤回的用户消息(仅末轮:该消息之后至多一条助手回复) */
const isRecallable = (m: ChatMessage) => {
  const list = messages.value;
  const idx = list.findIndex((x) => x.id === m.id);
  if (idx < 0 || m.role !== 'user') return false;
  return list.length - idx <= 2 && list.slice(idx + 1).every((x) => x.role === 'assistant');
};

/** 撤回末轮用户消息:删除该消息及其后的助手回复 */
const handleRecall = async (m: ChatMessage) => {
  if (!currentSessionId.value || sending.value) return;
  try {
    await proxy?.$modal.confirm('确认撤回这条消息吗?其后的助手回复将一并删除');
    await recallMessage(m.id);
    const idx = messages.value.findIndex((x) => x.id === m.id);
    if (idx > -1) messages.value.splice(idx);
    proxy?.$modal.msgSuccess('已撤回');
    loadSessions();
  } catch (e) {
    // 用户取消或接口失败
  }
};

/** 点赞/点踩:重复点击同值取消,反馈数据供运营看板聚合满意度 */
const handleFeedback = async (m: ChatMessage, value: 'like' | 'dislike') => {
  const next = m.feedback === value ? '' : value;
  const prev = m.feedback;
  m.feedback = next;
  // 点赞或取消反馈时清空本地备注/标签,避免下次点踩带出旧内容
  if (next !== 'dislike') {
    m.feedbackComment = undefined;
    m.feedbackTags = undefined;
  }
  try {
    await feedbackMessage(m.id, next as 'like' | 'dislike' | '');
  } catch (e) {
    m.feedback = prev;
  }
};

/* ---------- UX-02 点踩反馈面板 ---------- */
const FEEDBACK_TAGS = ['不准确', '不完整', '格式差', '答非所问', '其他'];
/** 当前打开反馈面板的消息ID(空串表示未打开) */
const feedbackPanelMsgId = ref<string>('');
const feedbackForm = reactive<{ tags: string[]; comment: string }>({ tags: [], comment: '' });
const feedbackSaving = ref(false);

const openFeedbackPanel = (m: ChatMessage) => {
  if (feedbackPanelMsgId.value === m.id) {
    feedbackPanelMsgId.value = '';
    return;
  }
  feedbackForm.tags = m.feedbackTags ? m.feedbackTags.split(',').filter(Boolean) : [];
  feedbackForm.comment = m.feedbackComment || '';
  feedbackPanelMsgId.value = String(m.id);
};

const toggleFeedbackTag = (t: string) => {
  const idx = feedbackForm.tags.indexOf(t);
  if (idx > -1) feedbackForm.tags.splice(idx, 1);
  else feedbackForm.tags.push(t);
};

const submitFeedback = async (m: ChatMessage) => {
  feedbackSaving.value = true;
  try {
    await feedbackMessage(m.id, 'dislike', feedbackForm.comment.trim(), feedbackForm.tags);
    m.feedback = 'dislike';
    m.feedbackComment = feedbackForm.comment.trim() || undefined;
    m.feedbackTags = feedbackForm.tags.length ? feedbackForm.tags.join(',') : undefined;
    feedbackPanelMsgId.value = '';
    proxy?.$modal.msgSuccess('已提交反馈,感谢指正');
  } finally {
    feedbackSaving.value = false;
  }
};

const clearFeedback = async (m: ChatMessage) => {
  feedbackSaving.value = true;
  try {
    await feedbackMessage(m.id, '');
    m.feedback = undefined;
    m.feedbackComment = undefined;
    m.feedbackTags = undefined;
    feedbackPanelMsgId.value = '';
  } finally {
    feedbackSaving.value = false;
  }
};

/* ---------- UX-02 点踩转问数示例 ---------- */
const exampleDialog = reactive<{
  visible: boolean;
  saving: boolean;
  dsId: string;
  question: string;
  wrongSql: string;
  sqlContent: string;
}>({ visible: false, saving: false, dsId: '', question: '', wrongSql: '', sqlContent: '' });

const openExampleDialog = (m: ChatMessage) => {
  const r = m.sqlResults?.[0];
  if (!r?.datasourceId) {
    proxy?.$modal.msgWarning('该消息缺少数据源信息,无法转为示例');
    return;
  }
  // 取该消息前一条 user 消息作为问题(向前扫描,避开 oxlint 对 reverse 的误报)
  const idx = messages.value.findIndex((x) => x.id === m.id);
  let userMsg: ChatMessage | undefined;
  for (let i = idx - 1; i >= 0; i--) {
    if (messages.value[i].role === 'user') {
      userMsg = messages.value[i];
      break;
    }
  }
  exampleDialog.dsId = String(r.datasourceId);
  exampleDialog.question = (userMsg?.content || '').slice(0, 500);
  exampleDialog.wrongSql = r.sql || '';
  exampleDialog.sqlContent = '';
  exampleDialog.visible = true;
};

const submitExample = async () => {
  if (!exampleDialog.question.trim()) {
    proxy?.$modal.msgWarning('问题不能为空');
    return;
  }
  if (!exampleDialog.sqlContent.trim()) {
    proxy?.$modal.msgWarning('请填写正确的 SQL 语句');
    return;
  }
  exampleDialog.saving = true;
  try {
    await addDatasourceExample({
      datasourceId: exampleDialog.dsId,
      question: exampleDialog.question.trim(),
      sqlContent: exampleDialog.sqlContent.trim(),
      status: '0',
      remark: exampleDialog.wrongSql ? `由点踩反馈转入,原错误SQL: ${exampleDialog.wrongSql.slice(0, 200)}` : '由点踩反馈转入'
    });
    proxy?.$modal.msgSuccess('已保存到示例库');
    exampleDialog.visible = false;
  } finally {
    exampleDialog.saving = false;
  }
};

const handleRename = async (s: SessionVO) => {
  try {
    const { value } = await ElMessageBox.prompt('请输入新的会话名称', '重命名会话', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      inputValue: s.title,
      inputPattern: /^.{1,100}$/,
      inputErrorMessage: '名称须为 1-100 字符'
    });
    await renameSession(s.id, value);
    proxy?.$modal.msgSuccess('重命名成功');
    loadSessions();
  } catch (e) {
    // 用户取消
  }
};

const handleDeleteSession = async (s: SessionVO) => {
  try {
    await proxy?.$modal.confirm(`确认删除会话「${s.title}」吗?消息将一并删除`);
    await delSession(s.id);
    proxy?.$modal.msgSuccess('删除成功');
    if (s.id === currentSessionId.value) {
      clearPendingAttachments();
      currentSessionId.value = '';
      currentAgentName.value = '';
      messages.value = [];
      welcomeText.value = '';
    }
    loadSessions();
  } catch (e) {
    // 用户取消
  }
};

/* ---------- 问数:SQL 结果表格与图表渲染 ---------- */

/** 图表模式状态(key: 消息ID-结果序号,值: ''/bar/line/pie) */
const chartModes = reactive<Record<string, string>>({});
/** 用户手动选择标记:未手动选择时按结果特征自动推荐图表模式 */
const userPickedCharts = reactive<Record<string, boolean>>({});
const chartRefs: Record<string, HTMLElement | null> = {};
const chartInstances: Record<string, echarts.ECharts> = {};

const chartKey = (m: ChatMessage, ri: number) => `${m.id}-${ri}`;
const modeOf = (m: ChatMessage, ri: number) => {
  const key = chartKey(m, ri);
  if (userPickedCharts[key]) return chartModes[key] || '';
  return recommendChartMode(m.sqlResults?.[ri]);
};

/** 按结果特征自动推荐图表模式:时间/日期维度→折线,否则柱状;不适合绘图时回退表格 */
const recommendChartMode = (r?: SqlResultItem): string => {
  if (!r || r.success === false || !r.columns?.length || !r.rows?.length) return '';
  const { dimIdx, metricIdxs, rows } = chartData(r);
  if (!metricIdxs.length || rows.length < 2 || rows.length > 50) return '';
  const sample = rows.slice(0, 5).map((row) => String(row[dimIdx] ?? ''));
  const isTimeDim = sample.some((v) => /^\d{4}[-/]\d{1,2}/.test(v) || /^\d{2}:\d{2}/.test(v));
  return isTimeDim ? 'line' : 'bar';
};

const setChartMode = (m: ChatMessage, ri: number, mode: string) => {
  const key = chartKey(m, ri);
  userPickedCharts[key] = true;
  chartModes[key] = mode;
  if (mode) {
    nextTick(() => renderChart(key, m.sqlResults?.[ri]));
  }
};

/** 导出问数结果为 CSV(BOM + 引号转义,导出 UI 截断后的行) */
const exportSqlCsv = (m: ChatMessage, ri: number) => {
  const r = m.sqlResults?.[ri];
  if (!r?.columns?.length) return;
  const esc = (v: unknown) => {
    const s = v == null ? '' : String(v);
    return /[",\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
  };
  const lines = [r.columns.map(esc).join(',')];
  (r.rows || []).forEach((row) => lines.push((row as unknown[]).map(esc).join(',')));
  const blob = new Blob(['﻿' + lines.join('\n')], { type: 'text/csv;charset=utf-8' });
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = `问数结果_${r.datasource || 'data'}_${Date.now()}.csv`;
  a.click();
  URL.revokeObjectURL(a.href);
};

const setChartRef = (key: string, el: unknown) => {
  chartRefs[key] = el as HTMLElement | null;
};

/** el-table 行数据:数组行转列名索引键对象 */
const tableRows = (r: SqlResultItem) =>
  (r.rows || []).map((row) => Object.fromEntries((row as unknown[]).map((v, i) => [String(i), v])));

/** 图表数据探查:首个非数值列作维度,数值列(最多 4 个)作指标 */
const chartData = (r: SqlResultItem) => {
  const rows = (r.rows || []) as unknown[][];
  const columns = r.columns || [];
  const isNumCol = (i: number) => rows.some((row) => typeof row[i] === 'number' && !Number.isNaN(row[i] as number));
  let dimIdx = columns.findIndex((_, i) => !isNumCol(i));
  if (dimIdx < 0) dimIdx = 0;
  const metricIdxs: number[] = [];
  columns.forEach((_, i) => {
    if (i !== dimIdx && isNumCol(i) && metricIdxs.length < 4) metricIdxs.push(i);
  });
  return { rows, columns, dimIdx, metricIdxs };
};

const renderChart = (key: string, r?: SqlResultItem) => {
  const el = chartRefs[key];
  if (!el || !r?.columns?.length) return;
  chartInstances[key]?.dispose();
  const mode = userPickedCharts[key] ? chartModes[key] : recommendChartMode(r);
  const { rows, columns, dimIdx, metricIdxs } = chartData(r);
  if (!metricIdxs.length) {
    el.innerHTML = '<div class="chart-empty">结果中没有可用于绘图的数值列,请切换回表格查看</div>';
    return;
  }
  const labels = rows.map((row) => String(row[dimIdx] ?? '-'));
  const textColor = getComputedStyle(document.documentElement).getPropertyValue('--el-text-color-primary').trim() || '#303133';
  const axisColor = getComputedStyle(document.documentElement).getPropertyValue('--el-border-color').trim() || '#dcdfe6';
  const textStyle = { color: textColor };
  let option: echarts.EChartsOption;
  if (mode === 'pie') {
    const mi = metricIdxs[0];
    option = {
      textStyle,
      tooltip: { trigger: 'item' },
      legend: { bottom: 0, type: 'scroll' },
      series: [
        {
          type: 'pie',
          radius: ['32%', '62%'],
          top: -10,
          data: rows.slice(0, 10).map((row, i) => ({
            name: `${labels[i] || '项' + (i + 1)}`,
            value: Number(row[mi]) || 0
          }))
        }
      ]
    };
  } else {
    option = {
      textStyle,
      tooltip: { trigger: 'axis' },
      legend: { bottom: 0, type: 'scroll' },
      grid: { left: 8, right: 8, top: 16, bottom: 36, containLabel: true },
      xAxis: { type: 'category', data: labels, axisLine: { lineStyle: { color: axisColor } } },
      yAxis: { type: 'value', splitLine: { lineStyle: { color: axisColor } } },
      series: metricIdxs.map((mi) => ({
        name: columns[mi],
        type: mode === 'line' ? 'line' : 'bar',
        data: rows.map((row) => (typeof row[mi] === 'number' ? row[mi] : null))
      }))
    };
  }
  const chart = echarts.init(el);
  chart.setOption(option);
  chartInstances[key] = chart;
};

/** 新问数结果到达后自动渲染推荐图表(用户手动选择过的卡片按其选择渲染) */
watch(
  () => messages.value.map((m) => m.id + ':' + (m.sqlResults?.length ?? 0)).join('|'),
  () => {
    nextTick(() => {
      messages.value.forEach((m) => {
        m.sqlResults?.forEach((r, ri) => {
          const key = chartKey(m, ri);
          if (modeOf(m, ri) && chartRefs[key]) renderChart(key, r);
        });
      });
    });
  }
);

onMounted(async () => {
  await Promise.all([loadAgents(), loadSessions()]);
  // UX-03 兜底轮询:15s 刷新会话用量(SSE usage 事件已做实时刷新)
  usageTimer = setInterval(() => {
    if (currentSessionId.value && !sending.value) refreshUsage();
  }, 15000);
});

onBeforeUnmount(() => {
  // 中止进行中的 SSE 流,避免回调继续写已销毁组件
  streamAbort?.abort();
  // 释放待发送附件与原图预览创建的 objectURL,避免内存泄漏
  clearPendingAttachments();
  if (previewUrl.value) URL.revokeObjectURL(previewUrl.value);
  Object.values(chartInstances).forEach((c) => c?.dispose());
  if (usageTimer) clearInterval(usageTimer);
});
</script>

<style lang="scss" scoped>
.agent-chat-page {
  /* 覆盖 app-container 的上下内边距:聊天页自身满高布局,避免发送按钮被挤出视口 */
  padding: 0 12px;
  height: calc(100vh - 84px);

  @keyframes typing-bounce {
    0%,
    60%,
    100% {
      transform: translateY(0);
      opacity: 0.4;
    }

    30% {
      transform: translateY(-4px);
      opacity: 1;
    }
  }

  .chat-layout {
    display: flex;
    gap: 10px;
    height: 100%;
  }

  .session-panel {
    width: 280px;
    flex-shrink: 0;
    background: var(--app-surface-bg);
    border: 1px solid var(--app-surface-border);
    border-radius: var(--app-radius-lg);
    box-shadow: var(--app-shadow-sm);
    display: flex;
    flex-direction: column;
    overflow: hidden;

    .session-header {
      padding: 12px;
      border-bottom: 1px solid var(--el-border-color-lighter);
      display: flex;
      flex-direction: column;
      gap: 10px;
    }

    .session-title {
      .pin-flag {
        color: var(--el-color-warning);
        margin-right: 4px;
        vertical-align: -2px;
      }
    }

    .session-list {
      flex: 1;
      overflow-y: auto;
      padding: 8px;
    }

    .session-item {
      position: relative;
      padding: 10px 12px;
      border-radius: 6px;
      cursor: pointer;
      transition: background 0.2s;

      &:hover {
        background: var(--el-fill-color-light);

        .session-ops {
          opacity: 1;
        }
      }

      &.active {
        background: var(--el-color-primary-light-9);

        .session-title {
          color: var(--el-color-primary);
        }
      }

      .session-title {
        font-size: 14px;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
        padding-right: 56px;
      }

      .session-meta {
        margin-top: 4px;
        font-size: 12px;
        color: var(--el-text-color-secondary);
        display: flex;
        gap: 10px;
        align-items: center;

        .session-time {
          margin-left: auto;
          color: var(--el-text-color-placeholder);
          font-size: 11px;
        }
      }

      .session-ops {
        position: absolute;
        right: 8px;
        top: 6px;
        opacity: 0;
        transition: opacity 0.2s;
      }
    }
  }

  .chat-panel {
    flex: 1;
    background: var(--app-surface-bg);
    border: 1px solid var(--app-surface-border);
    border-radius: var(--app-radius-lg);
    box-shadow: var(--app-shadow-sm);
    display: flex;
    flex-direction: column;
    overflow: hidden;
    position: relative;

    /* 用户上滑阅读历史时的"回到底部"悬浮按钮 */
    .back-bottom-btn {
      position: absolute;
      bottom: 190px;
      right: 20px;
      z-index: 6;
      display: flex;
      align-items: center;
      gap: 4px;
      padding: 6px 12px;
      border: 1px solid var(--el-border-color-light);
      border-radius: 16px;
      background: var(--el-bg-color);
      color: var(--el-text-color-regular);
      font-size: 12px;
      cursor: pointer;
      box-shadow: var(--el-box-shadow-light);

      &:hover {
        color: var(--el-color-primary);
        border-color: var(--el-color-primary-light-5);
      }
    }

    .chat-header {
      padding: 12px 16px;
      border-bottom: 1px solid var(--el-border-color-lighter);
      display: flex;
      align-items: center;
      gap: 10px;

      .export-btn {
        margin-left: auto;
      }

      .chat-title {
        font-weight: 600;
      }
    }

    /* UX-03 用量吸顶栏:紧凑单行,点击弹明细 */
    .usage-bar {
      padding: 6px 16px;
      border-bottom: 1px solid var(--el-border-color-lighter);
      font-size: 12px;
      color: var(--el-text-color-secondary);

      .usage-summary {
        display: flex;
        align-items: center;
        gap: 10px;
        cursor: pointer;
        width: fit-content;

        &:hover {
          color: var(--el-color-primary);
        }
      }

      .usage-round {
        color: var(--el-color-primary);
      }

      .usage-progress {
        width: 120px;
      }

      .usage-remain {
        font-weight: 600;
      }

      .usage-warn {
        color: var(--el-color-danger);
      }
    }

    .usage-detail {
      font-size: 12px;
      line-height: 1.9;

      .usage-cost-empty {
        color: var(--el-text-color-placeholder);
      }
    }

    .suggest-chips {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
      padding: 4px 16px 12px;

      .suggest-chip {
        max-width: 100%;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }
    }

    .message-list {
      flex: 1;
      overflow-y: auto;
      padding: 16px;
      display: flex;
      flex-direction: column;
      gap: 14px;
      background: var(--app-surface-bg);

      .load-earlier {
        display: flex;
        justify-content: center;
        padding-bottom: 4px;
      }

      .msg-row {
        display: flex;

        &.user {
          justify-content: flex-end;
        }

        &.assistant {
          justify-content: flex-start;
        }

        .bubble {
          max-width: 72%;
          padding: 10px 14px;
          border-radius: var(--app-radius-lg);
          font-size: 14px;
          line-height: 1.6;
          white-space: pre-wrap;
          word-break: break-word;
        }

        .user-bubble {
          /* WorkBuddy 式用户气泡:浅 accent 软底 + 正常文字色,右上角收口 */
          background: var(--app-accent-soft);
          border: 1px solid var(--app-accent-border, rgba(64, 158, 255, 0.16));
          color: var(--app-text-title);
          border-radius: var(--app-radius-lg);
          border-top-right-radius: 4px;
          /* 最小宽度保证操作行(时间/复制/修改/撤回)完整显示且留有余量 */
          min-width: 180px;
        }

        /* 助手消息:系统面板色气泡盒(仅包裹内容,不含操作按钮) */
        .assistant-bubble {
          background: var(--app-surface-bg);
          border: 1px solid var(--app-surface-border);
          box-shadow: var(--app-shadow-sm);
          border-top-left-radius: 4px;

          &.welcome {
            color: var(--app-text-muted);
            font-style: italic;
          }
        }

        /* 助手消息放宽阅读宽度 */
        &.assistant .bubble,
        &.assistant .bubble-wrap {
          max-width: 85%;
        }

        .typing-dots {
          display: inline-flex;
          align-items: center;
          gap: 4px;
          padding: 3px 2px;

          i {
            width: 6px;
            height: 6px;
            border-radius: 50%;
            background: var(--el-text-color-secondary);
            animation: typing-bounce 1.2s infinite ease-in-out;

            &:nth-child(2) {
              animation-delay: 0.15s;
            }

            &:nth-child(3) {
              animation-delay: 0.3s;
            }
          }
        }

        .bubble-wrap {
          display: flex;
          flex-direction: column;
          gap: 6px;
          max-width: 72%;

          .bubble {
            max-width: 100%;
          }
        }

        .msg-thumbs {
          display: flex;
          flex-wrap: wrap;
          gap: 6px;
          margin-bottom: 6px;

          .thumb-img {
            max-width: 180px;
            max-height: 180px;
            border-radius: 6px;
            cursor: pointer;
            object-fit: cover;
          }

          .file-chip {
            display: inline-flex;
            align-items: center;
            gap: 6px;
            max-width: 240px;
            padding: 6px 10px;
            border-radius: 6px;
            border: 1px solid var(--el-border-color-lighter);
            background: var(--el-fill-color-lighter);
            cursor: pointer;
            font-size: 12px;
            color: var(--el-text-color-primary);

            .file-chip-icon {
              flex: none;
              color: var(--el-color-primary);
            }

            .file-chip-name {
              overflow: hidden;
              text-overflow: ellipsis;
              white-space: nowrap;
            }
          }
        }

        .json-block {
          margin: 0;
          font-family: 'JetBrains Mono', Consolas, Menlo, monospace;
          font-size: 12.5px;
          line-height: 1.6;
          white-space: pre-wrap;
          word-break: break-word;
        }

        /* 助手消息 Markdown 正文(v-html 内容需 :deep 穿透),紧凑行距 */
        .md-body {
          font-size: 14px;
          line-height: 1.55;
          word-break: break-word;

          :deep(p) {
            margin: 0 0 6px;

            &:last-child {
              margin-bottom: 0;
            }
          }

          :deep(h1, h2, h3, h4, h5, h6) {
            margin: 10px 0 5px;
            line-height: 1.35;
            font-weight: 600;
          }

          :deep(h1) {
            font-size: 17.5px;
          }

          :deep(h2) {
            font-size: 16px;
          }

          :deep(h3) {
            font-size: 14.5px;
          }

          :deep(h4, h5, h6) {
            font-size: 14px;
          }

          :deep(ul, ol) {
            margin: 2px 0 6px;
            padding-left: 20px;
          }

          :deep(li) {
            margin: 1px 0;
          }

          :deep(li > p) {
            margin: 0;
          }

          :deep(blockquote) {
            margin: 6px 0;
            padding: 3px 10px;
            border-left: 3px solid var(--el-color-primary-light-5);
            background: var(--el-fill-color-light);
            color: var(--el-text-color-regular);
            border-radius: 2px;
          }

          :deep(a) {
            color: var(--el-color-primary);
            text-decoration: none;

            &:hover {
              text-decoration: underline;
            }
          }

          :deep(hr) {
            border: none;
            border-top: 1px solid var(--el-border-color-lighter);
            margin: 8px 0;
          }

          :deep(table) {
            border-collapse: collapse;
            margin: 6px 0;
            max-width: 100%;
            display: block;
            overflow-x: auto;

            th,
            td {
              border: 1px solid var(--el-border-color-lighter);
              padding: 4px 9px;
              font-size: 13px;
            }

            th {
              background: var(--el-fill-color-light);
              font-weight: 600;
            }
          }

          :deep(code) {
            font-family: 'JetBrains Mono', Consolas, Menlo, monospace;
            font-size: 12.5px;
            background: var(--el-fill-color);
            padding: 1.5px 5px;
            border-radius: 4px;
          }

          :deep(img) {
            max-width: 100%;
            border-radius: 6px;
          }

          /* 围栏代码块(语言标签 + 复制按钮) */
          :deep(.md-code) {
            margin: 8px 0;
            border: 1px solid var(--el-border-color-lighter);
            border-radius: 8px;
            overflow: hidden;
            background: #f6f8fa;

            .md-code-head {
              display: flex;
              justify-content: space-between;
              align-items: center;
              padding: 4px 12px;
              background: var(--el-fill-color-light);
              border-bottom: 1px solid var(--el-border-color-lighter);

              .md-code-lang {
                font-size: 12px;
                color: var(--el-text-color-secondary);
              }

              .md-copy-btn {
                border: none;
                background: transparent;
                cursor: pointer;
                font-size: 12px;
                color: var(--el-color-primary);
                padding: 2px 4px;

                &:hover {
                  text-decoration: underline;
                }
              }
            }

            pre {
              margin: 0;
              padding: 10px 12px;
              overflow-x: auto;

              code {
                display: block;
                padding: 0;
                background: transparent;
                line-height: 1.6;
                white-space: pre;
              }
            }
          }
        }

        .structured-tag {
          display: flex;
          justify-content: flex-start;
        }

        .citation-box {
          background: var(--app-elevated-soft-bg);
          border: 1px solid var(--app-surface-border);
          border-radius: var(--app-radius-md);
          padding: 8px 12px;

          .citation-title {
            font-size: 12px;
            font-weight: 600;
            color: var(--el-text-color-secondary);
            margin-bottom: 6px;
          }

          .citation-item {
            display: flex;
            align-items: center;
            gap: 6px;
            font-size: 12.5px;
            padding: 2px 0;

            .citation-name {
              color: var(--el-text-color-regular);
              overflow: hidden;
              text-overflow: ellipsis;
              white-space: nowrap;
            }

            .citation-score {
              color: var(--el-text-color-secondary);
              font-variant-numeric: tabular-nums;
            }

            .citation-eye {
              color: var(--el-color-primary);
              cursor: pointer;
              flex-shrink: 0;
            }
          }
        }

        .sql-results {
          display: flex;
          flex-direction: column;
          gap: 8px;
          max-width: 92%;

          .sql-card {
            background: var(--app-surface-bg);
            border: 1px solid var(--app-surface-border);
            border-radius: var(--app-radius-md);
            padding: 10px 12px;

            &.failed {
              border-color: var(--el-color-danger-light-5);
            }

            .sql-head {
              display: flex;
              align-items: center;
              gap: 8px;
              flex-wrap: wrap;

              .sql-ds {
                font-weight: 600;
                font-size: 13px;
              }

              .sql-meta {
                font-size: 12px;
                color: var(--el-text-color-secondary);
                font-variant-numeric: tabular-nums;
              }

              .sql-actions {
                margin-left: auto;
              }
            }

            .sql-text {
              margin: 8px 0;
              padding: 8px 10px;
              background: var(--el-fill-color-light);
              border-radius: 6px;
              font-family: var(--el-font-family-mono, monospace);
              font-size: 12px;
              line-height: 1.5;
              white-space: pre-wrap;
              word-break: break-all;
              color: var(--el-text-color-regular);
            }

            .sql-error {
              color: var(--el-color-danger);
              font-size: 12px;
              line-height: 1.6;
            }

            .sql-cap {
              margin-top: 6px;
              font-size: 12px;
              color: var(--el-text-color-secondary);
            }

            .sql-chart {
              .chart-canvas {
                height: 260px;
                width: 100%;
              }

              .chart-empty {
                height: 80px;
                display: flex;
                align-items: center;
                justify-content: center;
                color: var(--el-text-color-secondary);
                font-size: 13px;
              }
            }
          }
        }

        .msg-ops {
          display: flex;
          align-items: center;
          gap: 3px;
          /* 助手消息操作行:靠左(重新生成/复制/点赞/踩/时间) */
          justify-content: flex-start;
          transition: opacity 0.2s ease;

          /* 行内时间标签:弱化展示,与按钮同行 */
          .op-time {
            font-size: 11px;
            color: var(--app-text-muted);
            opacity: 0.75;
            margin: 0 4px 0 8px;
            font-variant-numeric: tabular-nums;
            user-select: none;
            white-space: nowrap;
          }

          .op-btn {
            display: inline-flex;
            align-items: center;
            justify-content: center;
            width: 26px;
            height: 26px;
            padding: 0;
            border: none;
            border-radius: 9px;
            background: transparent;
            color: var(--app-text-muted);
            cursor: pointer;
            transition:
              background-color 0.2s ease,
              color 0.2s ease;

            svg {
              width: 14px;
              height: 14px;
              fill: currentColor;
              display: block;
            }

            &:hover {
              background: var(--app-accent-soft);
              color: var(--app-accent-strong);
            }

            &:disabled {
              opacity: 0.4;
              cursor: not-allowed;
              background: transparent;
              color: var(--app-text-muted);
            }

            &.is-active-accent {
              background: var(--app-accent-soft);
              color: var(--app-accent-strong);
            }

            &.is-active-danger {
              background: var(--el-color-danger-light-9);
              color: var(--el-color-danger);
            }

            &.is-active-danger:hover {
              background: var(--el-color-danger-light-8);
              color: var(--el-color-danger);
            }
          }

          &.user-ops {
            /* 用户消息操作行:靠右(时间/复制/修改/撤回) */
            justify-content: flex-end;
          }
        }

        .team-trace {
          background: var(--app-elevated-soft-bg);
          border: 1px solid var(--app-surface-border);
          border-radius: var(--app-radius-md);
          padding: 10px 14px 2px 6px;

          .trace-title {
            font-size: 12px;
            font-weight: 600;
            color: var(--el-text-color-secondary);
            margin-bottom: 8px;
          }

          .trace-timeline {
            padding-left: 2px;

            :deep(.el-timeline-item__content) {
              padding-bottom: 14px;
            }

            .trace-head {
              display: flex;
              align-items: center;
              gap: 8px;

              .trace-agent {
                font-weight: 600;
                font-size: 13px;
              }

              .trace-tokens {
                font-size: 12px;
                color: var(--el-text-color-secondary);
              }

              .trace-processing {
                font-size: 12px;
                color: var(--el-color-primary);
                margin-left: 6px;
              }

              .dot-flash {
                animation: dot-blink 1.2s infinite;
              }
            }

            .trace-content {
              margin-top: 4px;
              font-size: 13px;
              line-height: 1.6;
              white-space: pre-wrap;
              word-break: break-word;
              color: var(--el-text-color-regular);

              &.trace-loading {
                color: var(--el-text-color-secondary);
              }
            }

            .typing-dots {
              display: inline-block;

              span {
                display: inline-block;
                width: 5px;
                height: 5px;
                margin: 0 1px;
                border-radius: 50%;
                background: var(--el-color-primary);
                animation: typing-bounce 1.2s infinite ease-in-out;

                &:nth-child(2) {
                  animation-delay: 0.2s;
                }

                &:nth-child(3) {
                  animation-delay: 0.4s;
                }
              }
            }

            .cursor-blink {
              display: inline-block;
              color: var(--el-color-primary);
              animation: cursor-blink 1s infinite;
            }
          }
        }
      }
    }

    .input-area {
      border-top: 1px solid var(--el-border-color-lighter);
      padding: 12px;

      .pending-images {
        display: flex;
        flex-wrap: wrap;
        gap: 8px;
        margin-bottom: 8px;

        .pending-item {
          position: relative;
          width: 72px;
          height: 72px;
          border-radius: 6px;
          overflow: hidden;
          border: 1px solid var(--el-border-color-lighter);

          img.pending-thumb {
            width: 100%;
            height: 100%;
            object-fit: cover;
            display: block;
          }

          .pending-file {
            display: flex;
            align-items: center;
            gap: 8px;
            width: 100%;
            height: 100%;
            padding: 8px;
            box-sizing: border-box;
            background: var(--el-fill-color-lighter);

            .pending-file-icon {
              font-size: 24px;
              color: var(--el-color-primary);
              flex: none;
            }

            .pending-file-meta {
              min-width: 0;
              flex: 1;
            }

            .pending-file-name {
              font-size: 12px;
              line-height: 16px;
              color: var(--el-text-color-primary);
              overflow: hidden;
              text-overflow: ellipsis;
              white-space: nowrap;
            }

            .pending-file-sub {
              margin-top: 2px;
              font-size: 11px;
              line-height: 15px;
              color: var(--el-text-color-secondary);
              overflow: hidden;
              text-overflow: ellipsis;
              white-space: nowrap;
            }
          }

          &.file {
            width: 220px;
            height: auto;
            min-height: 64px;
          }

          .pending-mask {
            position: absolute;
            inset: 0;
            display: flex;
            align-items: center;
            justify-content: center;
            font-size: 12px;
            color: #fff;
            background: rgba(0, 0, 0, 0.45);

            &.failed {
              background: var(--el-color-danger);
            }
          }

          .pending-ops {
            position: absolute;
            top: 2px;
            right: 2px;
            display: flex;
            gap: 4px;

            .pending-op {
              color: #fff;
              cursor: pointer;
              background: rgba(0, 0, 0, 0.45);
              border-radius: 50%;
              padding: 2px;
              font-size: 12px;
            }
          }
        }
      }

      .pending-tip {
        margin-top: 6px;
        font-size: 12px;
        color: var(--el-color-warning);
      }

      .hidden-file {
        display: none;
      }

      .input-actions {
        margin-top: 8px;
        display: flex;
        justify-content: space-between;
        align-items: center;

        .action-buttons {
          display: flex;
          gap: 10px;
          align-items: center;
        }

        .attach-btn-wrap {
          display: inline-flex;
        }

        .attach-btn {
          margin-left: 0;
        }

        .send-tip {
          font-size: 12px;
          color: var(--el-text-color-secondary);
        }
      }
    }
  }
}

/* 原图预览弹窗内容(弹窗 teleport 到 body,需顶层选择器) */
.preview-img {
  display: block;
  max-width: 100%;
  max-height: 70vh;
  margin: 0 auto;
}

/* 团队接力流式动画 */
@keyframes dot-blink {
  0%, 60%, 100% { opacity: 0.3; }
  30% { opacity: 1; }
}

@keyframes typing-bounce {
  0%, 60%, 100% { transform: translateY(0); opacity: 0.4; }
  30% { transform: translateY(-4px); opacity: 1; }
}

@keyframes cursor-blink {
  0%, 50% { opacity: 1; }
  51%, 100% { opacity: 0; }
}

/* P0:断线续传进行中的提示条 */
.reconnect-hint {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 14px;
  font-size: 12px;
  color: var(--el-color-warning);
  background: var(--el-color-warning-light-9);
  border-bottom: 1px solid var(--el-border-color-lighter);
  .hint-dot {
    width: 6px;
    height: 6px;
    border-radius: 50%;
    background: var(--el-color-warning);
    animation: reconnect-pulse 1.2s ease-in-out infinite;
  }
}
@keyframes reconnect-pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.3; }
}
</style>
