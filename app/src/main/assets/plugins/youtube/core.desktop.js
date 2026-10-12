(function() {
  window.__fieztaYoutubeSpy.start({
    playerSelectors: ['#movie_player'],
    topBarSelector: '#masthead-container',
    nativeEvents: ['yt-navigate-start', 'yt-navigate-finish', 'yt-page-data-updated'],
    panel: {
      item: 'ytd-playlist-panel-video-renderer',
      title: '#video-title',
      byline: '#byline',
      link: 'a#wc-endpoint',
      header: 'ytd-playlist-panel-renderer #playlist-title, ytd-playlist-panel-renderer h3:not([id])',
      isCurrent: function(item) { return item.hasAttribute('selected'); }
    }
  });
})();
