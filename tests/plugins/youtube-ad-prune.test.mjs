import test from 'node:test';
import assert from 'node:assert/strict';
import { startPage } from './harness.mjs';

const CORES = [['youtube', 'core.common.js'], ['youtube', 'core.desktop.js']];

function adPage() {
  const page = startPage({ fixture: 'youtube-watch.html', url: 'https://www.youtube.com/watch?v=I_izvAbhExY', cores: CORES });
  page.load('youtube', 'ad-prune.tweak.js');
  return page;
}

function showAd(page, { skip = false, duration = 15 } = {}) {
  const player = page.document.getElementById('movie_player');
  const video = page.document.querySelector('video');
  Object.defineProperty(video, 'duration', { value: duration, configurable: true });
  const clicks = [];
  if (skip) {
    const button = page.document.createElement('button');
    button.className = 'ytp-skip-ad-button';
    button.addEventListener('click', () => clicks.push('skip'));
    player.appendChild(button);
  }
  player.classList.add('ad-showing');
  return { video, clicks };
}

test('an ad is fast-forwarded to its end', async () => {
  const page = adPage();
  const { video } = showAd(page);
  await page.waitFor(() => video.currentTime === 15, 500);
  page.close();
});

test('the skip button of an ad is clicked', async () => {
  const page = adPage();
  const { clicks } = showAd(page, { skip: true });
  await page.waitFor(() => clicks.length > 0, 500);
  page.close();
});

test('an ad whose duration arrives late is skipped when it does', async () => {
  const page = adPage();
  const { video } = showAd(page, { duration: NaN });
  await page.waitFor(() => page.bus.last('youtube:adChanged').showing, 500);
  assert.equal(video.currentTime, 0);
  Object.defineProperty(video, 'duration', { value: 20, configurable: true });
  page.fireMediaEvent(video, 'durationchange');
  assert.equal(video.currentTime, 20);
  page.close();
});

test('an ad that survives the first jump is jumped again while it plays', async () => {
  const page = adPage();
  const { video } = showAd(page);
  await page.waitFor(() => video.currentTime === 15, 500);
  video.currentTime = 3;
  page.fireMediaEvent(video, 'timeupdate');
  assert.equal(video.currentTime, 15);
  page.close();
});

test('once the ad is over the video is left alone', async () => {
  const page = adPage();
  const { video } = showAd(page);
  await page.waitFor(() => video.currentTime === 15, 500);
  page.document.getElementById('movie_player').classList.remove('ad-showing');
  await page.waitFor(() => !page.bus.last('youtube:adChanged').showing, 500);
  video.currentTime = 3;
  page.fireMediaEvent(video, 'timeupdate');
  assert.equal(video.currentTime, 3);
  page.close();
});
