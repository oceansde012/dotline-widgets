// Renders tools/buds_art.html to the transparent PNG that widget_buds.xml shows in the widget picker (Android < 15 /
// launchers that ignore the generated preview). Usage: node tools/buds_art.mjs [out.png]   (needs playwright)
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const { chromium } = createRequire(import.meta.url)('playwright');
const here = path.dirname(fileURLToPath(import.meta.url));
const out = process.argv[2] || path.join(here, '../app/src/main/res/drawable-nodpi/pv_buds.png');
const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 400, height: 300 } });
await page.goto('file://' + path.join(here, 'buds_art.html') + '?w=62&h=42&scale=4&state=on');
await page.evaluate(() => { document.body.style.background = 'transparent'; });
await page.locator('#c').screenshot({ path: out, omitBackground: true });
await browser.close();
console.log('->', out);
