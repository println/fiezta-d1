import test from 'node:test';
import assert from 'node:assert/strict';
import { startPage } from './harness.mjs';

const DESKTOP = [['youtube', 'core.common.js'], ['youtube', 'core.desktop.js']];
const MOBILE = [['youtube', 'core.common.js'], ['youtube', 'core.mobile.js']];

function desktopPlaylistPage() {
  return startPage({
    fixture: 'youtube-playlist-desktop.html',
    url: 'https://www.youtube.com/watch?v=azdwsXLmrHE&list=RDI_izvAbhExY&index=2',
    cores: DESKTOP,
    plugin: ['youtube', 'watch.events.js']
  });
}

test('the desktop panel gives the whole playlist, titled after it', async () => {
  const page = desktopPlaylistPage();
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

test('the cursor is the item the desktop panel marks as selected', async () => {
  const page = desktopPlaylistPage();
  await page.waitFor(() => page.queues.length > 0);
  assert.equal(page.lastQueue().cursor, 1, 'the second track is playing, so it has one before and one after');
  page.close();
});

test('artwork is derived from the video id here too', async () => {
  const page = desktopPlaylistPage();
  await page.waitFor(() => page.queues.length > 0);
  assert.deepEqual(page.lastQueue().entries.map((entry) => entry.artwork), [
    'https://i.ytimg.com/vi/I_izvAbhExY/mqdefault.jpg',
    'https://i.ytimg.com/vi/azdwsXLmrHE/mqdefault.jpg',
    'https://i.ytimg.com/vi/9jK-NcRmVcw/mqdefault.jpg'
  ]);
  page.close();
});

test('picking a queue item clicks the desktop endpoint of that track', async () => {
  const page = desktopPlaylistPage();
  const clicked = [];
  page.document.querySelectorAll('a#wc-endpoint').forEach((link) => {
    link.addEventListener('click', () => clicked.push(link.getAttribute('href')));
  });

  page.fiezta.dispatch('queueItem', '2');
  assert.equal(clicked.length, 1);
  assert.match(clicked[0], /v=9jK-NcRmVcw/);
  page.close();
});
