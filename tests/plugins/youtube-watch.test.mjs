import test from 'node:test';
import assert from 'node:assert/strict';
import { startPage } from './harness.mjs';

const DESKTOP = [['youtube', 'core.common.js'], ['youtube', 'core.desktop.js']];
const MOBILE = [['youtube', 'core.common.js'], ['youtube', 'core.mobile.js']];

const VIDEO = { title: "Bee Gees - Stayin' Alive (Official Video)", author: 'beegees', video_id: 'I_izvAbhExY' };

function watchPage({ withPlayerApi = true } = {}) {
  const page = startPage({
    fixture: 'youtube-watch.html',
    url: 'https://www.youtube.com/watch?v=I_izvAbhExY',
    cores: DESKTOP,
    plugin: ['youtube', 'watch.events.js']
  });
  if (withPlayerApi) {
    page.document.getElementById('movie_player').getVideoData = () => VIDEO;
  }
  page.addMedia({ currentTime: 5, duration: 250 });
  page.fireMediaEvent(page.document.querySelector('video'), 'loadedmetadata');
  return page;
}

test('availability comes from the player buttons of the real page', async () => {
  const page = watchPage();
  await page.waitFor(() => page.readings.some((reading) => reading.canSkipNext));
  const reading = page.lastReading();
  assert.equal(reading.canSkipNext, true, 'ytp-next-button is aria-disabled=false');
  assert.equal(reading.canSkipPrevious, false, 'ytp-prev-button is aria-disabled=true');
  page.close();
});

test('the queue is the current track and the next one the player button names', async () => {
  const page = watchPage();
  await page.waitFor(() => page.queues.length > 0);
  const queue = page.lastQueue();
  assert.equal(queue.shape, 'stream');
  assert.equal(queue.cursor, 0, 'the previous button is disabled, so nothing comes before');
  assert.deepEqual(queue.entries.map((entry) => entry.title), [
    VIDEO.title,
    'Village People - YMCA (OFFICIAL Music Video 1978)'
  ]);
  assert.equal(queue.entries[0].artwork, 'https://i.ytimg.com/vi/I_izvAbhExY/hqdefault.jpg');
  assert.equal(queue.entries[1].artwork, 'https://i.ytimg.com/vi/CS9OO0S5w2k/mqdefault.jpg');
  page.close();
});

test('a disabled next button leaves the queue with the current track alone', async () => {
  const page = watchPage();
  page.document.querySelector('.ytp-next-button').setAttribute('aria-disabled', 'true');
  page.document.querySelector('#related').remove();
  await page.waitFor(() => page.queues.length > 0);
  assert.deepEqual(page.lastQueue().entries.map((entry) => entry.title), [VIDEO.title]);
  page.close();
});

test('with no player button the next comes from the related list of the mobile page', async () => {
  const page = watchPage();
  page.document.querySelector('.ytp-next-button').remove();
  await page.waitFor(() => page.queues.length > 0);
  const titles = page.lastQueue().entries.map((entry) => entry.title);
  assert.equal(titles.length, 2);
  assert.match(titles[1], /Nobody Puts Baby In A Corner/);
  page.close();
});

test('picking the next item in the queue clicks the next button', async () => {
  const page = watchPage();
  const clicks = [];
  page.document.querySelector('.ytp-next-button').addEventListener('click', () => clicks.push('next'));
  page.fiezta.dispatch('queueItem', '1');
  assert.deepEqual(clicks, ['next']);
  page.close();
});

test('metadata comes from the player api of the page', async () => {
  const page = watchPage();
  await page.waitFor(() => page.readings.some((reading) => reading.title === VIDEO.title));
  const reading = page.lastReading();
  assert.equal(reading.title, VIDEO.title);
  assert.equal(reading.artist, VIDEO.author);
  page.close();
});

test('without the player api the page title still answers', async () => {
  const page = watchPage({ withPlayerApi: false });
  await page.waitFor(() => page.readings.length > 0);
  const reading = page.lastReading();
  assert.equal(reading.title, "Bee Gees - Stayin' Alive (Official Video) - YouTube");
  assert.equal(reading.artist, 'www.youtube.com');
  page.close();
});

test('next clicks the button the page offers', async () => {
  const page = watchPage();
  const clicks = [];
  page.document.querySelector('.ytp-next-button').addEventListener('click', () => clicks.push('next'));
  page.fiezta.dispatch('nextClick');
  assert.deepEqual(clicks, ['next']);
  page.close();
});

test('previous restarts the track when the page says there is none', async () => {
  const page = watchPage();
  const media = page.document.querySelector('video');
  media.currentTime = 42;
  page.fiezta.dispatch('previousClick');
  assert.equal(media.currentTime, 0);
  page.close();
});

test('the track id is the video id, so the same video is one track', async () => {
  const page = watchPage();
  await page.waitFor(() => page.readings.some((reading) => reading.trackId === 'I_izvAbhExY'));
  assert.equal(page.lastReading().trackId, 'I_izvAbhExY');
  page.close();
});

test('the same video under another url and another title is still one track', async () => {
  const localized = startPage({
    fixture: 'youtube-watch.html',
    url: 'https://m.youtube.com/watch?v=I_izvAbhExY&list=RDI_izvAbhExY&index=23&pp=8AUB',
    cores: MOBILE,
    plugin: ['youtube', 'watch.events.js']
  });
  localized.document.getElementById('movie_player').getVideoData =
    () => ({ ...VIDEO, title: "Bee Gees - Stayin' Alive (Vídeo oficial)" });
  localized.addMedia({});
  localized.fireMediaEvent(localized.document.querySelector('video'), 'loadedmetadata');
  await localized.waitFor(() => localized.readings.some((reading) => reading.trackId === 'I_izvAbhExY'));

  const plain = watchPage();
  await plain.waitFor(() => plain.readings.some((reading) => reading.trackId === 'I_izvAbhExY'));

  assert.equal(localized.lastReading().trackId, plain.lastReading().trackId);
  assert.notEqual(localized.lastReading().title, plain.lastReading().title);
  localized.close();
  plain.close();
});

test('each wheel key gives feedback of what it did', async () => {
  const page = watchPage();
  page.fiezta.dispatch('nextClick');
  page.fiezta.dispatch('previousClick');
  page.fiezta.dispatch('nextLongPress');
  page.fiezta.dispatch('previousLongPress');
  assert.deepEqual(page.feedbacks, [
    ['youtube:nextVideo', 'Next video'],
    ['youtube:restartingVideo', 'Restarting video'],
    ['youtube:seekingForward', 'Forward 10 seconds'],
    ['youtube:seekingBackward', 'Back 10 seconds']
  ]);
  page.close();
});
