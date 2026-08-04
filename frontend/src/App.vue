<script setup>
import TopBar from '@/components/TopBar.vue';
import Toast from '@/components/Toast.vue';
import Modal from '@/components/Modal.vue';
</script>

<template>
  <TopBar />
  <main>
    <router-view />
  </main>
  <Toast />
  <Modal />
</template>

<style>
main {
  max-width: 1280px;
  margin: 20px auto;
  padding: 0 16px;
}
</style>
