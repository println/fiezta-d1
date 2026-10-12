(function() {
  if (!window.fieztaPage || window.__fieztaStillWatching) { return; }
  window.__fieztaStillWatching = true;

  var SIGNAL_EVERY_MILLIS = 5 * 60 * 1000;
  var signalledAt = 0;
  var followed = null;

  // YouTube counts as activity any keyup on the document: it refreshes the idle time it reports
  // to the server and cancels a pending "still watching?" prompt. The shortest prompt delay it
  // sends is 20 minutes, so a signal every 5 minutes of playback never lets one fire.
  function tellTheSiteSomeoneIsHere() {
    signalledAt = Date.now();
    document.dispatchEvent(new Event('keyup', { bubbles: true }));
  }

  function onProgress() {
    if (!followed.paused && Date.now() - signalledAt >= SIGNAL_EVERY_MILLIS) { tellTheSiteSomeoneIsHere(); }
  }

  window.fieztaPage.on('youtube:playerStateChanged', function(detail, elements) {
    if (!elements.video || elements.video === followed) { return; }
    if (followed) { followed.removeEventListener('timeupdate', onProgress); }
    followed = elements.video;
    followed.addEventListener('timeupdate', onProgress);
  });
})();
