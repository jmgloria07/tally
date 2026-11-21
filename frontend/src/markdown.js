import { marked } from 'marked';
import DOMPurify from 'dompurify';

// The libraries are bundled by Vite, never loaded from a CDN.
// Always sanitize: never render marked output on its own. The CSP is a second layer.
export function renderMarkdown(text) {
  return DOMPurify.sanitize(marked.parse(String(text ?? ''), { breaks: false, gfm: true }));
}
