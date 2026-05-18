import { defineConfig } from 'vite';
import { viteSingleFile } from 'vite-plugin-singlefile';
import { resolve, dirname } from 'path';
import { fileURLToPath } from 'url';

const __dirname = dirname(fileURLToPath(import.meta.url));

/**
 * One Vite build per game. The {@code GAME} env var selects which entry to
 * build/serve — package.json wraps this in {@code build:<game>} scripts.
 *
 * Why one-per-build? {@code vite-plugin-singlefile} sets
 * {@code output.inlineDynamicImports: true}, which Rollup forbids for multi-input
 * builds. Inlining everything (CSS + JS + bridge shim) into ONE self-contained
 * HTML per game is the whole point — so we accept the build-per-game pattern.
 *
 * Output: {@code dist/<game>/index.html} — picked up by the Android Gradle
 * {@code copyGamesHtml} task and dropped into the APK assets, served by the
 * WebView at {@code file:///android_asset/games/<game>/index.html}.
 */

const GAME = process.env.GAME ?? 'wyr';

export default defineConfig({
  root: resolve(__dirname, 'src'),
  base: './',
  plugins: [viteSingleFile()],
  build: {
    outDir: resolve(__dirname, 'dist'),
    // Each game's output goes to its own subdir (dist/<game>/index.html),
    // so concurrent builds don't clobber siblings. We DO want to clear
    // <game>/'s own artifacts on each build — which singleFile + Vite handle
    // naturally because only one game's entry is in the input.
    emptyOutDir: false,
    target: 'es2020',
    cssCodeSplit: false,
    assetsInlineLimit: 100_000_000,
    rollupOptions: {
      input: resolve(__dirname, `src/${GAME}/index.html`),
    },
  },
  server: {
    // 5173 collides with lading-app dev; bump.
    port: 5174,
    open: `/${GAME}/`,
  },
});
