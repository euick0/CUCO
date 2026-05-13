package pt.cuco.scanner

import org.json.JSONObject

object FillFormJs {

    private const val TEMPLATE = """
(function(serial, ctime, usage) {
  function setVal(el, v) {
    if (!el) return false;
    try {
      var d = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
      d.call(el, v);
    } catch (e) {
      el.value = v;
    }
    el.dispatchEvent(new Event('input', {bubbles:true}));
    el.dispatchEvent(new Event('change', {bubbles:true}));
    return true;
  }
  function find(keywords, exclude) {
    var inputs = document.querySelectorAll('input[type=text], input:not([type]), textarea');
    for (var i = 0; i < inputs.length; i++) {
      var inp = inputs[i];
      var haystack = [
        inp.name||'', inp.id||'', inp.placeholder||'',
        inp.getAttribute('aria-label')||'',
        (inp.labels && inp.labels[0] && inp.labels[0].innerText)||'',
        (inp.previousElementSibling && inp.previousElementSibling.innerText)||'',
        (inp.parentElement && inp.parentElement.innerText)||''
      ].join(' ').toLowerCase();
      var ok = true;
      for (var k = 0; k < keywords.length; k++) {
        if (haystack.indexOf(keywords[k]) < 0) { ok = false; break; }
      }
      if (ok && exclude) {
        for (var e = 0; e < exclude.length; e++) {
          if (haystack.indexOf(exclude[e]) >= 0) { ok = false; break; }
        }
      }
      if (ok) return inp;
    }
    return null;
  }
  setVal(find(['serial']), serial);
  setVal(find(['certified']), ctime) || setVal(find(['time'], ['usage']), ctime);
  setVal(find(['usage']), usage);
  return 'filled';
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
