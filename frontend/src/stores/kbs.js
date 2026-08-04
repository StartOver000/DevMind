import { reactive } from 'vue';
import { api } from '@/api/client';

/** 全局知识库列表，供知识库/问答/SQL/评估视图共享 */
export const kbsStore = reactive({
  kbs: [],
  loaded: false,
  async load() {
    try {
      const data = await api('/api/knowledge-bases');
      this.kbs = data.items || [];
      this.loaded = true;
    } catch (err) {
      this.kbs = [];
      this.loaded = true;
      throw err;
    }
  }
});
