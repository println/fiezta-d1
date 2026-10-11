(function() {
  if (window.fiezta) { return; }
  var handlers = {};
  var timers = [];
  var subscriptions = [];
  var reportedNext = null, reportedPrevious = null;
  var metadataProvider = null;
  var queueProvider = null;
  var lastReadingJson = null;
  var lastReadingPublishedAt = 0;
  var lastQueueJson = null;
  var capturedActionHandlers = {};
  var CAPTURED_ACTION_BY_EVENT = { nextClick: 'nexttrack', previousClick: 'previoustrack', seekTo: 'seekto' };
  var TRACK_CHANGE_GRACE_MILLIS = 15000;
  var stickyUrl = null;
  var wasEverAudibleOnThisUrl = false;
  var trackCarriedUntil = 0;

  function publishHandlers() {
    if (!window.fieztaplugins) { return; }
    var names = Object.keys(handlers);
    for (var event in CAPTURED_ACTION_BY_EVENT) {
      if (capturedActionHandlers[CAPTURED_ACTION_BY_EVENT[event]] && names.indexOf(event) === -1) {
        names.push(event);
      }
    }
    window.fieztaplugins.onHandlersChanged(names.join(','));
  }

  function area(media) {
    var rect = media.getBoundingClientRect();
    return rect.width * rect.height;
  }

  function isAudible(media) {
    return !media.paused && !media.muted && media.volume > 0;
  }

  function largest(list) {
    return list.reduce(function(best, media) { return (!best || area(media) > area(best)) ? media : best; }, null);
  }

  function electActiveMedia() {
    var current = window.__fieztaActiveMedia;
    if (current && document.contains(current) && !current.ended) { return current; }
    var all = Array.prototype.slice.call(document.querySelectorAll('video, audio'));
    var elected = largest(all.filter(isAudible)) || largest(all.filter(function(media) { return media.readyState > 0; }));
    window.__fieztaActiveMedia = elected;
    return elected;
  }

  window.__fieztaMedia = electActiveMedia;

  function pageTitleMetadata() {
    return { title: document.title, artist: location.hostname, artwork: '' };
  }

  function navigatorMetadata() {
    var metadata = navigator.mediaSession && navigator.mediaSession.metadata;
    if (!metadata) { return null; }
    var artwork = (metadata.artwork || []).reduce(function(best, art) {
      return (!best || (art.sizes || '') > (best.sizes || '')) ? art : best;
    }, null);
    return { title: metadata.title || '', artist: metadata.artist || '', artwork: artwork ? artwork.src : '' };
  }

  function siteSaysPlaying() {
    return !!(navigator.mediaSession && navigator.mediaSession.playbackState === 'playing');
  }

  function isTrack(media) {
    if (location.href !== stickyUrl) {
      if (wasEverAudibleOnThisUrl) { trackCarriedUntil = Date.now() + TRACK_CHANGE_GRACE_MILLIS; }
      stickyUrl = location.href;
      wasEverAudibleOnThisUrl = false;
    }
    if (media && isAudible(media)) { wasEverAudibleOnThisUrl = true; }
    return wasEverAudibleOnThisUrl || siteSaysPlaying() || Date.now() < trackCarriedUntil;
  }

  function currentMetadata() {
    var provided = (metadataProvider && metadataProvider()) || {};
    var fromSite = navigatorMetadata() || {};
    var fromPage = pageTitleMetadata();
    return {
      title: provided.title || fromSite.title || fromPage.title,
      artist: provided.artist || fromSite.artist || fromPage.artist,
      artwork: provided.artwork || fromSite.artwork || fromPage.artwork,
      id: provided.id || ''
    };
  }

  function buildReading() {
    var media = electActiveMedia();
    var metadata = currentMetadata();
    var track = isTrack(media);
    if (!media) {
      return {
        hasMedia: false,
        playing: siteSaysPlaying(),
        positionSeconds: 0,
        durationSeconds: 0,
        playbackRate: 1,
        title: metadata.title || '',
        artist: metadata.artist || '',
        artworkUrl: metadata.artwork || '',
        canSkipNext: canSkipNext(),
        canSkipPrevious: canSkipPrevious(),
        trackId: metadata.id || '',
        isTrack: track,
        pageUrl: location.href
      };
    }
    return {
      hasMedia: true,
      playing: !media.paused,
      positionSeconds: media.currentTime || 0,
      durationSeconds: isFinite(media.duration) ? media.duration : 0,
      playbackRate: media.playbackRate || 1,
      title: metadata.title || '',
      artist: metadata.artist || '',
      artworkUrl: metadata.artwork || '',
      canSkipNext: canSkipNext(),
      canSkipPrevious: canSkipPrevious(),
      trackId: metadata.id || '',
      isTrack: track,
      pageUrl: location.href
    };
  }

  function canSkipNext() {
    return reportedNext === null ? !!capturedActionHandlers.nexttrack : reportedNext;
  }

  function canSkipPrevious() {
    return reportedPrevious === null ? !!capturedActionHandlers.previoustrack : reportedPrevious;
  }

  function publishReading() {
    if (!window.mediacontrol) { return; }
    var reading = buildReading();
    var json = JSON.stringify(reading);
    var now = Date.now();
    if (json === lastReadingJson && !(reading.hasMedia && now - lastReadingPublishedAt >= 5000)) { return; }
    lastReadingJson = json;
    lastReadingPublishedAt = now;
    window.mediacontrol.onReading(json);
  }

  function currentTrackEntry() {
    var metadata = currentMetadata();
    if (!metadata.title) { return null; }
    return { title: metadata.title, subtitle: metadata.artist || '', artwork: metadata.artwork || '' };
  }

  function buildQueue() {
    var provided = queueProvider && queueProvider();
    if (!provided || !provided.shape || provided.shape === 'none') { return null; }
    var entries = (provided.entries || []).map(function(entry) {
      return { title: entry.title || '', subtitle: entry.subtitle || '', artwork: entry.artwork || '' };
    });
    var cursor = typeof provided.cursor === 'number' ? provided.cursor : -1;
    if (entries.length === 0) {
      var current = currentTrackEntry();
      if (!current) { return null; }
      entries = [current];
      cursor = 0;
    }
    return {
      shape: provided.shape,
      title: provided.title || '',
      entries: entries,
      cursor: cursor
    };
  }

  function publishQueue() {
    if (!window.mediacontrol) { return; }
    var queue = buildQueue();
    var json = JSON.stringify(queue);
    if (json === lastQueueJson) { return; }
    lastQueueJson = json;
    window.mediacontrol.onQueue(json);
  }

  window.setInterval(publishReading, 1000);
  window.setInterval(publishQueue, 1000);

  var lastTimeupdateAt = 0;

  function onMediaEvent(e) {
    if (!(e.target instanceof HTMLMediaElement)) { return; }
    publishReading();
  }

  document.addEventListener('playing', onMediaEvent, true);
  document.addEventListener('pause', onMediaEvent, true);
  document.addEventListener('ended', onMediaEvent, true);
  document.addEventListener('volumechange', onMediaEvent, true);
  document.addEventListener('seeked', onMediaEvent, true);
  document.addEventListener('durationchange', onMediaEvent, true);
  document.addEventListener('ratechange', onMediaEvent, true);
  document.addEventListener('timeupdate', function(e) {
    if (!(e.target instanceof HTMLMediaElement)) { return; }
    var now = Date.now();
    if (now - lastTimeupdateAt < 1000) { return; }
    lastTimeupdateAt = now;
    publishReading();
  }, true);

  if (navigator.mediaSession && navigator.mediaSession.setActionHandler) {
    var originalSetActionHandler = navigator.mediaSession.setActionHandler.bind(navigator.mediaSession);
    navigator.mediaSession.setActionHandler = function(action, handler) {
      if (handler) {
        capturedActionHandlers[action] = handler;
      } else {
        delete capturedActionHandlers[action];
      }
      publishHandlers();
      return originalSetActionHandler(action, handler);
    };
  }

  window.fiezta = {
    on: function(name, fn) {
      handlers[name] = fn;
      publishHandlers();
    },
    dispatch: function(name, argument) {
      var fn = handlers[name];
      if (fn) {
        fn(argument);
        return;
      }
      var captured = capturedActionHandlers[CAPTURED_ACTION_BY_EVENT[name]];
      if (captured) {
        var details = { action: CAPTURED_ACTION_BY_EVENT[name] };
        if (name === 'seekTo') { details.seekTime = Number(argument); }
        captured(details);
      }
    },
    setAvailable: function(available) {
      if ('next' in available) { reportedNext = !!available.next; }
      if ('previous' in available) { reportedPrevious = !!available.previous; }
      publishReading();
    },
    reportSearchResult: function(found) {
      if (window.mediacontrol) { window.mediacontrol.onSearchResult(!!found); }
    },
    setMetadataProvider: function(provider) {
      metadataProvider = provider;
    },
    setQueueProvider: function(provider) {
      queueProvider = provider;
    },
    matchesVoiceQuery: function(text) {
      var query = window.__fieztaVoiceSearch && window.__fieztaVoiceSearch.query;
      if (!query) { return false; }
      return normalizeVoiceQuery(text) === normalizeVoiceQuery(query);
    },
    toast: function(text) {
      if (window.fieztaplugins) { window.fieztaplugins.showToast(String(text)); }
    },
    setInterval: function(fn, ms) {
      var id = window.setInterval(fn, ms);
      timers.push(id);
      return id;
    },
    listen: function(name, fn) {
      subscriptions.push(window.fieztaPage.on(name, fn));
    },
    reset: function() {
      subscriptions.forEach(function(cancel) { cancel(); });
      subscriptions = [];
      for (var i = 0; i < timers.length; i++) { window.clearInterval(timers[i]); }
      timers = [];
      handlers = {};
      metadataProvider = null;
      queueProvider = null;
      reportedNext = null;
      reportedPrevious = null;
      lastReadingJson = null;
      lastQueueJson = null;
      capturedActionHandlers = {};
      publishHandlers();
    }
  };
  window.fiesta = window.fiezta;

  function normalizeVoiceQuery(text) {
    return String(text)
      .toLowerCase()
      .normalize('NFD')
      .replace(/[\u0300-\u036f]/g, '')
      .replace(/[^a-z0-9]+/g, ' ')
      .trim();
  }
})();
