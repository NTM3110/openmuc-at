import { sveltekit } from '@sveltejs/kit/vite';
import { defineConfig } from 'vite';

export default defineConfig({
	plugins: [sveltekit()],
	server: {
		proxy: {
			'/rest': {
				target: 'http://localhost:8888',
				changeOrigin: true
			}
		}
	},
	base: './',                 // VERY IMPORTANT
	build: {
		// The installed monitoring browser is based on pre-Chromium Edge.
		target: 'edge18',
		// Inline the icon fonts because SvelteKit inlines CSS in legacy bundle mode.
		assetsInlineLimit: 250000,
		outDir: 'dist',
		emptyOutDir: true
	}
});
