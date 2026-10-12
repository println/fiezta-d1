import test from 'node:test';
import assert from 'node:assert/strict';
import { startPage } from './harness.mjs';

const CORES = [['youtube', 'core.common.js'], ['youtube', 'core.desktop.js']];

function searchPage() {
  const page = startPage({ url: 'https://www.youtube.com/results?search_query=abba', cores: CORES });
  const stages = [];
  page.window.fieztaplugins.onVoiceSearchProgress = (id, generation, stage) => stages.push(stage);
  page.window.__fieztaVoiceSearch = { id: 1, generation: 1, query: 'abba' };
  page.stages = stages;
  return page;
}

function addResult(page, href) {
  const renderer = page.document.createElement('ytd-video-renderer');
  const link = page.document.createElement('a');
  link.href = href;
  Object.defineProperty(link, 'offsetParent', { value: page.document.body });
  renderer.appendChild(link);
  page.document.body.appendChild(renderer);
}

test('the first organic result is reported once it shows up, with no fixed wait', async () => {
  const page = searchPage();
  page.load('youtube', 'voice-search.js');
  assert.deepEqual(page.stages, ['confirmed']);
  addResult(page, '/watch?v=abba1');
  await page.waitFor(() => page.searchResults.length > 0, 500);
  assert.deepEqual(page.searchResults, [true]);
  assert.deepEqual(page.stages, ['confirmed', 'result']);
  page.close();
});

test('a result that was already on the page is used at once', () => {
  const page = searchPage();
  addResult(page, '/watch?v=abba1');
  return page.waitFor(() => page.bus.last('youtube:searchResultsChanged').firstVideoUrl !== null, 500).then(() => {
    page.load('youtube', 'voice-search.js');
    assert.deepEqual(page.searchResults, [true]);
    page.close();
  });
});
