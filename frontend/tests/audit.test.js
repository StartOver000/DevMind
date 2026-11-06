import { describe, it, expect, vi, beforeEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';

vi.mock('@/api/client', () => ({
  api: vi.fn(),
  formatTime: (t) => (t ? String(t) : '-')
}));
vi.mock('@/stores/toast', () => ({ showToast: vi.fn() }));
vi.mock('@/stores/session', () => ({ session: { reloadKey: 0 } }));

import { api } from '@/api/client';
import AuditView from '@/views/AuditView.vue';

const logRow = {
  id: 1,
  created_time: '2026-11-06 10:00:00',
  user_id: 2,
  tool_name: 'create_order',
  tool_type: 'interface',
  source: 'agent',
  status: 'success',
  cost_ms: 12,
  error: null,
  executed_as: 'user:2'
};

describe('AuditView 执行身份', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('调用明细展示实际执行身份（区分代持与用户身份）', async () => {
    api.mockResolvedValueOnce([]).mockResolvedValueOnce([logRow]);

    const wrapper = mount(AuditView);
    await flushPromises();

    const text = wrapper.text();
    expect(text).toContain('执行身份');
    expect(text).toContain('user:2');
  });

  it('操作回放的时间线里展示每条调用的执行身份', async () => {
    api.mockResolvedValueOnce([]).mockResolvedValueOnce([]);
    const wrapper = mount(AuditView);
    await flushPromises();

    const tabs = wrapper.findAll('.tab-btn');
    await tabs[1].trigger('click');
    api.mockResolvedValueOnce({
      total: 1,
      denied: 0,
      windowMinutes: 30,
      tools: ['create_order'],
      deniedPolicies: [],
      events: [
        {
          id: 9,
          created_time: '2026-11-06 10:00:00',
          tool_name: 'create_order',
          source: 'agent',
          decision: 'ALLOW',
          policy_id: 'DEFAULT_ALLOW',
          args_hash: 'abcdef0123456789',
          status: 'success',
          cost_ms: 5,
          workflow_run_id: null,
          executed_as: 'service:create_order'
        }
      ]
    });

    await wrapper.find('.replay-form button').trigger('click');
    await flushPromises();

    const text = wrapper.text();
    expect(text).toContain('service:create_order');
  });
});
