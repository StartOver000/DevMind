import { describe, it, expect, vi } from 'vitest';
import { mount } from '@vue/test-utils';
import DocTable from '@/components/DocTable.vue';
import KbSidebar from '@/components/KbSidebar.vue';

describe('DocTable 组件', () => {
  it('空列表显示提示', () => {
    const wrapper = mount(DocTable, { props: { docs: [], kbId: 1 } });
    expect(wrapper.text()).toContain('还没有文档');
  });

  it('渲染文档行和状态', () => {
    const docs = [
      { id: 1, fileName: 'a.md', fileType: 'md', status: 'COMPLETED', chunkCount: 10, createdTime: '2026-08-04T10:00:00' },
      { id: 2, fileName: 'b.pdf', fileType: 'pdf', status: 'PROCESSING', chunkCount: 0, createdTime: '2026-08-04T11:00:00' }
    ];
    const wrapper = mount(DocTable, { props: { docs, kbId: 1 } });
    const rows = wrapper.findAll('tbody tr');
    expect(rows).toHaveLength(2);
    expect(rows[0].text()).toContain('a.md');
    expect(rows[0].text()).toContain('COMPLETED');
    expect(rows[1].find('.status').classes()).toContain('PROCESSING');
    expect(rows[0].text()).toContain('2026-08-04 10:00:00');
  });

  it('每个文档行有操作按钮', () => {
    const docs = [{ id: 7, fileName: 'a.md', fileType: 'md', status: 'COMPLETED', chunkCount: 1, createdTime: null }];
    const wrapper = mount(DocTable, { props: { docs, kbId: 1 } });
    const buttons = wrapper.findAll('td:last-child button').map((b) => b.text());
    expect(buttons).toEqual(['预览', '更新', '版本', '任务', '删除']);
  });

  it('删除时确认后调用 API 并触发 refresh', async () => {
    const docs = [{ id: 7, fileName: 'a.md', fileType: 'md', status: 'COMPLETED', chunkCount: 1, createdTime: null }];
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true);
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      text: async () => '{}'
    });
    global.fetch = fetchMock;

    const wrapper = mount(DocTable, { props: { docs, kbId: 1 } });
    await wrapper.findAll('td:last-child button')[4].trigger('click');
    await new Promise((r) => setTimeout(r, 0));

    expect(confirmSpy).toHaveBeenCalled();
    expect(fetchMock).toHaveBeenCalledWith(
      expect.stringContaining('/api/documents/7'),
      expect.objectContaining({ method: 'DELETE' })
    );
    expect(wrapper.emitted('refresh')).toBeTruthy();
  });
});

describe('KbSidebar 组件', () => {
  it('渲染知识库列表', () => {
    const kbs = [
      { id: 1, name: '研发知识', documentCount: 5 },
      { id: 2, name: '运维手册', documentCount: 0 }
    ];
    const wrapper = mount(KbSidebar, { props: { kbs, currentKbId: 1 } });
    const items = wrapper.findAll('.kb-item');
    expect(items).toHaveLength(2);
    expect(items[0].text()).toContain('研发知识');
    expect(items[0].text()).toContain('5 文档');
    expect(items[0].classes()).toContain('active');
  });

  it('空列表显示提示', () => {
    const wrapper = mount(KbSidebar, { props: { kbs: [], currentKbId: null } });
    expect(wrapper.text()).toContain('还没有知识库');
  });

  it('空列表显示「一键创建示例知识库」CTA', () => {
    const wrapper = mount(KbSidebar, { props: { kbs: [], currentKbId: null } });
    expect(wrapper.find('.demo-kb-btn').exists()).toBe(true);
    expect(wrapper.text()).toContain('一键创建示例知识库');
  });

  it('有知识库时隐藏示例库 CTA', () => {
    const wrapper = mount(KbSidebar, { props: { kbs: [{ id: 1, name: '库', documentCount: 1 }], currentKbId: 1 } });
    expect(wrapper.find('.demo-kb-btn').exists()).toBe(false);
  });

  it('点击示例库 CTA 调用 demo 接口并触发 created', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      text: async () => JSON.stringify({ id: 99, name: '示例知识库', documentId: 5, documentName: 'a.md', duplicate: false })
    });
    global.fetch = fetchMock;

    const wrapper = mount(KbSidebar, { props: { kbs: [], currentKbId: null } });
    await wrapper.find('.demo-kb-btn').trigger('click');
    await new Promise((r) => setTimeout(r, 0));

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/knowledge-bases/demo',
      expect.objectContaining({ method: 'POST' })
    );
    expect(wrapper.emitted('created')).toBeTruthy();
    expect(wrapper.emitted('created')[0][0]).toMatchObject({ id: 99, name: '示例知识库' });
  });

  it('点击知识库触发 select 事件', async () => {
    const kbs = [{ id: 3, name: '测试库', documentCount: 1 }];
    const wrapper = mount(KbSidebar, { props: { kbs, currentKbId: null } });
    await wrapper.find('.kb-item').trigger('click');
    expect(wrapper.emitted('select')).toBeTruthy();
    expect(wrapper.emitted('select')[0]).toEqual([3]);
  });
});
