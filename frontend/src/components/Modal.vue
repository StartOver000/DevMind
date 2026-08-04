<script setup>
import { modalState, closeModal } from '@/stores/modal';
</script>

<template>
  <div v-if="modalState.visible" class="modal" @click.self="closeModal">
    <div class="modal-card">
      <div class="modal-head">
        <h3>{{ modalState.title }}</h3>
        <button @click="closeModal">关闭</button>
      </div>
      <div class="modal-body">
        <component :is="modalState.component" v-bind="modalState.props" />
      </div>
    </div>
  </div>
</template>

<style scoped>
.modal {
  position: fixed;
  inset: 0;
  background: rgba(15, 23, 32, 0.45);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 50;
}

.modal-card {
  width: min(560px, calc(100vw - 32px));
  max-height: 80vh;
  overflow: auto;
  background: #fff;
  border-radius: 8px;
  padding: 16px;
}

.modal-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 10px;
}

.modal-head h3 {
  margin: 0;
}
</style>
