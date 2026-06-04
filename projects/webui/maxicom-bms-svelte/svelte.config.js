import adapter from '@sveltejs/adapter-static';

/** @type {import('@sveltejs/kit').Config} */
const config = {
	kit: {
		adapter: adapter({
			pages: 'dist',
			assets: 'dist',
			fallback: 'index.html'
		}),
		output: {
			// Avoid ESM/dynamic-import bootstrap for the older browser used on site.
			bundleStrategy: 'inline'
		}
	}
};

export default config;
