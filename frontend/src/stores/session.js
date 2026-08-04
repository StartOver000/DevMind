import { reactive } from 'vue';
import { getToken, getCurrentUserId } from '@/api/client';

/**
 * 会话状态：登录 Token、当前用户、用户列表。
 * reloadKey 用于在登录/切换用户后通知各视图重新加载数据。
 */
export const session = reactive({
  userId: getCurrentUserId(),
  reloadKey: 0
});

session.requestReload = function () {
  this.reloadKey += 1;
};

export { getToken };
