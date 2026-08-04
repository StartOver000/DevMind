import { reactive } from 'vue';

export const toastState = reactive({
  message: '',
  isError: false,
  visible: false,
  timer: null
});

let timer = null;

export function showToast(message, isError = false) {
  toastState.message = message;
  toastState.isError = isError;
  toastState.visible = true;
  clearTimeout(timer);
  timer = setTimeout(() => {
    toastState.visible = false;
  }, 3200);
}
