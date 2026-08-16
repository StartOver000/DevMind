<script setup>
/**
 * 快速上手引导（产品审视 P1-2"10 分钟闭环"落地）：
 * 新用户首次进入展示 3 步闭环（建库传文档 → 问它/生成流程 → 看得见每一步），
 * 可关闭并记忆（localStorage）。让"业务人员"第一眼知道怎么开始，而不是面对空白页。
 */
import { ref } from 'vue';

const KEY = 'devmind_quickstart_dismissed';
const visible = ref(!localStorage.getItem(KEY));

function dismiss() {
  visible.value = false;
  localStorage.setItem(KEY, '1');
}
</script>

<template>
  <div v-if="visible" class="quickstart">
    <div class="qs-head">
      <b>👋 3 步开始，让 AI 替你干活</b>
      <button class="qs-close" @click="dismiss" title="关闭">✕</button>
    </div>
    <div class="qs-steps">
      <a href="#/kb" class="qs-step">
        <span class="qs-no">1</span>
        <b>建知识库，上传你的文档</b>
        <span>上传资料后，AI 才能基于它们回答你的问题</span>
      </a>
      <a href="#/chat" class="qs-step">
        <span class="qs-no">2</span>
        <b>问它，或说句话让 AI 跑流程</b>
        <span>问答直接问；流程用大白话描述需求，AI 帮你排好步骤。想调公司系统？生成前点「🧭 能力盘点」看能调什么</span>
      </a>
      <a href="#/workflows" class="qs-step">
        <span class="qs-no">3</span>
        <b>看得见每一步</b>
        <span>执行轨迹、人工审批、一键沉淀技能——机器干活，人把关</span>
      </a>
    </div>
    <div class="qs-foot">
      💡 想 10 分钟体验完整闭环？建一个知识库上传文档，然后在「流程」说一句"查一下文档，总结要点"试试。
      要调公司系统：找管理员在「管理 → 接口」接入并授权，你就能在流程里说人话调用。
    </div>
  </div>
</template>

<style scoped>
.quickstart {
  border: 1px dashed var(--accent);
  border-radius: 12px;
  padding: 14px 16px;
  background: linear-gradient(135deg, var(--accent-weak), transparent);
  display: grid;
  gap: 10px;
  margin-bottom: 14px;
}
.qs-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 15px;
}
.qs-close {
  border: 0;
  background: transparent;
  cursor: pointer;
  color: var(--muted);
  font-size: 14px;
}
.qs-steps {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 10px;
}
.qs-step {
  border: 1px solid var(--line);
  border-radius: 10px;
  padding: 12px;
  text-decoration: none;
  color: var(--text);
  background: var(--panel);
  display: grid;
  gap: 4px;
  transition: border-color 0.15s, transform 0.15s;
}
.qs-step:hover {
  border-color: var(--accent);
  transform: translateY(-1px);
}
.qs-no {
  width: 22px;
  height: 22px;
  border-radius: 50%;
  background: var(--accent);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  font-weight: 700;
}
.qs-step b {
  font-size: 13px;
}
.qs-step span {
  font-size: 12px;
  color: var(--muted);
}
.qs-foot {
  font-size: 12px;
  color: var(--muted);
}
@media (max-width: 900px) {
  .qs-steps {
    grid-template-columns: 1fr;
  }
}
</style>
