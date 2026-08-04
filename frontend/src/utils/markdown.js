import { marked } from 'marked';

// 全局配置：启用 GFM（表格/删除线/自动链接）
marked.setOptions({ gfm: true, breaks: true });

/**
 * 将 Markdown 源码渲染为 HTML 字符串。
 * @param {string} src 原始 Markdown 文本
 * @returns {string} 渲染后的 HTML
 */
export function renderMarkdown(src) {
  if (!src) return '';
  return marked.parse(String(src));
}
