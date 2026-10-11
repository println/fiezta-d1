(function() {
  if (window.__fieztaYoutubeSpy) { return; }
  window.__fieztaYoutubeSpy = { start: start };

  var SKIP_AD_SELECTOR = '.ytp-skip-ad-button, .ytp-ad-skip-button, .ytp-ad-skip-button-modern';
  var MEDIA_EVENTS = ['loadstart', 'loadedmetadata', 'durationchange', 'canplay', 'playing', 'pause', 'ended', 'waiting', 'volumechange', 'emptied'];
  var USER_EVENTS = ['touchstart', 'wheel', 'keydown'];
  var WATCHED_ATTRIBUTES = ['class', 'aria-disabled', 'aria-selected', 'selected', 'disabled', 'href', 'data-tooltip-text'];
  var SPONSORED_CONTAINERS = [
    'ytd-promoted-video-renderer', 'ytd-ad-slot-renderer', 'ytd-in-feed-ad-layout-renderer',
    'ytd-display-ad-renderer', 'ytd-promoted-sparkles-web-renderer', 'ytm-promoted-video-renderer',
    'ytm-companion-ad-renderer', 'ytm-ad-slot-renderer', '[is-ad]', '[data-ad-slot-id]'
  ].join(',');
  var SAFETY_NET_MILLIS = 2000;

  function start(layout) {
    if (window.__fieztaYoutubeSpyStarted) { return; }
    window.__fieztaYoutubeSpyStarted = true;
    var scheduled = false;
    var observedPlayer = null;
    var layoutObserver = new ResizeObserver(observeVisibilityGuarded);

    function emit(name, detail, elements, options) {
      window.fieztaPage.emit('youtube:' + name, detail, elements, options);
    }

    function guarded(fn) {
      try { fn(); } catch (e) { if (window.console && console.error) { console.error('youtube core failed', e); } }
    }

    function schedule() {
      if (scheduled) { return; }
      scheduled = true;
      Promise.resolve().then(function() {
        scheduled = false;
        recompute();
      });
    }

    function observeVisibilityGuarded() {
      guarded(observeVisibility);
    }

    function pageKind() {
      var path = location.pathname;
      if (path.indexOf('/watch') === 0) { return 'watch'; }
      if (path.indexOf('/shorts/') === 0) { return 'shorts'; }
      if (path.indexOf('/results') === 0) { return 'search'; }
      return 'other';
    }

    function urlParameter(name) {
      var match = location.search.match(new RegExp('[?&]' + name + '=([^&]+)'));
      return match ? decodeURIComponent(match[1]) : '';
    }

    function apiPlayer() { return document.getElementById('movie_player'); }

    function videoElement() { return document.querySelector('#movie_player video') || document.querySelector('video'); }

    function layoutPlayer() {
      for (var i = 0; i < layout.playerSelectors.length; i++) {
        var found = document.querySelector(layout.playerSelectors[i]);
        if (found) { return found; }
      }
      return document.querySelector('video');
    }

    function videoData() {
      var player = apiPlayer();
      var data = player && typeof player.getVideoData === 'function' ? player.getVideoData() : null;
      return data && data.video_id ? data : null;
    }

    function videoIdOf(href) {
      var match = String(href || '').match(/[?&]v=([^&]+)/);
      return match ? match[1] : '';
    }

    function observePage() {
      emit('pageChanged', { kind: pageKind(), url: location.href });
    }

    function currentTrack() {
      var kind = pageKind();
      if (kind === 'shorts') {
        var short = location.pathname.match(/\/shorts\/([^/?#]+)/);
        return short ? { videoId: short[1], title: '', author: '', playlistId: '' } : null;
      }
      if (kind !== 'watch') { return null; }
      var data = videoData();
      if (!data) { return null; }
      return { videoId: data.video_id, title: data.title || '', author: data.author || '', playlistId: urlParameter('list') };
    }

    function observeTrack() {
      var track = currentTrack();
      if (track) { emit('trackChanged', track, { video: videoElement(), player: apiPlayer() }); }
    }

    function playbackState(video) {
      if (video.ended) { return 'ended'; }
      if (video.paused) { return 'paused'; }
      return video.readyState < 3 ? 'buffering' : 'playing';
    }

    function observePlayerState() {
      var video = videoElement();
      if (!video) { return; }
      emit('playerStateChanged', { state: playbackState(video), muted: !!video.muted }, { video: video, player: apiPlayer() });
    }

    function observeAd() {
      var player = apiPlayer();
      var skipButton = document.querySelector(SKIP_AD_SELECTOR);
      var showing = !!(player && player.classList && player.classList.contains('ad-showing')) || !!skipButton;
      emit('adChanged', { showing: showing, skippable: !!skipButton }, {
        skipButton: skipButton,
        adVideo: document.querySelector('.ad-showing video')
      });
    }

    function isEnabled(button) {
      return button.getAttribute('aria-disabled') !== 'true' && !button.disabled;
    }

    function navigationButtons() {
      if (pageKind() === 'shorts') {
        var carousel = document.querySelectorAll('.ytShortsCarouselShortsA11yNavButton');
        return { previous: carousel[0] || null, next: carousel[1] || null };
      }
      var mobile = document.querySelectorAll('.player-middle-controls-prev-next-button');
      return {
        previous: document.querySelector('.ytp-prev-button') || mobile[0] || null,
        next: document.querySelector('.ytp-next-button') || mobile[1] || null
      };
    }

    function observeNavigation() {
      var buttons = navigationButtons();
      emit('navigationChanged', {
        next: buttons.next ? isEnabled(buttons.next) : null,
        previous: buttons.previous ? isEnabled(buttons.previous) : null
      }, { nextButton: buttons.next, previousButton: buttons.previous });
    }

    function textOf(item, selector) {
      var element = item.querySelector(selector);
      if (!element) { return ''; }
      return (element.getAttribute('title') || element.textContent || '').trim();
    }

    function panelTitle() {
      var header = document.querySelector(layout.panel.header);
      if (!header) { return ''; }
      return (header.innerText || header.textContent || '').split(String.fromCharCode(10))[0].trim();
    }

    function playlistQueue() {
      var items = [].slice.call(document.querySelectorAll(layout.panel.item));
      if (!items.length) { return null; }
      return {
        queue: {
          shape: 'list',
          title: panelTitle(),
          cursor: items.findIndex(layout.panel.isCurrent),
          entries: items.map(function(item) {
            var link = item.querySelector('a[href*="v="]');
            return {
              title: textOf(item, layout.panel.title),
              subtitle: textOf(item, layout.panel.byline),
              videoId: link ? videoIdOf(link.getAttribute('href')) : ''
            };
          })
        },
        itemLinks: items.map(function(item) { return item.querySelector(layout.panel.link); })
      };
    }

    function neighbourFromPlayerButton() {
      var button = document.querySelector('.ytp-next-button');
      if (!button || !isEnabled(button)) { return null; }
      var videoId = videoIdOf(button.getAttribute('href'));
      if (!videoId) { return null; }
      return { title: button.getAttribute('data-tooltip-text') || '', subtitle: '', videoId: videoId };
    }

    function neighbourFromRelatedList() {
      var item = document.querySelector('ytm-video-with-context-renderer');
      var link = item && item.querySelector('a[href*="v="]');
      var videoId = link && videoIdOf(link.getAttribute('href'));
      if (!videoId) { return null; }
      var headline = item.querySelector('.media-item-headline');
      var byline = item.querySelector('.ytmBadgeAndBylineRendererItemByline');
      return {
        title: headline ? headline.textContent.trim() : '',
        subtitle: byline ? byline.textContent.trim() : '',
        videoId: videoId
      };
    }

    function streamQueue() {
      var track = currentTrack();
      if (!track || pageKind() === 'shorts') { return { shape: 'stream', title: '', cursor: -1, entries: [] }; }
      var next = neighbourFromPlayerButton() || neighbourFromRelatedList();
      var current = { title: track.title, subtitle: track.author, videoId: track.videoId };
      return { shape: 'stream', title: '', cursor: 0, entries: next ? [current, next] : [current] };
    }

    function observeQueue() {
      var kind = pageKind();
      if (kind !== 'watch' && kind !== 'shorts') {
        emit('queueChanged', { shape: 'none', title: '', cursor: -1, entries: [] }, { itemLinks: [] });
        return;
      }
      var playlist = kind === 'watch' ? playlistQueue() : null;
      emit('queueChanged', playlist ? playlist.queue : streamQueue(), { itemLinks: playlist ? playlist.itemLinks : [] });
    }

    function fixedTopBarBottom() {
      var bar = document.querySelector(layout.topBarSelector);
      if (!bar) { return 0; }
      var position = getComputedStyle(bar).position;
      if (position !== 'fixed' && position !== 'sticky') { return 0; }
      return Math.max(0, bar.getBoundingClientRect().bottom);
    }

    function observeVisibility() {
      var player = layoutPlayer();
      if (!player) { return; }
      if (observedPlayer !== player) {
        observedPlayer = player;
        layoutObserver.observe(player);
      }
      var rect = player.getBoundingClientRect();
      if (rect.height === 0) { return; }
      var above = Math.floor(rect.top - fixedTopBarBottom());
      var below = Math.ceil(rect.bottom - window.innerHeight);
      emit('playerVisibilityChanged', {
        visible: above >= 0 && below <= 0,
        hiddenAbove: Math.max(0, -above),
        hiddenBelow: Math.max(0, below)
      }, { player: player });
    }

    function isSponsored(link) {
      if (link.closest(SPONSORED_CONTAINERS)) { return true; }
      var result = link.closest('ytd-video-renderer, ytd-rich-item-renderer, ytm-video-with-context-renderer');
      return !!result && !!result.querySelector(
        '#ad-badge, ytd-ad-badge-renderer, .badge-style-type-ad, [aria-label="Sponsored"], [aria-label="Patrocinado"]'
      );
    }

    function firstVideoLink() {
      var links = document.querySelectorAll('a[href*="/watch?v="]');
      for (var i = 0; i < links.length; i++) {
        if (links[i].offsetParent !== null && !isSponsored(links[i])) { return links[i]; }
      }
      return null;
    }

    function observeSearchResults() {
      var link = pageKind() === 'search' ? firstVideoLink() : null;
      emit('searchResultsChanged', { firstVideoUrl: link ? link.href : null }, { firstVideoLink: link });
    }

    function recompute() {
      [observePage, observeTrack, observePlayerState, observeAd, observeNavigation,
        observeQueue, observeVisibility, observeSearchResults].forEach(guarded);
    }

    new MutationObserver(schedule).observe(document.documentElement, {
      childList: true, subtree: true, attributes: true, attributeFilter: WATCHED_ATTRIBUTES
    });
    layout.nativeEvents.forEach(function(type) { window.addEventListener(type, schedule, true); });
    MEDIA_EVENTS.forEach(function(type) { document.addEventListener(type, schedule, true); });
    USER_EVENTS.forEach(function(type) {
      window.addEventListener(type, function() {
        emit('userInteracted', { at: Date.now() }, {}, { moment: true });
      }, { capture: true, passive: true });
    });
    window.addEventListener('scroll', observeVisibilityGuarded, { passive: true });
    window.addEventListener('resize', observeVisibilityGuarded);
    layoutObserver.observe(document.documentElement);
    window.setInterval(schedule, SAFETY_NET_MILLIS);
    recompute();
  }
})();
