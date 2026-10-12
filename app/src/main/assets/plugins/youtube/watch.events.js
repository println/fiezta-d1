(function() {
  var bus = window.fieztaPage;
  var track = null;
  var navigation = { next: null, previous: null };
  var ad = { showing: false, skippable: false };
  var queue = null;

  function elementsOf(name) { return bus.lastElements('youtube:' + name) || {}; }

  function publishAvailability() {
    var available = {};
    if (ad.showing) {
      available.next = true;
    } else if (navigation.next !== null) {
      available.next = navigation.next;
    }
    if (navigation.previous !== null) { available.previous = navigation.previous; }
    fiezta.setAvailable(available);
  }

  function requestVoicePlayback() {
    var context = window.__fieztaVoiceSearch;
    if (!context) { return; }
    var requested = false;
    var progress = function(stage) {
      if (window.fieztaplugins) {
        window.fieztaplugins.onVoiceSearchProgress(String(context.id), String(context.generation), stage);
      }
    };
    fiezta.listen('youtube:playerStateChanged', function(detail, elements) {
      if (requested || !elements.video) { return; }
      requested = true;
      var result = elements.video.play();
      if (result && typeof result.then === 'function') {
        result.then(function() { progress('playRequested'); }, function() { progress('failed'); });
      } else {
        progress('playRequested');
      }
    });
  }

  function thumbnailOf(videoId, quality) {
    return videoId ? 'https://i.ytimg.com/vi/' + videoId + '/' + quality + 'default.jpg' : '';
  }

  fiezta.on('nextClick', function() {
    var ads = elementsOf('adChanged');
    if (ads.skipButton) { ads.skipButton.click(); return; }
    if (ads.adVideo && isFinite(ads.adVideo.duration)) { ads.adVideo.currentTime = ads.adVideo.duration; return; }
    var next = elementsOf('navigationChanged').nextButton;
    if (next) {
      if (navigation.next) { next.click(); }
      return;
    }
    var player = elementsOf('trackChanged').player;
    if (player && typeof player.nextVideo === 'function') { player.nextVideo(); }
  });

  fiezta.on('previousClick', function() {
    var previous = elementsOf('navigationChanged').previousButton;
    if (previous && navigation.previous) {
      previous.click();
    } else {
      var video = elementsOf('playerStateChanged').video;
      if (video) { video.currentTime = 0; }
    }
  });

  function tapPlayerSide(forward) {
    var states = elementsOf('playerStateChanged');
    if (!states.player || !states.controlsSurface) { return false; }
    var bounds = states.player.getBoundingClientRect();
    states.controlsSurface.dispatchEvent(new MouseEvent('click', {
      bubbles: true, cancelable: true, composed: true, view: window,
      clientX: bounds.left + bounds.width * (forward ? 0.9 : 0.1),
      clientY: bounds.top + bounds.height / 2
    }));
    return true;
  }
  function doubleTapSeek(forward) {
    if (!tapPlayerSide(forward)) {
      var video = elementsOf('playerStateChanged').video;
      if (video) { video.currentTime += forward ? 10 : -10; }
      return;
    }
    setTimeout(function() { tapPlayerSide(forward); }, 200);
  }

  fiezta.on('nextLongPress', function() { doubleTapSeek(true); });
  fiezta.on('previousLongPress', function() { doubleTapSeek(false); });

  function queueEntry(entry, quality) {
    return { title: entry.title, subtitle: entry.subtitle, artwork: thumbnailOf(entry.videoId, quality) };
  }
  function queueForRuntime() {
    if (!queue) { return { shape: 'stream' }; }
    if (queue.shape === 'list') {
      return {
        shape: 'list',
        title: queue.title,
        cursor: queue.cursor,
        entries: queue.entries.map(function(entry) { return queueEntry(entry, 'mq'); })
      };
    }
    return {
      shape: 'stream',
      cursor: queue.cursor,
      entries: queue.entries.map(function(entry, index) { return queueEntry(entry, index === queue.cursor ? 'hq' : 'mq'); })
    };
  }

  fiezta.setQueueProvider(queueForRuntime);
  fiezta.on('queueItem', function(index) {
    var link = (elementsOf('queueChanged').itemLinks || [])[Number(index)];
    if (link) {
      link.click();
    } else if (Number(index) > 0) {
      fiezta.dispatch('nextClick');
    }
  });
  fiezta.setMetadataProvider(function() {
    if (!track || !track.title) { return null; }
    return { id: track.videoId, title: track.title, artist: track.author, artwork: thumbnailOf(track.videoId, 'hq') };
  });

  fiezta.listen('youtube:trackChanged', function(detail) { track = detail; });
  fiezta.listen('youtube:navigationChanged', function(detail) { navigation = detail; publishAvailability(); });
  fiezta.listen('youtube:adChanged', function(detail) { ad = detail; publishAvailability(); });
  fiezta.listen('youtube:queueChanged', function(detail) { queue = detail; });
  requestVoicePlayback();
})();
