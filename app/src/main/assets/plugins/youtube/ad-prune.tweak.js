(function() {
  if (window.__csAdPrune) { return; }
  window.__csAdPrune = true;

  var keys = ['adPlacements', 'playerAds', 'adSlots', 'adBreakHeartbeatParams'];
  var origParse = JSON.parse;
  JSON.parse = function() {
    var result = origParse.apply(this, arguments);
    if (result && typeof result === 'object') {
      for (var i = 0; i < keys.length; i++) {
        if (keys[i] in result) { delete result[keys[i]]; }
      }
    }
    return result;
  };

  if (!window.fieztaPage) { return; }

  var told = false;

  function tellSkip() {
    if (told) { return; }
    told = true;
    window.fieztaPage.feedback('youtube:skippingAd', 'Skipping ad');
  }

  function skipAd(elements) {
    var video = elements.adVideo;
    if (video && isFinite(video.duration) && video.duration > 0) {
      try { video.currentTime = video.duration; } catch (e) {}
    }
    if (elements.skipButton) { elements.skipButton.click(); }
  }

  window.fieztaPage.on('youtube:adChanged', function(detail, elements) {
    if (!detail.showing) {
      told = false;
      return;
    }
    tellSkip();
    skipAd(elements);
    var video = elements.adVideo;
    if (video && !video.__fieztaAdPrune) {
      video.__fieztaAdPrune = true;
      ['durationchange', 'timeupdate'].forEach(function(type) {
        video.addEventListener(type, function() {
          if (window.fieztaPage.last('youtube:adChanged').showing) { skipAd(window.fieztaPage.lastElements('youtube:adChanged')); }
        });
      });
    }
  });
})();
