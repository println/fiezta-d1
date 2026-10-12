(function() {
  if (window.fieztaPage) { return; }
  var listeners = {};
  var last = {};

  function isNamespaced(name) {
    return /^[a-z0-9][a-z0-9_-]*:[A-Za-z0-9_.-]+$/.test(String(name));
  }

  function deliver(handler, record) {
    try {
      handler(record.detail, record.elements);
    } catch (e) {
      if (window.console && console.error) { console.error('fieztaPage handler failed', e); }
    }
  }

  window.fieztaPage = {
    emit: function(name, detail, elements, options) {
      if (!isNamespaced(name)) { throw new Error('fieztaPage event needs a plugin prefix: ' + name); }
      var sticky = !(options && options.moment);
      var record = { detail: detail, elements: elements || {}, json: JSON.stringify(detail === undefined ? null : detail) };
      if (sticky) {
        var previous = last[name];
        if (previous && previous.json === record.json) {
          previous.elements = record.elements;
          return;
        }
        last[name] = record;
      }
      (listeners[name] || []).slice().forEach(function(handler) { deliver(handler, record); });
    },
    on: function(name, handler) {
      (listeners[name] = listeners[name] || []).push(handler);
      if (last[name]) { deliver(handler, last[name]); }
      return function() {
        var list = listeners[name] || [];
        var index = list.indexOf(handler);
        if (index >= 0) { list.splice(index, 1); }
      };
    },
    feedback: function(name, text) {
      if (!isNamespaced(name)) { throw new Error('fieztaPage feedback needs a plugin prefix: ' + name); }
      if (window.fieztaplugins && window.fieztaplugins.feedback) { window.fieztaplugins.feedback(String(name), String(text)); }
    },
    last: function(name) {
      return last[name] ? last[name].detail : undefined;
    },
    lastElements: function(name) {
      return last[name] ? last[name].elements : undefined;
    }
  };
})();
