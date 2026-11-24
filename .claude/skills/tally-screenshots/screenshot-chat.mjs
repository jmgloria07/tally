// Drives headless Edge over CDP (no dependencies): signs in to the sandbox, puts a finished chat from
// ask-coach.mjs in localStorage, opens #coach and screenshots it at 2x.
// Usage: node screenshot-chat.mjs <answer.json> <out.png> [width=1440] [height=1000] [heading to scroll to]
// Without a heading, the pane shows the question and the start of the answer. The chat pane is capped at 470px
// (style.css .chat-messages), so a long answer needs a second shot scrolled to a later heading.
import { spawn } from 'node:child_process';
import { mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const [reviewPath, outPath, width = '1440', height = '1000', scrollTo = ''] = process.argv.slice(2);
const review = JSON.parse(readFileSync(reviewPath, 'utf8'));
const EDGE = 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe';
const PORT = 9333;
const profile = mkdtempSync(join(tmpdir(), 'tally-shot-'));

const edge = spawn(EDGE, ['--headless=new', `--remote-debugging-port=${PORT}`, `--user-data-dir=${profile}`,
  '--no-first-run', '--hide-scrollbars', 'about:blank'], { stdio: 'ignore' });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

let target;
for (let i = 0; i < 50 && !target; i++) {
  try {
    const list = await (await fetch(`http://127.0.0.1:${PORT}/json/list`)).json();
    target = list.find((t) => t.type === 'page');
  } catch { await sleep(200); }
}
if (!target) throw new Error('Edge did not start');

const ws = new WebSocket(target.webSocketDebuggerUrl);
await new Promise((r) => ws.addEventListener('open', r, { once: true }));
let nextId = 0;
const waiting = new Map();
ws.addEventListener('message', (e) => {
  const msg = JSON.parse(e.data);
  if (msg.id && waiting.has(msg.id)) { waiting.get(msg.id)(msg); waiting.delete(msg.id); }
});
const send = (method, params = {}) => new Promise((resolve, reject) => {
  const id = ++nextId;
  waiting.set(id, (m) => (m.error ? reject(new Error(`${method}: ${m.error.message}`)) : resolve(m.result)));
  ws.send(JSON.stringify({ id, method, params }));
});
const evaluate = async (expression) => {
  const r = await send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true });
  if (r.exceptionDetails) throw new Error(r.exceptionDetails.exception?.description ?? 'evaluate failed');
  return r.result.value;
};

try {
  await send('Emulation.setDeviceMetricsOverride', { width: +width, height: +height, deviceScaleFactor: 2, mobile: +width < 600 });
  await send('Page.navigate', { url: 'http://localhost:8766/' });
  await sleep(1500);
  const chat = [{ role: 'user', content: review.question }, { role: 'assistant', content: review.answer }];
  const status = await evaluate(`(async () => {
    const r = await fetch('/api/auth/login', { method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: 'tester', password: 'testtest' }) });
    localStorage.setItem('tally.chat.v1', ${JSON.stringify(JSON.stringify(chat))});
    return r.status;
  })()`);
  if (status < 200 || status > 299) throw new Error(`login ${status}`);
  await send('Page.navigate', { url: 'http://localhost:8766/#coach' });
  await send('Page.reload'); // a hash change alone keeps the login overlay from the first, signed-out load
  await sleep(2500); // health check + render
  // Show the question and the start of the review, not the end the chat auto-scrolls to.
  const info = await evaluate(`(() => {
    const box = document.querySelector('.chat-messages');
    const answer = document.querySelectorAll('.assistant-message')[0];
    const heading = ${JSON.stringify(scrollTo)}
      ? [...document.querySelectorAll('.assistant-message h1, .assistant-message h2, .assistant-message h3')]
          .find((h) => h.textContent.includes(${JSON.stringify(scrollTo)}))
      : null;
    if (box && heading) box.scrollTop += heading.getBoundingClientRect().top - box.getBoundingClientRect().top - 16;
    else if (box && answer) box.scrollTop = answer.offsetTop - box.offsetTop - 120;
    return { status: document.querySelector('.model-badge')?.innerText, login: !!document.querySelector('.login-overlay') };
  })()`);
  console.log(JSON.stringify(info));
  await sleep(300);
  const { data } = await send('Page.captureScreenshot', { format: 'png' });
  writeFileSync(outPath, Buffer.from(data, 'base64'));
  console.log(`saved ${outPath}`);
} finally {
  ws.close();
  edge.kill();
}
