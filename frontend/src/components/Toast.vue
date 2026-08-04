<script setup>
import { toastState } from '@/stores/toast';
</script>

<template>
  <div class="toast" :class="{ hidden: !toastState.visible, error: toastState.isError }">
    {{ toastState.message }}
  </div>
</template>

<style scoped>
.toast {
  position: fixed;
  left: 50%;
  bottom: 24px;
  transform: translateX(-50%);
  background: #26313c;
  color: #fff;
  padding: 10px 16px;
  border-radius: 6px;
  z-index: 60;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.2);
  max-width: 80vw;
}

.toast.error {
  background: var(--danger);
}

.hidden {
  display: none;
}
</style>
