(function() {
  if (!window.fieztaPage || window.__csPlayerScroll) { return; }
  window.__csPlayerScroll = true;

  var keeping = false;
  var onWatch = false;
  var videoId = null;
  var cursor = null;
  var visibility = null;
  var told = false;

  window.fieztaPage.on('youtube:pageChanged', function(detail) {
    onWatch = detail.kind === 'watch';
    reveal();
  });
  window.fieztaPage.on('youtube:trackChanged', function(detail) {
    if (detail.videoId === videoId) { return; }
    videoId = detail.videoId;
    keepInView();
  });
  window.fieztaPage.on('youtube:queueChanged', function(detail) {
    if (detail.cursor === cursor) { return; }
    cursor = detail.cursor;
    keepInView();
  });
  window.fieztaPage.on('youtube:playerVisibilityChanged', function(detail) {
    visibility = detail;
    reveal();
  });
  window.fieztaPage.on('youtube:userInteracted', function() { keeping = false; });

  function keepInView() {
    keeping = true;
    told = false;
    reveal();
  }

  function tellScroll() {
    if (told) { return; }
    told = true;
    window.fieztaPage.feedback('youtube:scrollingToPlayer', 'Scrolling to the player');
  }

  function reveal() {
    if (!keeping || !onWatch || !visibility) { return; }
    if (visibility.hiddenAbove > 0) {
      tellScroll();
      window.scrollBy(0, -visibility.hiddenAbove);
    } else if (visibility.hiddenBelow > 0 && visibility.roomAbove > 0) {
      tellScroll();
      window.scrollBy(0, Math.min(visibility.hiddenBelow, visibility.roomAbove));
    }
  }
})();
