import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { JSDOM } from 'jsdom';

const here = dirname(fileURLToPath(import.meta.url));
const assets = join(here, '..', '..', 'app', 'src', 'main', 'assets', 'plugins');

export function readFixture(name) {
  return readFileSync(join(here, 'fixtures', name), 'utf8');
}

function scriptOf(...parts) {
  return readFileSync(join(assets, ...parts), 'utf8');
}

// jsdom has no layout, so every element measures 0x0 and the runtime's "largest media
// wins" election would pick nothing. Tests declare a size per element instead.
function installLayout(window) {
  window.HTMLMediaElement.prototype.getBoundingClientRect = function () {
    const width = Number(this.dataset.width || 0);
    const height = Number(this.dataset.height || 0);
    return { width, height, top: 0, left: 0, right: width, bottom: height };
  };
}

// jsdom does not implement innerText. This approximation puts a line break between
// block-level children, which is all the plugins rely on when reading page headers.
function installInnerText(window) {
  Object.defineProperty(window.Element.prototype, 'innerText', {
    get() {
      const children = Array.from(this.children);
      if (children.length === 0) return this.textContent;
      return children.map((child) => child.textContent.trim()).filter(Boolean).join('\n');
    }
  });
}

// The runtime wraps navigator.mediaSession.setActionHandler to learn what the site
// itself supports, so the object has to exist before the runtime is evaluated.
function installMediaSession(window) {
  const handlers = {};
  window.navigator.mediaSession = {
    metadata: null,
    playbackState: 'none',
    setActionHandler(action, handler) {
      if (handler) handlers[action] = handler; else delete handlers[action];
    },
    handlers
  };
}

export function startPage({ fixture, url = 'https://m.youtube.com/', plugin = null, cores = [] } = {}) {
  const dom = new JSDOM(fixture ? readFixture(fixture) : '<!DOCTYPE html><html><body></body></html>', {
    url,
    runScripts: 'outside-only',
    pretendToBeVisual: true
  });
  const { window } = dom;
  installLayout(window);
  installInnerText(window);
  installMediaSession(window);

  const readings = [];
  const queues = [];
  const searchResults = [];
  const handlerNames = [];
  window.mediacontrol = {
    onReading: (json) => readings.push(JSON.parse(json)),
    onQueue: (json) => queues.push(JSON.parse(json)),
    onSearchResult: (found) => searchResults.push(found)
  };
  window.fieztaplugins = {
    onHandlersChanged: (names) => handlerNames.push(names === '' ? [] : names.split(','))
  };

  window.eval(scriptOf('fiezta-page.js'));
  cores.forEach((core) => window.eval(scriptOf(...core)));
  window.eval(scriptOf('fiezta-runtime.js'));
  if (plugin) window.eval(scriptOf(...plugin));

  return {
    window,
    document: window.document,
    fiezta: window.fiezta,
    bus: window.fieztaPage,
    readings,
    queues,
    searchResults,
    handlerNames,
    lastReading: () => readings[readings.length - 1],
    lastQueue: () => queues[queues.length - 1],
    lastHandlers: () => handlerNames[handlerNames.length - 1] || [],
    addMedia: ({ width = 640, height = 360, paused = false, currentTime = 0, duration = 100 } = {}) => {
      const media = window.document.createElement('video');
      media.dataset.width = String(width);
      media.dataset.height = String(height);
      Object.defineProperty(media, 'paused', { value: paused, writable: true });
      Object.defineProperty(media, 'duration', { value: duration, writable: true });
      Object.defineProperty(media, 'readyState', { value: 4, writable: true });
      media.currentTime = currentTime;
      media.volume = 1;
      window.document.body.appendChild(media);
      return media;
    },
    mediaSession: window.navigator.mediaSession,
    setSiteMetadata: (metadata) => { window.navigator.mediaSession.metadata = metadata; },
    fireMediaEvent: (media, type) => media.dispatchEvent(new window.Event(type, { bubbles: true })),
    navigateTo: (href) => dom.reconfigure({ url: href }),
    advanceClock: (millis) => {
      const now = window.Date.now();
      window.Date.now = () => now + millis;
    },
    waitFor: async (predicate, timeoutMs = 2000) => {
      const deadline = Date.now() + timeoutMs;
      while (Date.now() < deadline) {
        if (predicate()) return true;
        await new Promise((resolve) => setTimeout(resolve, 25));
      }
      throw new Error('timed out waiting for the runtime to publish');
    },
    close: () => dom.window.close()
  };
}
