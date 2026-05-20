package pt.cuco.scanner

import org.json.JSONObject

object FillFormJs {

    private const val TEMPLATE = """
(function(serial, ctime, usage) {
  function setVal(el, v) {
    if (!el || typeof v !== 'string' || v.length === 0) return false;
    try {
      var setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
      setter.call(el, v);
    } catch (e) {
      el.value = v;
    }
    el.dispatchEvent(new Event('input', { bubbles: true }));
    el.dispatchEvent(new Event('change', { bubbles: true }));
    return true;
  }

  function allInputs() {
    return document.querySelectorAll('input, textarea');
  }

  function findBySelectors(selectors) {
    for (var s = 0; s < selectors.length; s++) {
      var found = document.querySelector(selectors[s]);
      if (found) return found;
    }
    return null;
  }

  function findByKeywords(keywordGroups, exclude) {
    var inputs = allInputs();
    for (var i = 0; i < inputs.length; i++) {
      var inp = inputs[i];
      var type = (inp.type || '').toLowerCase();
      if (type === 'hidden' || type === 'submit' || type === 'button') continue;

      var haystack = [
        inp.name || '', inp.id || '', inp.placeholder || '',
        inp.getAttribute('aria-label') || '',
        (inp.labels && inp.labels[0] && inp.labels[0].innerText) || '',
        (inp.previousElementSibling && inp.previousElementSibling.innerText) || '',
        (inp.parentElement && inp.parentElement.innerText) || ''
      ].join(' ').toLowerCase();

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

  var serialEl = findBySelectors([
    'input[name=serial]', 'input[id*=serial i]', 'input[name*=serial i]'
  ]) || findByKeywords([['serial'], ['machine','serial']], null);

  var ctimeEl = findBySelectors([
    'input[id=c]', 'input[name=ctime]', 'input[id=ctime]', 'input[name*=certified i]',
    'input[id*=certified i]', 'input[name*=time i]', 'input[id*=time i]'
  ]) || findByKeywords([['certified'], ['certified', 'time'], ['ctime']], ['usage', 'counter']);

  var usageEl = findBySelectors([
    'input[id=u]', 'input[name=usage]', 'input[name=usagecounter]', 'input[id*=usage i]',
    'input[id*=counter i]', 'input[name*=counter i]'
  ]) || findByKeywords([['usage'], ['usage', 'counter'], ['counter']], ['time', 'certified']);

  var okSerial = setVal(serialEl, serial);
  var okCtime = setVal(ctimeEl, ctime);
  var okUsage = setVal(usageEl, usage);

  return JSON.stringify({serial: okSerial, ctime: okCtime, usage: okUsage});
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
