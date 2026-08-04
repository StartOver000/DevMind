import { reactive } from 'vue';

export const modalState = reactive({
  visible: false,
  title: '',
  // 弹窗内容组件（通过 defineAsyncComponent 或组件对象传入）
  component: null,
  props: {}
});

export function openModal(title, component, props = {}) {
  modalState.title = title;
  modalState.component = component;
  modalState.props = props;
  modalState.visible = true;
}

export function closeModal() {
  modalState.visible = false;
  modalState.component = null;
  modalState.props = {};
}
