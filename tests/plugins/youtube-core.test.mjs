import test from 'node:test';
import assert from 'node:assert/strict';
import { startPage } from './harness.mjs';

const MOBILE_CORES = [['youtube', 'core.common.js'], ['youtube', 'core.mobile.js']];
const DESKTOP_CORES = [['youtube', 'core.common.js'], ['youtube', 'core.desktop.js']];
const VIDEO = { title: 'Queen - Radio Ga Ga (Official Video)', author: 'Queen Official', video_id: 'azdwsXLmrHE' };
const WATCHED = [
  'pageChanged', 'trackChanged', 'playerStateChanged', 'adChanged', 'navigationChanged',
  'queueChanged', 'playerVisibilityChanged', 'searchResultsChanged', 'userInteracted'
];

function corePage({ fixture, url, cores }) {
  const page = startPage({ fixture, url, cores: [] });
  const player = page.document.getElementById('movie_player');
  if (player) player.getVideoData = () => VIDEO;
  const events = {};
  WATCHED.forEach((name) => page.bus.on('youtube:' + name, (detail, elements) => {
    (events[name] = events[name] || []).push({ detail: JSON.parse(JSON.stringify(detail)), elements });
  }));
  cores.forEach((core) => page.load(...core));
  page.events = events;
  page.last = (name) => (events[name] || [])[(events[name] || []).length - 1];
  return page;
}

const mobile = (fixture, url = 'https://m.youtube.com/watch?v=azdwsXLmrHE&list=RDI_izvAbhExY') =>
  corePage({ fixture, url, cores: MOBILE_CORES });
const desktop = (fixture, url = 'https://www.youtube.com/watch?v=azdwsXLmrHE&list=RDI_izvAbhExY&index=2') =>
  corePage({ fixture, url, cores: DESKTOP_CORES });

const SITES = [
  { site: 'mobile', open: mobile, fixture: 'youtube-playlist.html', item: 'ytm-playlist-panel-video-renderer',
    select: (item, on) => item.setAttribute('aria-selected', String(on)) },
  { site: 'desktop', open: desktop, fixture: 'youtube-playlist-desktop.html', item: 'ytd-playlist-panel-video-renderer',
    select: (item, on) => (on ? item.setAttribute('selected', '') : item.removeAttribute('selected')) }
];

for (const { site, open, fixture, item, select } of SITES) {
  test(`${site}: the playlist is announced as a queue with its links`, () => {
    const page = open(fixture);
    const queue = page.last('queueChanged').detail;
    assert.equal(queue.shape, 'list');
    assert.equal(queue.title, "Mix de Bee Gees - Stayin' Alive (Official Video)");
    assert.equal(queue.entries.length, 3);
    assert.equal(queue.entries[1].videoId, 'azdwsXLmrHE');
    assert.equal(page.last('queueChanged').elements.itemLinks.length, 3);
    page.close();
  });

  test(`${site}: a new selection moves the cursor without a timer`, async () => {
    const page = open(fixture);
    const items = page.document.querySelectorAll(item);
    const first = page.last('queueChanged').detail.cursor;
    select(items[first], false);
    select(items[2], true);
    await page.waitFor(() => page.last('queueChanged').detail.cursor === 2, 500);
    page.close();
  });

  test(`${site}: the page kind and the playlist id come from the url`, () => {
    const page = open(fixture);
    assert.equal(page.last('pageChanged').detail.kind, 'watch');
    assert.equal(new URL(page.last('pageChanged').detail.url).searchParams.get('list'), 'RDI_izvAbhExY');
    page.close();
  });
}

test('the track carries the playlist id of the url', () => {
  const page = desktop('youtube-playlist-desktop.html');
  assert.equal(page.last('trackChanged').detail.playlistId, 'RDI_izvAbhExY');
  page.close();
});

test('a late subscriber still gets the track', () => {
  const page = desktop('youtube-playlist-desktop.html');
  let seen = null;
  page.bus.on('youtube:trackChanged', (detail) => { seen = detail; });
  assert.equal(seen.videoId, VIDEO.video_id);
  assert.equal(seen.title, VIDEO.title);
  page.close();
});

test('the next and previous buttons say what the player allows', async () => {
  const page = desktop('youtube-playlist-desktop.html');
  assert.deepEqual(page.last('navigationChanged').detail, { next: true, previous: true });
  const next = page.document.querySelector('.ytp-next-button');
  next.setAttribute('aria-disabled', 'true');
  await page.waitFor(() => page.last('navigationChanged').detail.next === false, 500);
  assert.equal(page.last('navigationChanged').elements.nextButton, next);
  page.close();
});

test('an ad is announced with its skip button, and ends', async () => {
  const page = corePage({ fixture: 'youtube-watch.html', url: 'https://www.youtube.com/watch?v=I_izvAbhExY', cores: DESKTOP_CORES });
  assert.deepEqual(page.last('adChanged').detail, { showing: false, skippable: false });
  const player = page.document.getElementById('movie_player');
  const skip = page.document.createElement('button');
  skip.className = 'ytp-skip-ad-button';
  player.appendChild(skip);
  player.classList.add('ad-showing');
  await page.waitFor(() => page.last('adChanged').detail.showing, 500);
  assert.equal(page.last('adChanged').detail.skippable, true);
  assert.equal(page.last('adChanged').elements.skipButton, skip);
  skip.remove();
  player.classList.remove('ad-showing');
  await page.waitFor(() => !page.last('adChanged').detail.showing, 500);
  page.close();
});

test('the player state follows the media events', async () => {
  const page = corePage({ fixture: 'youtube-watch.html', url: 'https://www.youtube.com/watch?v=I_izvAbhExY', cores: DESKTOP_CORES });
  const video = page.document.querySelector('video');
  Object.defineProperty(video, 'paused', { value: false, configurable: true });
  Object.defineProperty(video, 'readyState', { value: 4, configurable: true });
  video.muted = true;
  page.fireMediaEvent(video, 'playing');
  await page.waitFor(() => page.last('playerStateChanged').detail.state === 'playing', 500);
  assert.equal(page.last('playerStateChanged').detail.muted, true);
  page.close();
});

test('shorts: the track is the short in the url and the queue is a stream', () => {
  const page = corePage({ fixture: 'youtube-shorts.html', url: 'https://www.youtube.com/shorts/abc123', cores: DESKTOP_CORES });
  assert.equal(page.last('trackChanged').detail.videoId, 'abc123');
  assert.equal(page.last('queueChanged').detail.shape, 'stream');
  assert.deepEqual(page.last('navigationChanged').detail, { next: true, previous: false });
  page.close();
});

test('the player scrolled out of sight is reported with how far', () => {
  const page = desktop('youtube-playlist-desktop.html');
  const player = page.document.getElementById('movie_player');
  player.getBoundingClientRect = () => ({ top: -120, bottom: 80, height: 200, width: 300, left: 0, right: 300 });
  page.window.dispatchEvent(new page.window.Event('scroll'));
  assert.deepEqual(page.last('playerVisibilityChanged').detail, { visible: false, hiddenAbove: 120, hiddenBelow: 0, roomAbove: 0 });
  assert.equal(page.last('playerVisibilityChanged').elements.player, player);
  page.close();
});

test('touching and typing are moments, not states', () => {
  const page = desktop('youtube-playlist-desktop.html');
  page.window.dispatchEvent(new page.window.Event('touchstart'));
  page.window.dispatchEvent(new page.window.Event('keydown'));
  assert.equal(page.events.userInteracted.length, 2);
  let late = 0;
  page.bus.on('youtube:userInteracted', () => { late += 1; });
  assert.equal(late, 0);
  page.close();
});

test('on a search page the first organic result is announced', async () => {
  const page = corePage({ fixture: null, url: 'https://www.youtube.com/results?search_query=abba', cores: DESKTOP_CORES });
  page.document.body.innerHTML =
    '<ytd-video-renderer><a href="/watch?v=ad"></a><ytd-ad-badge-renderer></ytd-ad-badge-renderer></ytd-video-renderer>' +
    '<ytd-video-renderer><a href="/watch?v=abba1"></a></ytd-video-renderer>';
  page.document.querySelectorAll('a').forEach((link) => Object.defineProperty(link, 'offsetParent', { value: page.document.body }));
  await page.waitFor(() => page.last('searchResultsChanged').detail.firstVideoUrl !== null, 500);
  assert.match(page.last('searchResultsChanged').detail.firstVideoUrl, /v=abba1$/);
  page.close();
});

test('while the player still holds the previous video the track is not announced', async () => {
  const page = desktop('youtube-playlist-desktop.html', 'https://www.youtube.com/watch?v=r36UCU3ndIM&list=RDI_izvAbhExY&index=3');
  assert.equal(page.events.trackChanged, undefined);
  page.document.getElementById('movie_player').getVideoData = () => ({ ...VIDEO, video_id: 'r36UCU3ndIM' });
  page.fireMediaEvent(page.document.querySelector('video'), 'loadedmetadata');
  await page.waitFor(() => page.last('trackChanged'), 500);
  assert.equal(page.last('trackChanged').detail.videoId, 'r36UCU3ndIM');
  page.close();
});

test('desktop: the queue title is read from the header the site renders today', async () => {
  const page = desktop('youtube-playlist-desktop.html');
  const oldHeader = page.document.querySelector('#playlist-title');
  const header = page.document.createElement('h3');
  header.innerHTML = '<yt-attributed-string>Mix de Queen</yt-attributed-string>';
  oldHeader.replaceWith(header);
  await page.waitFor(() => page.last('queueChanged').detail.title === 'Mix de Queen', 500);
  page.close();
});

test('desktop shorts: the video and the buttons are the ones of the shorts player', async () => {
  const page = corePage({ fixture: 'youtube-watch.html', url: 'https://www.youtube.com/shorts/abc123', cores: DESKTOP_CORES });
  page.document.body.insertAdjacentHTML('beforeend',
    '<div id="shorts-player"><video></video></div>' +
    '<div id="navigation-button-up"><button disabled></button></div>' +
    '<div id="navigation-button-down"><button></button></div>');
  const short = page.document.querySelector('#shorts-player video');
  Object.defineProperty(short, 'paused', { value: false, configurable: true });
  Object.defineProperty(short, 'readyState', { value: 4, configurable: true });
  page.fireMediaEvent(short, 'playing');
  await page.waitFor(() => page.last('playerStateChanged').detail.state === 'playing', 500);
  assert.equal(page.last('playerStateChanged').elements.video, short);
  assert.deepEqual(page.last('navigationChanged').detail, { next: true, previous: false });
  page.close();
});

test('mobile: the buttons leaving the screen with the controls do not change what the player allows', async () => {
  const page = corePage({ fixture: 'youtube-watch.html', url: 'https://m.youtube.com/watch?v=azdwsXLmrHE', cores: MOBILE_CORES });
  page.document.querySelectorAll('.ytp-prev-button, .ytp-next-button').forEach((button) => button.remove());
  page.document.body.insertAdjacentHTML('beforeend',
    '<button class="player-middle-controls-prev-next-button" aria-disabled="true"></button>' +
    '<button class="player-middle-controls-prev-next-button"></button>');
  await page.waitFor(() => page.last('navigationChanged').detail.next === true, 500);
  const announced = page.events.navigationChanged.length;

  page.document.querySelectorAll('.player-middle-controls-prev-next-button').forEach((button) => button.remove());
  await page.waitFor(() => page.bus.lastElements('youtube:navigationChanged').nextButton === null, 500);
  assert.deepEqual(page.last('navigationChanged').detail, { next: true, previous: false });
  assert.equal(page.events.navigationChanged.length, announced);
  page.close();
});

test('a link of the miniplayer is not a search result', async () => {
  const page = corePage({ fixture: null, url: 'https://www.youtube.com/results?search_query=abba', cores: DESKTOP_CORES });
  page.document.body.innerHTML =
    '<ytd-miniplayer><a href="/watch?v=playing"></a></ytd-miniplayer>' +
    '<ytd-video-renderer><a href="/watch?v=abba1"></a></ytd-video-renderer>';
  page.document.querySelectorAll('a').forEach((link) => Object.defineProperty(link, 'offsetParent', { value: page.document.body }));
  await page.waitFor(() => page.last('searchResultsChanged').detail.firstVideoUrl !== null, 500);
  assert.match(page.last('searchResultsChanged').detail.firstVideoUrl, /v=abba1$/);
  page.close();
});

test('the core starts once per document', () => {
  const page = desktop('youtube-playlist-desktop.html');
  const before = page.events.pageChanged.length;
  page.window.eval("window.__fieztaYoutubeSpy.start({ playerSelectors: [], topBarSelector: 'x', nativeEvents: [], panel: {} })");
  assert.equal(page.events.pageChanged.length, before);
  page.close();
});
