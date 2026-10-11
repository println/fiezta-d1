(function() {
  if (!window.fieztaPage || window.__fieztaUnmutePlayer) { return; }
  window.__fieztaUnmutePlayer = true;

  var onWatch = false;
  window.fieztaPage.on('youtube:pageChanged', function(detail) { onWatch = detail.kind === 'watch'; });
  window.fieztaPage.on('youtube:playerStateChanged', function(detail, elements) {
    if (onWatch && detail.state === 'playing' && detail.muted) { unmute(elements.video, elements.player); }
  });

  function unmute(video, player) {
    if (!video) { return; }
    if (player && typeof player.unMute === 'function') { player.unMute(); }
    video.muted = false;
    if (video.volume === 0) { video.volume = 1; }
  }
})();
