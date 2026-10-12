import test from 'node:test';
import assert from 'node:assert/strict';
import { startPage } from './harness.mjs';

const DESKTOP = [['youtube', 'core.common.js'], ['youtube', 'core.desktop.js']];
const MOBILE = [['youtube', 'core.common.js'], ['youtube', 'core.mobile.js']];

function playlistPage() {
  return startPage({
    fixture: 'youtube-playlist.html',
    url: 'https://m.youtube.com/watch?v=I_izvAbhExY&list=RDI_izvAbhExY',
    cores: MOBILE,
    plugin: ['youtube', 'watch.events.js']
  });
}

test('the queue is the playlist of the page, titled after it', async () => {
  const page = playlistPage();
  await page.waitFor(() => page.queues.length > 0);
  const queue = page.lastQueue();
  assert.equal(queue.shape, 'list');
  assert.equal(queue.title, "Mix de Bee Gees - Stayin' Alive (Official Video)");
  assert.deepEqual(queue.entries.map((entry) => entry.title), [
    "Bee Gees - Stayin' Alive (Official Video)",
    'Queen - Radio Ga Ga (Official Video)',
    'Europe - The Final Countdown (Official Video)'
  ]);
  assert.deepEqual(queue.entries.map((entry) => entry.subtitle), ['beegees', 'Queen Official', 'Europe']);
  page.close();
});

test('the cursor follows the selected track', async () => {
  const page = playlistPage();
  await page.waitFor(() => page.queues.length > 0);
  assert.equal(page.lastQueue().cursor, 0);

  const items = page.document.querySelectorAll('ytm-playlist-panel-video-renderer');
  items[0].setAttribute('aria-selected', 'false');
  items[1].setAttribute('aria-selected', 'true');
  await page.waitFor(() => page.lastQueue().cursor === 1);
  page.close();
});

test('artwork is derived from the video id, because the thumbnail is lazy loaded', async () => {
  const page = playlistPage();
  const images = page.document.querySelectorAll('ytm-playlist-panel-video-renderer img');
  assert.equal(images[0].getAttribute('src'), null, 'the fixture keeps the real page: no src yet');

  await page.waitFor(() => page.queues.length > 0);
  assert.deepEqual(page.lastQueue().entries.map((entry) => entry.artwork), [
    'https://i.ytimg.com/vi/I_izvAbhExY/mqdefault.jpg',
    'https://i.ytimg.com/vi/azdwsXLmrHE/mqdefault.jpg',
    'https://i.ytimg.com/vi/9jK-NcRmVcw/mqdefault.jpg'
  ]);
  page.close();
});

test('picking a queue item clicks the track it names', async () => {
  const page = playlistPage();
  const clicked = [];
  page.document.querySelectorAll('a.YtmCompactMediaItemMetadataContent').forEach((link) => {
    link.addEventListener('click', () => clicked.push(link.getAttribute('href')));
  });

  page.fiezta.dispatch('queueItem', '2');
  assert.equal(clicked.length, 1);
  assert.match(clicked[0], /v=9jK-NcRmVcw/);
  page.close();
});

test('a mix whose panel is collapsed falls back to the current track and what comes next', async () => {
  const page = playlistPage();
  page.document.querySelectorAll('ytm-playlist-panel-video-renderer').forEach((item) => item.remove());
  await page.waitFor(() => page.queues.length > 0);
  const queue = page.lastQueue();
  assert.equal(queue.shape, 'stream');
  assert.equal(queue.cursor, 0);
  assert.ok(queue.entries.length >= 1, 'the current track is always in the queue');
  page.close();
});
