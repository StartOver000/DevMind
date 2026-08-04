<script setup>
import { ref, onMounted } from 'vue';
import { api, formatTime } from '@/api/client';
import { showToast } from '@/stores/toast';

const items = ref([]);
const error = ref('');

async function load() {
  try {
    const data = await api('/api/sql-diagnosis?limit=10');
    items.value = data.items || [];
  } catch (err) {
    error.value = err.message;
    showToast(err.message, true);
  }
}

onMounted(load);
</script>

<template>
  <div v-if="error" class="empty">{{ error }}</div>
  <div v-else-if="!items.length" class="empty small">还没有诊断记录</div>
  <div v-else>
    <div v-for="item in items" :key="item.id" class="reference">
      <div class="head">
        <span>#{{ item.id }} {{ item.riskLevel }}</span>
        <span class="score">{{ formatTime(item.createdTime) }}</span>
      </div>
      <p>{{ item.sql }}</p>
    </div>
  </div>
</template>
