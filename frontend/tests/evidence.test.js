import { describe, it, expect } from 'vitest';
import { mount } from '@vue/test-utils';
import EvidenceDrawer from '@/components/EvidenceDrawer.vue';

const validPkg = {
  runId: 100,
  valid: true,
  recordCount: 2,
  brokenIndex: -1,
  reason: null,
  approved: true,
  rejected: false,
  executed: true,
  decidedBy: '7',
  lastRecordHash: 'abcdef1234567890',
  records: [
    {
      action: 'APPROVE',
      nodeTitle: '财务审批',
      decidedBy: '7',
      beforeHash: '1111111111111111',
      afterHash: null,
      prevHash: 'GENESIS',
      recordHash: '2222222222222222'
    },
    {
      action: 'EXECUTE',
      nodeTitle: null,
      decidedBy: 'system',
      beforeHash: null,
      afterHash: '3333333333333333',
      prevHash: '2222222222222222',
      recordHash: '4444444444444444'
    }
  ]
};

describe('EvidenceDrawer 组件', () => {
  it('链完整时显示「链完整」与五要素摘要', () => {
    const wrapper = mount(EvidenceDrawer, { props: { pkg: validPkg } });

    expect(wrapper.find('.evidence-drawer').exists()).toBe(true);
    expect(wrapper.find('.evidence-valid').text()).toContain('链完整');
    expect(wrapper.find('.evidence-invalid').exists()).toBe(false);
    expect(wrapper.text()).toContain('run #100');
    expect(wrapper.text()).toContain('审批通过');
    expect(wrapper.text()).toContain('已执行');
    expect(wrapper.text()).toContain('2 条');
  });

  it('链断开时显示断点序号与原因，而不是绿色状态', () => {
    const broken = {
      ...validPkg,
      valid: false,
      brokenIndex: 2,
      reason: '哈希不匹配：第 2 条记录内容被改动'
    };
    const wrapper = mount(EvidenceDrawer, { props: { pkg: broken } });

    expect(wrapper.find('.evidence-invalid').exists()).toBe(true);
    expect(wrapper.find('.evidence-valid').exists()).toBe(false);
    expect(wrapper.text()).toContain('第 2 条');
    expect(wrapper.text()).toContain('哈希不匹配');
  });

  it('证据行按顺序渲染动作与哈希摘要', () => {
    const wrapper = mount(EvidenceDrawer, { props: { pkg: validPkg } });

    const rows = wrapper.findAll('tbody tr');
    expect(rows).toHaveLength(2);
    expect(rows[0].text()).toContain('审批通过');
    expect(rows[0].text()).toContain('财务审批');
    expect(rows[0].text()).toContain('11111111');
    expect(rows[1].text()).toContain('执行');
    expect(rows[1].text()).toContain('system');
    expect(rows[1].text()).toContain('22222222');
  });

  it('无证据时不误报「链断开」', () => {
    const empty = {
      runId: 100,
      valid: false,
      recordCount: 0,
      brokenIndex: -1,
      reason: '无证据：该运行没有审批或执行记录',
      approved: false,
      rejected: false,
      executed: false,
      decidedBy: null,
      lastRecordHash: null,
      records: []
    };
    const wrapper = mount(EvidenceDrawer, { props: { pkg: empty } });

    expect(wrapper.find('.evidence-empty').exists()).toBe(true);
    expect(wrapper.find('.evidence-invalid').exists()).toBe(false);
    expect(wrapper.text()).toContain('无证据');
    expect(wrapper.text()).not.toContain('第 -1 条');
  });

  it('请求失败（pkg 为空且非 loading）时不渲染伪造状态', () => {
    const wrapper = mount(EvidenceDrawer, { props: { pkg: null, loading: false } });

    expect(wrapper.find('.evidence-status').exists()).toBe(false);
    expect(wrapper.find('tbody').exists()).toBe(false);
  });

  it('加载中显示提示且不渲染表格', () => {
    const wrapper = mount(EvidenceDrawer, { props: { pkg: null, loading: true } });

    expect(wrapper.text()).toContain('加载中');
    expect(wrapper.find('tbody').exists()).toBe(false);
  });
});
