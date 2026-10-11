(function() {
  var shortId = null;
  fiezta.setQueueProvider(function() { return { shape: 'stream' }; });
  fiezta.setMetadataProvider(function() { return shortId ? { id: shortId } : null; });
  fiezta.listen('youtube:trackChanged', function(detail) { shortId = detail.videoId; });

  function navigationButton(name) {
    return window.fieztaPage.lastElements('youtube:navigationChanged')[name];
  }

  fiezta.on('nextClick', function() {
    var button = navigationButton('nextButton');
    if (button && !button.disabled) { button.click(); }
  });

  fiezta.on('previousClick', function() {
    var button = navigationButton('previousButton');
    if (button && !button.disabled) { button.click(); }
  });

  fiezta.listen('youtube:navigationChanged', function(detail) {
    var available = {};
    if (detail.next !== null) { available.next = detail.next; }
    if (detail.previous !== null) { available.previous = detail.previous; }
    fiezta.setAvailable(available);
  });
})();
