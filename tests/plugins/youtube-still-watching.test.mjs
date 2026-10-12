import test from 'node:test';
import assert from 'node:assert/strict';
import { startPage } from './harness.mjs';

const CORES = [['youtube', 'core.common.js'], ['youtube', 'core.mobile.js']];
const FIVE_MINUTES = 5 * 60 * 1000;

function watchPage() {
  const page = startPage({ fixture: 'youtube-watch.html', url: 'https://m.youtube.com/watch?v=I_izvAbhExY', cores: CORES });
  const { window, document } = page;
  const video = document.querySelector('#movie_player video');
  let now = 1_000_000;
  window.Date.now = () => now;
  const signals = [];
  document.addEventListener('keyup', () => signals.push(now));
  const interactions = [];
  page.bus.on('youtube:userInteracted', () => interactions.push(now));
  Object.defineProperty(video, 'paused', { value: false, configurable: true, writable: true });
  Object.defineProperty(video, 'readyState', { value: 4, configurable: true });
  page.fireMediaEvent(video, 'playing');
  page.load('youtube', 'still-watching.tweak.js');
  return Object.assign(page, {
    video, signals, interactions,
    plays: (millis) => { now += millis; page.fireMediaEvent(video, 'timeupdate'); }
  });
}

async function followed(page) {
  await page.waitFor(() => !!page.bus.last('youtube:playerStateChanged'), 500);
}

test('while the video plays the site is told someone is there, every five minutes', async () => {
  const page = watchPage();
  await followed(page);
  page.plays(250);
  assert.equal(page.signals.length, 1);
  page.plays(250);
  page.plays(60_000);
  assert.equal(page.signals.length, 1);
  page.plays(FIVE_MINUTES);
  assert.equal(page.signals.length, 2);
  page.close();
});

test('a paused video tells nothing', async () => {
  const page = watchPage();
  await followed(page);
  page.video.paused = true;
  page.plays(FIVE_MINUTES);
  assert.deepEqual(page.signals, []);
  page.close();
});

test('the signal is not taken for the driver touching the page', async () => {
  const page = watchPage();
  await followed(page);
  page.plays(250);
  assert.equal(page.signals.length, 1);
  assert.deepEqual(page.interactions, []);
  page.close();
});
