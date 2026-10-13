// The site is published twice: the docs of the latest release at the root, and
// the docs of main, which describe what has not been released yet, under /next/.
// The Pages workflow builds the latter with DOCS_CHANNEL=next.
export const SITE_URL = 'https://jrtc.dev/';
export const NEXT_BASE = '/next/';

// The theme imports this file too, and runs in the browser, where there is no
// process; it goes by the site's base instead.
export const IS_NEXT = typeof process !== 'undefined' && process.env.DOCS_CHANNEL === 'next';
