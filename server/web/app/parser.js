// Werd web — turns pasted text into lines with repetition counts.
// A faithful port of the Android app's WerdParser: (3) [٣] ×3 3x "3 مرات" "ثلاثاً" "مرتين" "مائة مرة" "يكرر 7" "3 times" …
// A line holding only a count applies it to the line before.
'use strict';
const WerdParser = (() => {
  const isLetter = (c) => /\p{L}/u.test(c);
  const isDigit = (c) => /\p{Nd}/u.test(c);
  const isIgnorable = (c) => (c >= 'ً' && c <= 'ٟ') || c === 'ٰ' || c === 'ـ' ||
    c === '‌' || c === '‍' || c === '‎' || c === '‏' || c === '؜';
  function normChar(c) {
    if (c >= '٠' && c <= '٩') return String.fromCharCode(48 + c.charCodeAt(0) - 0x0660);
    if (c >= '۰' && c <= '۹') return String.fromCharCode(48 + c.charCodeAt(0) - 0x06F0);
    switch (c) {
      case 'أ': case 'إ': case 'آ': case 'ٱ': return 'ا';
      case 'ة': return 'ه';
      case 'ى': return 'ي';
      case 'ؤ': return 'و';
      case '（': return '(';
      case '）': return ')';
    }
    return c.toLowerCase().length === 1 ? c.toLowerCase() : c;
  }
  const nw = (s) => { let o = ''; for (const c of s) if (!isIgnorable(c)) o += normChar(c); return o; };
  const esc = (s) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

  // normalised copy with an index map back to the original (UTF-16 indices)
  function norm(original) {
    let text = ''; const map = [];
    for (let i = 0; i < original.length; i++) {
      const c = original[i];
      if (isIgnorable(c)) continue;
      const n = normChar(c);
      for (let k = 0; k < n.length; k++) { text += n[k]; map.push(i); }
    }
    return {
      text,
      range(start, endExcl) {
        if (!map.length) return [0, 0];
        const clamp = (v) => Math.max(0, Math.min(map.length - 1, v));
        const s = map[clamp(start)];
        let e = map[clamp(endExcl - 1)] + 1;
        while (e < original.length && isIgnorable(original[e])) e++;
        return [s, e];
      },
    };
  }

  // ------------------------------------------------------------ Arabic number words
  const units = { 'واحد': 1, 'واحده': 1, 'احدي': 1, 'احد': 1, 'اثنين': 2, 'اثنان': 2, 'اثنتين': 2, 'اثنتان': 2, 'اثني': 2, 'اثنا': 2,
    'ثلاث': 3, 'ثلاثه': 3, 'ثلاثا': 3, 'اربع': 4, 'اربعه': 4, 'اربعا': 4, 'خمس': 5, 'خمسه': 5, 'خمسا': 5, 'ست': 6, 'سته': 6, 'ستا': 6,
    'سبع': 7, 'سبعه': 7, 'سبعا': 7, 'ثمان': 8, 'ثماني': 8, 'ثمانيه': 8, 'ثمانيا': 8, 'تسع': 9, 'تسعه': 9, 'تسعا': 9 };
  const tenWords = new Set(['عشر', 'عشره', 'عشرا']);
  const tens = { 'عشرين': 20, 'عشرون': 20, 'ثلاثين': 30, 'ثلاثون': 30, 'اربعين': 40, 'اربعون': 40, 'خمسين': 50, 'خمسون': 50,
    'ستين': 60, 'ستون': 60, 'سبعين': 70, 'سبعون': 70, 'ثمانين': 80, 'ثمانون': 80, 'تسعين': 90, 'تسعون': 90 };
  const hundredWords = new Set(['مائه', 'مئه', 'مايه', 'مائة', 'مئة']);
  const twoHundredWords = new Set(['مائتين', 'مئتين', 'مائتان', 'مئتان', 'مايتين']);
  const thousandWords = new Set(['الف', 'الاف']);
  const twoThousandWords = new Set(['الفين', 'الفان']);
  const has = (o, k) => Object.prototype.hasOwnProperty.call(o, k);
  const unitOfHundred = (t) => {
    for (const suf of ['مائه', 'مئه']) if (t.endsWith(suf) && has(units, t.slice(0, -suf.length))) return units[t.slice(0, -suf.length)];
    return null;
  };
  const isNumberWord = (t) => has(units, t) || tenWords.has(t) || has(tens, t) || hundredWords.has(t) || twoHundredWords.has(t) ||
    thousandWords.has(t) || twoThousandWords.has(t) || unitOfHundred(t) != null;

  const allNumberWords = (() => {
    const base = [...Object.keys(units), ...tenWords, ...Object.keys(tens), ...hundredWords, ...twoHundredWords, ...thousandWords, ...twoThousandWords];
    const compounds = Object.keys(units).flatMap((u) => [u + 'مائه', u + 'مئه']);
    return [...new Set([...base, ...compounds].map(nw))].sort((a, b) => b.length - a.length);
  })();
  const numWords = (() => {
    const alt = allNumberWords.map(esc).join('|');
    const follow = 'عشره|عشرا|عشر|مائه|مئه|الاف|الف';
    return `(?:${alt})(?:\\s+(?:و(?:${alt})|${follow}))*`;
  })();

  function arabicNumber(phrase) {
    const tokens = nw(phrase).trim().split(/\s+/).filter(Boolean);
    if (!tokens.length) return null;
    let total = 0, pending = 0;
    const strip = (t) => (t && t.length > 2 && t.startsWith('و') && isNumberWord(t.slice(1))) ? t.slice(1) : t;
    for (let i = 0; i < tokens.length; i++) {
      const t = strip(tokens[i]);
      if (has(units, t)) {
        const u = units[t];
        const next = tokens[i + 1] != null ? strip(tokens[i + 1]) : null;
        if (next != null && tenWords.has(next)) { pending += u + 10; i++; } else pending += u;
      } else if (tenWords.has(t)) pending += 10;
      else if (has(tens, t)) pending += tens[t];
      else if (hundredWords.has(t)) pending = (pending >= 1 && pending <= 9) ? pending * 100 : pending + 100;
      else if (twoHundredWords.has(t)) pending += 200;
      else if (unitOfHundred(t) != null) pending += unitOfHundred(t) * 100;
      else if (thousandWords.has(t)) { total += (pending === 0 ? 1 : pending) * 1000; pending = 0; }
      else if (twoThousandWords.has(t)) total += 2000;
      else return null;
    }
    const v = total + pending;
    return v > 0 ? v : null;
  }

  // ------------------------------------------------------------ patterns
  const ACC = '(?:ثلاثا|اربعا|خمسا|ستا|سبعا|ثمانيا|تسعا|عشرا)(?:\\s+و(?:عشرين|ثلاثين|اربعين|خمسين|ستين|سبعين|ثمانين|تسعين))?';
  const TIMES = '(?:مرات|مره|مرار|مرارا|times|time|marrat|marra)';
  const OPEN = '[\\(\\[\\{«﴾﴿]';
  const CLOSE = '[\\)\\]\\}»﴾﴿]';
  const XS = '[x×✕*]';
  const NOTL_B = '(?<![\\p{L}])';
  const NOTL_A = '(?![\\p{L}])';
  const englishWords = { 'one': 1, 'two': 2, 'three': 3, 'four': 4, 'five': 5, 'six': 6, 'seven': 7, 'eight': 8, 'nine': 9, 'ten': 10,
    'eleven': 11, 'twelve': 12, 'nineteen': 19, 'thirty-three': 33, 'thirty three': 33, 'thirty-four': 34, 'thirty four': 34,
    'forty': 40, 'forty-one': 41, 'forty one': 41, 'seventy': 70, 'hundred': 100, 'a hundred': 100, 'one hundred': 100,
    'a thousand': 1000, 'thousand': 1000 };
  function countOf(s) {
    const t = s.trim();
    const d = t.match(/\d{1,5}/);
    if (d) return parseInt(d[0], 10);
    if (t.startsWith('مرت')) return 2;
    return arabicNumber(t.replace(/\s*(?:مرات|مره|مرار|مرارا|times|time)$/u, ''));
  }
  const engAlt = Object.keys(englishWords).sort((a, b) => b.length - a.length).map(esc).join('|');
  const P = (src, value, keep = 0, flags = 'iu') => ({ re: new RegExp(src, flags), value, keep });
  const patterns = [
    P(`(${OPEN})\\s*(?:${XS}\\s*)?(\\d{1,5})\\s*(${XS}|${TIMES})?\\s*${CLOSE}`, (m) => {
      const v = parseInt(m[2], 10);
      const ornate = m[1] === '﴾' || m[1] === '﴿';
      const qualified = !!m[3] || m[0].includes('×') || m[0].toLowerCase().includes('x');
      return ornate && !qualified ? null : v;
    }),
    P(`${OPEN}\\s*(مره\\s+واحده|مره|مرتين|مرتان|once|twice|thrice)\\s*${CLOSE}`, (m) => {
      const w = m[1].toLowerCase();
      return (w === 'مرتين' || w === 'مرتان' || w === 'twice') ? 2 : w === 'thrice' ? 3 : 1;
    }),
    P(`${OPEN}\\s*(${numWords})\\s*(?:${TIMES})?\\s*${CLOSE}`, (m) => arabicNumber(m[1])),
    P(`${OPEN}\\s*([^()\\[\\]{}«»﴾﴿]*?[^\\s()\\[\\]{}«»﴾﴿])\\s*[-–—:،,]?\\s+` +
      `((?:${XS}\\s*)?\\d{1,5}\\s*(?:${TIMES}|${XS})?|(?:${numWords})\\s*${TIMES}|مرتين|مرتان|${ACC})\\s*${CLOSE}`,
      (m) => isEmptyText(m[1]) ? null : countOf(m[2]), 1),
    P(`${NOTL_B}(?:يكرر|تكرر|كرر|تكرار|يقال|repeat|recite)\\s*:?\\s*(\\d{1,5})${NOTL_A}(?:\\s*${TIMES})?`, (m) => parseInt(m[1], 10)),
    P(`(?:${XS}\\s*)?(\\d{1,5})\\s*${TIMES}${NOTL_A}`, (m) => parseInt(m[1], 10)),
    P(`${NOTL_B}(${numWords})\\s*${TIMES}${NOTL_A}`, (m) => arabicNumber(m[1])),
    P(`${NOTL_B}(مرتين|مرتان)${NOTL_A}`, () => 2, 0, 'u'),
    P(`${NOTL_B}(مره\\s+واحده)${NOTL_A}`, () => 1, 0, 'u'),
    P(`\\s[-–—:]\\s*(\\d{1,5})\\s*[.،,؛]?\\s*$`, (m) => parseInt(m[1], 10), 0, 'u'),
    P(`(?:${XS}\\s*(\\d{1,5})|(\\d{1,5})\\s*${XS})\\s*[.:،,؛]?\\s*$`, (m) => parseInt(m[1] || m[2], 10)),
    P(`^\\s*(?:${XS}\\s*(\\d{1,5})|(\\d{1,5})\\s*${XS})(?![\\p{L}\\d])`, (m) => parseInt(m[1] || m[2], 10)),
    P(`${NOTL_B}(${ACC})\\s*[.:،,؛]?\\s*$`, (m) => arabicNumber(m[1]), 0, 'u'),
    P(`${NOTL_B}(${engAlt})\\s+times${NOTL_A}`, (m) => englishWords[m[1].toLowerCase()] ?? null),
    P(`${NOTL_B}(thrice|twice)${NOTL_A}`, (m) => m[1].toLowerCase() === 'twice' ? 2 : 3),
  ];

  // ------------------------------------------------------------ helpers
  const isEmptyText = (s) => ![...s].some(isLetter);
  const emptyBrackets = /[\(\[\{«]\s*[\)\]\}»]/gu;
  const edgeJunk = /^[\s\-–—:،,؛|*•·]+|[\s\-–—:،,؛|*•·]+$/gu;
  const cleanup = (s) => s.replace(emptyBrackets, ' ').replace(/[ \t]{2,}/g, ' ').replace(edgeJunk, '').trim();
  const leadingNumbering = /^\s*[\d٠-٩۰-۹]{1,3}\s*[-.)–:]\s+/u;
  function stripNumbering(line) {
    const m = line.match(leadingNumbering);
    if (!m) return line;
    const rest = line.slice(m[0].length);
    return rest.trim() ? rest : line;
  }

  function detect(line) {
    const n = norm(line);
    for (const p of patterns) {
      p.re.lastIndex = 0;
      const m = p.re.exec(n.text);
      if (!m) continue;
      const count = p.value(m);
      if (count == null || !(count > 0) || count > 100000) continue;
      const [s, e] = n.range(m.index, m.index + m[0].length);
      let kept = '';
      if (p.keep > 0 && m[p.keep] != null) {
        // locate the kept group inside the match (JS has no group indices without the d flag)
        const gi = m[0].indexOf(m[p.keep]);
        if (gi >= 0) { const [ks, ke] = n.range(m.index + gi, m.index + gi + m[p.keep].length); kept = line.slice(ks, ke); }
      }
      return { rest: cleanup(line.slice(0, s) + ' ' + kept + ' ' + line.slice(e)), count, start: s, end: e };
    }
    return { rest: cleanup(line), count: null, start: -1, end: -1 };
  }

  function repeatedToken(text) {
    const t = text.trim().replace(/^[()\[\]«»﴿﴾،, ]+|[()\[\]«»﴿﴾،, ]+$/gu, '');
    const tokens = t.split(/\s+/).filter(Boolean);
    if (tokens.length < 3) return null;
    const first = nw(tokens[0]);
    if (first.length < 2 || tokens.some((x) => nw(x) !== first)) return null;
    return [tokens[0], tokens.length];
  }

  function splitAfterMarker(clause) {
    const d = detect(clause);
    if (d.count == null || d.end < 0) return [clause];
    const after = clause.slice(d.end);
    if ([...after].filter(isLetter).length < 12) return [clause];
    const head = clause.slice(0, d.end).trim();
    if (!/\p{L}/u.test(head)) return [clause];
    return [head, ...splitAfterMarker(after.trim().replace(/^[،,) ]+/u, ''))];
  }

  function splitClauses(line) {
    const out = []; let sb = ''; let depth = 0; let ornate = false; let bracketStart = -1;
    const emit = () => {
      const t = sb.trim(); sb = '';
      if (!t) return;
      if (!/\p{L}/u.test(t) && out.length && !/\d/u.test(t)) out[out.length - 1] += t; else out.push(t);
    };
    for (const c of line) {
      sb += c;
      if ('([{«'.includes(c)) { if (depth === 0) bracketStart = sb.length - c.length; depth++; }
      else if (')]}»'.includes(c)) {
        depth = Math.max(0, depth - 1);
        if (depth === 0 && bracketStart >= 0 && !ornate) {
          const inner = sb.slice(bracketStart); bracketStart = -1;
          if (detect(inner).count != null) { emit(); continue; }
        }
      } else if (c === '﴿' || c === '﴾') ornate = !ornate;
      if (depth > 0 && bracketStart >= 0 && sb.length - bracketStart > 60) { depth = 0; bracketStart = -1; }
      if (depth === 0 && !ornate && '،,؛;.؟?!۔'.includes(c)) emit();
    }
    emit();
    return out.flatMap(splitAfterMarker);
  }

  const MAX_CHUNK = 380;
  function parseClauses(clauses, items) {
    const acc = [];
    const flush = () => {
      if (!acc.length) return;
      let chunk = '';
      for (const c of acc) {
        if (chunk && chunk.length + c.length > MAX_CHUNK) { items.push({ text: cleanup(chunk), count: 1, detected: false }); chunk = ''; }
        chunk += (chunk ? ' ' : '') + c;
      }
      if (chunk.trim()) items.push({ text: cleanup(chunk), count: 1, detected: false });
      acc.length = 0;
    };
    for (const cl of clauses) {
      const d = detect(cl);
      const rep = d.count == null ? repeatedToken(d.rest) : null;
      if (rep) { flush(); items.push({ text: rep[0], count: rep[1], detected: true }); }
      else if (d.count == null) acc.push(cl);
      else if (isEmptyText(d.rest)) {
        if (acc.length) { const last = acc.pop(); flush(); items.push({ text: cleanup(last), count: d.count, detected: true }); }
        else if (items.length) Object.assign(items[items.length - 1], { count: d.count, detected: true });
      } else { flush(); items.push({ text: d.rest, count: d.count, detected: true }); }
    }
    flush();
  }

  const titleWords = ['ورد', 'راتب', 'اذكار', 'أذكار', 'حزب', 'دعاء', 'وظيفه', 'وظيفة', 'اوراد', 'أوراد', 'صلوات',
    'wird', 'werd', 'adhkar', 'azkar', 'ratib', 'hizb', 'dua', "du'a", 'litany', 'awrad'];
  function looksLikeTitle(s, count) {
    if (s.length > 60) return false;
    const l = nw(s);
    const keyword = titleWords.some((w) => l.includes(nw(w)));
    const terminal = '.!؟?،:'.includes(s.trimEnd().slice(-1));
    return (keyword && !terminal) || (s.length <= 40 && !terminal && count >= 4 && s.split(' ').length <= 6 && keyword);
  }

  function parse(raw) {
    const lines = raw.replace(/\r\n?/g, '\n').split('\n').map((s) => s.trim()).filter(Boolean);
    const items = [];
    for (const line0 of lines) {
      const line = stripNumbering(line0);
      const clauses = splitClauses(line);
      if (clauses.length > 1 && clauses.slice(0, -1).some((c) => { const d = detect(c); return d.count != null || repeatedToken(d.rest) != null; })) {
        parseClauses(clauses, items);
        continue;
      }
      const d = detect(line);
      if (d.count == null) {
        const rep = repeatedToken(d.rest);
        if (rep) { items.push({ text: rep[0], count: rep[1], detected: true }); continue; }
      }
      if (isEmptyText(d.rest)) {
        if (d.count != null && items.length) Object.assign(items[items.length - 1], { count: d.count, detected: true });
        continue;
      }
      items.push({ text: d.rest, count: d.count ?? 1, detected: d.count != null });
    }
    let title = null;
    if (items.length >= 3 && !items[0].detected && looksLikeTitle(items[0].text, items.length)) title = items.shift().text;
    return { title, items };
  }

  return { parse, detect, arabicNumber, bare: nw };
})();
if (typeof module !== 'undefined') module.exports = WerdParser;
