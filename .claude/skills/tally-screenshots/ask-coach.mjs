// Signs in to the sandbox (tester/testtest on localhost:8766) and asks the coach one question.
// Writes {question, answer} to <out.json>. Usage: node ask-coach.mjs <out.json> "<question>"
import { writeFileSync } from 'node:fs';

const [outPath, question] = process.argv.slice(2);
if (!outPath || !question) throw new Error('usage: node ask-coach.mjs <out.json> "<question>"');
const BASE = 'http://localhost:8766';

const login = await fetch(`${BASE}/api/auth/login`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ username: 'tester', password: 'testtest' }),
});
if (!login.ok) throw new Error(`login ${login.status}: ${await login.text()}`);
const cookie = login.headers.getSetCookie().map((c) => c.split(';')[0]).join('; ');

const res = await fetch(`${BASE}/api/coach`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json', Cookie: cookie },
  body: JSON.stringify({ message: question, history: [] }),
});
const body = await res.json();
if (!res.ok) throw new Error(`coach ${res.status}: ${JSON.stringify(body)}`);
writeFileSync(outPath, JSON.stringify({ question, answer: body.answer }, null, 2));
console.log(body.answer);
