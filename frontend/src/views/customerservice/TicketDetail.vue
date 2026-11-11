<script setup lang="ts">
/**
 * 工单详情(C1):状态流转操作区 + 事件时间轴。
 * 由工作台路由进入,props 传 ticketId;transitionTicket 统一提交
 * hold/resume/resolve/close/reopen,后端校验七态跃迁表。
 * 扩展点:语音通话录音回放入口(通话详情在 Calls.vue,此处按 ticketId 串联)。
 */
import type { CsTicketEvent } from '@/api/customerservice';

defineProps<{ events: CsTicketEvent[] }>();

const statusText = (item: CsTicketEvent) => `${item.fromStatus ?? '开始'} → ${item.toStatus ?? ''}`;
</script>

<template>
  <div class="cs-events">
    <el-empty v-if="!events.length" description="暂无流转记录" :image-size="60" />
    <el-timeline v-else>
      <el-timeline-item
        v-for="item in events"
        :key="item.id"
        :timestamp="item.createTime ?? ''"
        placement="top"
      >
        <div class="cs-event-title">{{ item.eventType }} · {{ item.actorType }}</div>
        <div class="cs-event-body">{{ statusText(item) }}</div>
        <div v-if="item.note" class="cs-event-note">{{ item.note }}</div>
      </el-timeline-item>
    </el-timeline>
  </div>
</template>

<style scoped>
.cs-events {
  padding: 8px 4px;
}
.cs-event-title {
  font-weight: 500;
}
.cs-event-body,
.cs-event-note {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
