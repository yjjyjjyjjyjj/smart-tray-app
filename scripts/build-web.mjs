import { cp, mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import { build } from 'esbuild';

const root = fileURLToPath(new URL('../', import.meta.url));
const out = path.join(root, 'www');
await rm(out, { recursive: true, force: true });
await mkdir(out, { recursive: true });
for (const directory of ['css', 'js', 'assets']) {
  await cp(path.join(root, directory), path.join(out, directory), { recursive: true });
}
await build({ entryPoints: [path.join(root, 'native/bridge.js')], bundle: true,
  format: 'iife', target: 'es2020', outfile: path.join(out, 'js/capacitor-runtime.js') });
const html = await readFile(path.join(root, 'index.html'), 'utf8');
await writeFile(path.join(out, 'index.html'), html.replace('<script src="js/mock-data.js">',
  '<script src="js/capacitor-runtime.js"></script>\n  <script src="js/mock-data.js">'));
console.log('Web assets built in www/ (edit index.html, css/, js/; www/ is generated).');
