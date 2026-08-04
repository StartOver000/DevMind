import { describe, it, expect, beforeEach } from 'vitest';
import { mount } from '@vue/test-utils';
import { showToast, toastState } from '@/stores/toast';
import { openModal, closeModal, modalState } from '@/stores/modal';
import Toast from '@/components/Toast.vue';
import Modal from '@/components/Modal.vue';

describe('Toast 组件', () => {
  beforeEach(() => {
    toastState.visible = false;
    toastState.message = '';
  });

  it('默认隐藏', () => {
    const wrapper = mount(Toast);
    expect(wrapper.find('.toast').classes()).toContain('hidden');
  });

  it('showToast 后显示消息', () => {
    showToast('操作成功');
    const wrapper = mount(Toast);
    expect(wrapper.find('.toast').classes()).not.toContain('hidden');
    expect(wrapper.text()).toContain('操作成功');
  });

  it('错误提示带 error class', () => {
    showToast('出错了', true);
    const wrapper = mount(Toast);
    expect(wrapper.find('.toast').classes()).toContain('error');
  });
});

describe('Modal 组件', () => {
  beforeEach(() => {
    closeModal();
  });

  it('默认不渲染', () => {
    const wrapper = mount(Modal);
    expect(wrapper.find('.modal').exists()).toBe(false);
  });

  it('openModal 后渲染标题', () => {
    openModal('我的标题', null);
    const wrapper = mount(Modal);
    expect(wrapper.find('.modal').exists()).toBe(true);
    expect(wrapper.find('.modal-head h3').text()).toBe('我的标题');
  });

  it('closeModal 后隐藏', () => {
    openModal('标题', null);
    closeModal();
    expect(modalState.visible).toBe(false);
    const wrapper = mount(Modal);
    expect(wrapper.find('.modal').exists()).toBe(false);
  });
});
