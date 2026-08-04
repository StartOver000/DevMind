import { describe, it, expect } from 'vitest';
import { renderMarkdown } from '@/utils/markdown';

describe('renderMarkdown', () => {
  it('渲染标题与段落', () => {
    const html = renderMarkdown('# 标题\n\n这是**加粗**文本。');
    expect(html).toContain('<h1');
    expect(html).toContain('标题');
    expect(html).toContain('<strong>加粗</strong>');
  });

  it('渲染代码块', () => {
    const html = renderMarkdown('```js\nconst a = 1;\n```');
    expect(html).toContain('<pre>');
    expect(html).toContain('<code');
    expect(html).toContain('const a = 1;');
  });

  it('渲染列表与链接', () => {
    const html = renderMarkdown('- 项目一\n- 项目二\n\n[文档](https://example.com)');
    expect(html).toContain('<li>项目一</li>');
    expect(html).toContain('<a href="https://example.com">文档</a>');
  });

  it('渲染 GFM 表格（gfm=true）', () => {
    const html = renderMarkdown('| a | b |\n| - | - |\n| 1 | 2 |');
    expect(html).toContain('<table>');
    expect(html).toContain('<td>1</td>');
  });

  it('空输入返回空字符串', () => {
    expect(renderMarkdown('')).toBe('');
    expect(renderMarkdown(null)).toBe('');
  });
});
