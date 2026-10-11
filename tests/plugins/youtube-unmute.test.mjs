import test from 'node:test';
import assert from 'node:assert/strict';
import { startPage } from './harness.mjs';

const CORES = [['youtube', 'core.common.js'], ['youtube', 'core.desktop.js']];

function watchPage() {
  const page = startPage({ fixture: 'youtube-watch.html', url: 'https://www.youtube.com/watch?v=I_izvAbhExY', cores: CORES });
  page.load('youtube', 'unmute-player.tweak.js');
  return page;
}

function play(page, video) {
  Object.defineProperty(video, 'paused', { value: false, configurable: true });
  Object.defineProperty(video, 'readyState', { value: 4, configurable: true });
  page.fireMediaEvent(video, 'playing');
}

test('a muted video that starts playing is unmuted', async () => {
  const page = watchPage();
  const video = page.document.querySelector('video');
  video.muted = true;
  play(page, video);
  await page.waitFor(() => !video.muted, 500);
  page.close();
});

test('a video the user left unmuted is left alone', async () => {
  const page = watchPage();
  const video = page.document.querySelector('video');
  video.muted = false;
  video.volume = 0.3;
  play(page, video);
  await page.waitFor(() => page.bus.last('youtube:playerStateChanged').state === 'playing', 500);
  assert.equal(video.volume, 0.3);
  page.close();
});
