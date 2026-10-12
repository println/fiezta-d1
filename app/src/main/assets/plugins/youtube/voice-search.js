(function() {
  var RESULT_TIMEOUT_MILLIS = 10000;

  function progress(stage) {
    var context = window.__fieztaVoiceSearch;
    if (context && window.fieztaplugins) {
      window.fieztaplugins.onVoiceSearchProgress(String(context.id), String(context.generation), stage);
    }
  }

  function playFirstResult() {
    progress('confirmed');
    var finished = false;
    var stopListening = function() {};
    var timeout = setTimeout(function() { finish(null); }, RESULT_TIMEOUT_MILLIS);

    function finish(url) {
      if (finished) { return; }
      finished = true;
      clearTimeout(timeout);
      stopListening();
      fiezta.reportSearchResult(!!url);
      if (!url) {
        progress('failed');
        return;
      }
      progress('result');
      location.href = url;
    }

    if (!window.fieztaPage) {
      finish(null);
      return;
    }
    stopListening = window.fieztaPage.on('youtube:searchResultsChanged', function(detail) {
      if (detail.firstVideoUrl) { finish(detail.firstVideoUrl); }
    });
  }

  playFirstResult();
})();
