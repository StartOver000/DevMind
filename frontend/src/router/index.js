import { createRouter, createWebHashHistory } from 'vue-router';

const routes = [
  { path: '/', redirect: '/kb' },
  {
    path: '/kb',
    name: 'knowledge-base',
    component: () => import('@/views/KnowledgeBaseView.vue'),
    meta: { title: '知识库' }
  },
  {
    path: '/chat',
    name: 'chat',
    component: () => import('@/views/ChatView.vue'),
    meta: { title: '问答' }
  },
  {
    path: '/tools',
    name: 'tools',
    component: () => import('@/views/ToolsView.vue'),
    meta: { title: '接口' }
  },
  {
    path: '/workflows',
    name: 'workflows',
    component: () => import('@/views/WorkflowsView.vue'),
    meta: { title: '流程' }
  },
  {
    path: '/skills',
    name: 'skills',
    component: () => import('@/views/SkillView.vue'),
    meta: { title: '技能' }
  },
  {
    path: '/sql',
    name: 'sql',
    component: () => import('@/views/SqlDiagnosisView.vue'),
    meta: { title: 'SQL 诊断' }
  },
  {
    path: '/usage',
    name: 'usage',
    component: () => import('@/views/UsageView.vue'),
    meta: { title: '用量' }
  },
  {
    path: '/audit',
    name: 'audit',
    component: () => import('@/views/AuditView.vue'),
    meta: { title: '审计日志' }
  },
  {
    path: '/team',
    name: 'team',
    component: () => import('@/views/TeamView.vue'),
    meta: { title: '团队' }
  },
  {
    path: '/eval',
    name: 'eval',
    component: () => import('@/views/EvaluationView.vue'),
    meta: { title: '评估' }
  }
];

const router = createRouter({
  history: createWebHashHistory(),
  routes
});

router.afterEach((to) => {
  document.title = to.meta.title ? `DevMind · ${to.meta.title}` : 'DevMind';
});

export default router;
