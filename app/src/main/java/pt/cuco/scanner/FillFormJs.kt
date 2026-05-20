package pt.cuco.scanner

import org.json.JSONObject

object FillFormJs {

    private const val TEMPLATE = """
(function(serial, ctime, usage) {
  var values = { serial: serial, ctime: ctime, usage: usage };

  if (window.__cucoFillerInstalled) {
    window.__cucoFillerValues = values;
    if (typeof window.__cucoFillerTick === 'function') {
      try { window.__cucoFillerTick(); } catch (e) {}
    }
    return JSON.stringify({ reused: true });
  }
  window.__cucoFillerInstalled = true;
  window.__cucoFillerValues = values;

  var filled = { serial: false, ctime: false, usage: false };

  function setVal(el, v) {
    if (!el || typeof v !== 'string' || v.length === 0) return false;
    try {
      var proto = (el.tagName === 'TEXTAREA') ? window.HTMLTextAreaElement.prototype
                                              : window.HTMLInputElement.prototype;
      var setter = Object.getOwnPropertyDescriptor(proto, 'value').set;
      setter.call(el, v);
    } catch (e) {
      el.value = v;
    }
    try { el.dispatchEvent(new Event('input', { bubbles: true })); } catch (e) {}
    try { el.dispatchEvent(new Event('change', { bubbles: true })); } catch (e) {}
    try { el.dispatchEvent(new Event('blur', { bubbles: true })); } catch (e) {}
    return true;
  }

  function collectRoots() {
    var roots = [document];
    function walk(root) {
      var all;
      try { all = root.querySelectorAll('*'); } catch (e) { return; }
      for (var i = 0; i < all.length; i++) {
        var node = all[i];
        if (node.shadowRoot) {
          roots.push(node.shadowRoot);
          walk(node.shadowRoot);
        }
        if (node.tagName === 'IFRAME' || node.tagName === 'FRAME') {
          try {
            var doc = node.contentDocument;
            if (doc) { roots.push(doc); walk(doc); }
          } catch (e) {}
        }
      }
    }
    walk(document);
    return roots;
  }

  function allInputs() {
    var roots = collectRoots();
    var out = [];
    for (var r = 0; r < roots.length; r++) {
      var found;
      try { found = roots[r].querySelectorAll('input, textarea'); } catch (e) { continue; }
      for (var i = 0; i < found.length; i++) out.push(found[i]);
    }
    return out;
  }

  function queryFirstFillable(selector) {
    var roots = collectRoots();
    for (var r = 0; r < roots.length; r++) {
      var list;
      try { list = roots[r].querySelectorAll(selector); } catch (e) { continue; }
      for (var i = 0; i < list.length; i++) {
        if (fillable(list[i])) return list[i];
      }
    }
    return null;
  }

  function findBySelectors(selectors) {
    for (var s = 0; s < selectors.length; s++) {
      var found = queryFirstFillable(selectors[s]);
      if (found) return found;
    }
    return null;
  }

  function fillable(inp) {
    var type = (inp.type || '').toLowerCase();
    if (type === 'hidden' || type === 'submit' || type === 'button' ||
        type === 'checkbox' || type === 'radio' || type === 'file' || type === 'image') return false;
    if (inp.disabled || inp.readOnly) return false;
    return true;
  }

  function textOf(node) {
    if (!node) return '';
    return node.innerText || node.textContent || '';
  }

  function haystackFor(inp) {
    var parts = [
      inp.name || '', inp.id || '', inp.placeholder || '',
      inp.getAttribute('aria-label') || '',
      inp.getAttribute('title') || '',
      inp.className || ''
    ];
    try {
      if (inp.labels && inp.labels[0]) parts.push(textOf(inp.labels[0]));
    } catch (e) {}
    try { parts.push(textOf(inp.previousElementSibling)); } catch (e) {}
    try { parts.push(textOf(inp.parentElement)); } catch (e) {}
    var joined = parts.join(' ').toLowerCase();
    try { joined = joined.normalize('NFD').replace(/[̀-ͯ]/g, ''); } catch (e) {}
    return joined;
  }

  function findByKeywords(keywordGroups, exclude) {
    var inputs = allInputs();
    for (var i = 0; i < inputs.length; i++) {
      var inp = inputs[i];
      if (!fillable(inp)) continue;
      var haystack = haystackFor(inp);

      var matchGroup = false;
      for (var g = 0; g < keywordGroups.length; g++) {
        var group = keywordGroups[g];
        var ok = true;
        for (var k = 0; k < group.length; k++) {
          if (haystack.indexOf(group[k]) < 0) { ok = false; break; }
        }
        if (ok) { matchGroup = true; break; }
      }
      if (!matchGroup) continue;

      if (exclude) {
        var excluded = false;
        for (var e = 0; e < exclude.length; e++) {
          if (haystack.indexOf(exclude[e]) >= 0) { excluded = true; break; }
        }
        if (excluded) continue;
      }
      return inp;
    }
    return null;
  }

  function tryFill() {
    var v = window.__cucoFillerValues || values;

    // Serial field: the CUCO page uses short ids "c" and "u" for ctime / usage,
    // but the serial input is NOT id="s" — that selector matched an unrelated
    // first input on the page and broke serial autofill. Match only by full
    // names / keywords.
    var serialEl = findBySelectors([
      'input[id="serial"]', 'input[name="serial"]',
      'input[id="serialnumber"]', 'input[name="serialnumber"]',
      'input[id="machineserial"]', 'input[name="machineserial"]',
      'input[id*="serial" i]', 'input[name*="serial" i]'
    ]) || findByKeywords([
      ['machine', 'serial'], ['serial', 'number'], ['numero', 'serie'],
      ['numero', 'de', 'serie'], ['n', 'serie'], ['serial'], ['serie']
    ], ['certified', 'tempo', 'usage', 'counter', 'contador', 'utiliza', 'unblock', 'desbloque', 'code', 'codigo']);

    var ctimeEl = findBySelectors([
      'input[id="c"]', 'input[name="c"]',
      'input[id="ctime"]', 'input[name="ctime"]',
      'input[id*="certified" i]', 'input[name*="certified" i]',
      'input[id*="ctime" i]', 'input[name*="ctime" i]'
    ]) || findByKeywords([
      ['certified', 'time'], ['certified'], ['ctime'], ['tempo', 'certificado'], ['hora', 'certificada']
    ], ['usage', 'counter', 'uso', 'utiliza', 'contador']);

    var usageEl = findBySelectors([
      'input[id="u"]', 'input[name="u"]',
      'input[id="usage"]', 'input[name="usage"]',
      'input[name="usagecounter"]', 'input[id*="usage" i]',
      'input[id*="counter" i]', 'input[name*="counter" i]',
      'input[id*="utiliza" i]', 'input[name*="utiliza" i]',
      'input[id*="contador" i]', 'input[name*="contador" i]'
    ]) || findByKeywords([
      ['usage', 'counter'], ['usage'], ['counter'], ['contador'], ['utiliza'], ['uso']
    ], ['time', 'certified', 'tempo', 'certificada', 'hora']);

    if (!filled.serial && serialEl && fillable(serialEl)) {
      if (setVal(serialEl, v.serial)) filled.serial = true;
    }
    if (!filled.ctime && ctimeEl && fillable(ctimeEl)) {
      if (setVal(ctimeEl, v.ctime)) filled.ctime = true;
    }
    if (!filled.usage && usageEl && fillable(usageEl)) {
      if (setVal(usageEl, v.usage)) filled.usage = true;
    }

    return filled.serial && filled.ctime && filled.usage;
  }

  window.__cucoFillerTick = function() {
    filled = { serial: false, ctime: false, usage: false };
    tryFill();
  };

  tryFill();

  var observer;
  try {
    observer = new MutationObserver(function() { tryFill(); });
    observer.observe(document.documentElement || document.body || document, {
      childList: true, subtree: true, attributes: true,
      attributeFilter: ['id', 'name', 'class', 'value']
    });
  } catch (e) {}

  var ticks = 0;
  var interval = setInterval(function() {
    ticks++;
    var done = tryFill();
    if (done || ticks > 60) {
      clearInterval(interval);
      if (done && observer) {
        try { observer.disconnect(); } catch (e) {}
      }
    }
  }, 500);

  return JSON.stringify({ installed: true, filled: filled });
})(%s, %s, %s);
"""

    fun build(serial: String, certifiedTime: String, usageCounter: String): String {
        return TEMPLATE.format(
            JSONObject.quote(serial),
            JSONObject.quote(certifiedTime),
            JSONObject.quote(usageCounter),
        )
    }
}
