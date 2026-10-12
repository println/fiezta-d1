import test from 'node:test';
import assert from 'node:assert/strict';
import { startPage } from './harness.mjs';

const CORES = [['youtube', 'core.common.js'], ['youtube', 'core.mobile.js']];
const PLAYER_HEIGHT = 200;
const SCREEN_HEIGHT = 400;

function watchPage({ screenHeight = SCREEN_HEIGHT } = {}) {
  const page = startPage({
    fixture: 'youtube-playlist.html',
    url: 'https://m.youtube.com/watch?v=I_izvAbhExY&list=RDI_izvAbhExY',
    cores: CORES
  });
  const { window } = page;
  let scrollY = 0;
  const player = page.document.getElementById('player-container-id');
  player.getBoundingClientRect = () => ({
    top: -scrollY, bottom: PLAYER_HEIGHT - scrollY, height: PLAYER_HEIGHT, width: 300, left: 0, right: 300
  });
  Object.defineProperty(window, 'innerHeight', { value: screenHeight });
  let scrolls = 0;
  page.scrolls = () => scrolls;
  window.scrollBy = (_x, y) => {
    scrolls += 1;
    scrollY += y;
    window.dispatchEvent(new window.Event('scroll'));
  };
  page.siteScrollsTo = (y) => window.scrollBy(0, y - scrollY);
  page.scrollY = () => scrollY;
  page.player = player;
  page.load('youtube', 'player-scroll.tweak.js');
  return page;
}

function selectTrack(page, index) {
  const items = page.document.querySelectorAll('ytm-playlist-panel-video-renderer');
  items.forEach((item, position) => item.setAttribute('aria-selected', String(position === index)));
}

test('the player comes back after the site scrolls the new track into view', async () => {
  const page = watchPage();
  selectTrack(page, 1);
  await page.waitFor(() => page.bus.last('youtube:queueChanged').cursor === 1);
  page.siteScrollsTo(307);
  assert.equal(page.scrollY(), 0, 'the player is shown again, wherever the site pushed the page');
  page.close();
});

test('a touch before the track change does not leave the player hidden', async () => {
  const page = watchPage();
  page.window.dispatchEvent(new page.window.Event('touchstart'));
  page.siteScrollsTo(307);
  assert.equal(page.scrollY(), 307, 'the user took over the scroll');

  selectTrack(page, 1);
  await page.waitFor(() => page.bus.last('youtube:queueChanged').cursor === 1);
  assert.equal(page.scrollY(), 0, 'the next track brings the player back');
  page.close();
});

test('the scroll of the user is respected until the next track', async () => {
  const page = watchPage();
  page.window.dispatchEvent(new page.window.Event('wheel'));
  page.siteScrollsTo(150);
  assert.equal(page.scrollY(), 150);
  page.close();
});

test('a player taller than the screen stays with its top in view instead of going up and down', () => {
  const page = watchPage({ screenHeight: PLAYER_HEIGHT - 50 });
  const before = page.scrolls();
  page.siteScrollsTo(120);
  assert.equal(page.scrollY(), 0);
  assert.equal(page.scrolls() - before, 2, 'the scroll of the site and the one that undoes it');
  page.close();
});

test('nothing moves outside a watch page', () => {
  const page = startPage({
    fixture: 'youtube-playlist.html',
    url: 'https://m.youtube.com/results?search_query=abba',
    cores: CORES
  });
  let scrolled = 0;
  page.window.scrollBy = () => { scrolled += 1; };
  page.load('youtube', 'player-scroll.tweak.js');
  assert.equal(scrolled, 0);
  page.close();
});
