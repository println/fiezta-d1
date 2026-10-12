(function() {
  window.__fieztaYoutubeSpy.start({
    playerSelectors: ['#player-container-id', '#movie_player'],
    topBarSelector: 'ytm-mobile-topbar-renderer',
    nativeEvents: [
      'state-navigatestart', 'state-change', 'state-navigateend', 'engagement-panel-update',
      'video-data-change', 'player-state-change', 'player-ad-state-change'
    ],
    panel: {
      item: 'ytm-playlist-panel-video-renderer',
      title: '.YtmCompactMediaItemHeadline',
      byline: '.YtmCompactMediaItemByline',
      link: 'a.YtmCompactMediaItemMetadataContent',
      header: '.playlist-engagement-panel-header',
      isCurrent: function(item) { return item.getAttribute('aria-selected') === 'true'; }
    }
  });
})();
