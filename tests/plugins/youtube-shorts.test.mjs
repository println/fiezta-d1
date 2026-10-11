import test from 'node:test';
import assert from 'node:assert/strict';
import { startPage } from './harness.mjs';

const CORES = [['youtube', 'core.common.js'], ['youtube', 'core.desktop.js']];

function shortsPage() {
  const page = startPage({
    fixture: 'youtube-shorts.html',
    url: 'https://www.youtube.com/shorts/abc123',
    cores: CORES,
    plugin: ['youtube', 'shorts.events.js']
  });
  page.addMedia({ currentTime: 2, duration: 30 });
  return page;
}

test('availability comes from the carousel buttons', async () => {
  const page = shortsPage();
  await page.waitFor(() => page.readings.some((reading) => reading.canSkipNext));
  const reading = page.lastReading();
  assert.equal(reading.canSkipNext, true, 'the next button is enabled');
  assert.equal(reading.canSkipPrevious, false, 'the previous button is disabled');
  page.close();
});

test('the queue is the current short alone, because the page renders no neighbour', async () => {
  const page = shortsPage();
  assert.equal(page.document.querySelectorAll('a[href*="/shorts/"]').length, 0, 'the real page links to no neighbour');

  await page.waitFor(() => page.queues.length > 0);
  const queue = page.lastQueue();
  assert.equal(queue.shape, 'stream');
  assert.equal(queue.cursor, 0);
  assert.deepEqual(queue.entries.map((entry) => entry.title), ['8 de abril de 2026 - YouTube']);
  page.close();
});

test('next and previous click the buttons, and a disabled one is left alone', async () => {
  const page = shortsPage();
  const clicks = [];
  const [previous, next] = page.document.querySelectorAll('.ytShortsCarouselShortsA11yNavButton');
  previous.addEventListener('click', () => clicks.push('previous'));
  next.addEventListener('click', () => clicks.push('next'));

  page.fiezta.dispatch('previousClick');
  page.fiezta.dispatch('nextClick');
  assert.deepEqual(clicks, ['next'], 'the previous button was disabled');
  page.close();
});

test('the track id of a short comes from its url', async () => {
  const page = shortsPage();
  await page.waitFor(() => page.readings.length > 0);
  assert.equal(page.lastReading().trackId, 'abc123');
  page.close();
});
