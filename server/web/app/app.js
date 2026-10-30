// Werd on the web — the daily-awrad tally, read-aloud, stats, library and family, in the browser.
// Data lives in this browser (localStorage); the library, family and the family voice come from the Werd server that serves this page.
'use strict';

// =============================================================== strings
const STR = {
  ar: {
    awrad: 'الأوراد', library: 'المكتبة', stats: 'الإحصائيات', family: 'العائلة', settings: 'الإعدادات',
    greetMorning: 'صباح الخير', greetEvening: 'مساء الخير', todayAwrad: 'أوراد اليوم', doneOf: 'أتممت {0} من {1}', streakDays: 'أيام متتالية',
    myAwrad: 'أورادي', newWerd: 'ورد جديد', lines: '{0} سطر', noAwrad: 'لا أوراد بعد', noAwradSub: 'أضف ورداً بلصق نصّه، أو من المكتبة.',
    notToday: 'ليس اليوم', back: 'رجوع', edit: 'تعديل', delete: 'حذف', duplicate: 'نسخ', copy: ' (نسخة)', resetToday: 'إعادة عدّ اليوم',
    markDone: 'تعليم كمكتمل', focus: 'وضع التركيز', list: 'عرض القائمة', bigger: 'تكبير الخط', smaller: 'تصغير الخط', required: 'المطلوب: {0}',
    lineOf: 'السطر {0} من {1}', more: 'المزيد', listen: 'استماع', reading: 'يقرأ السطر {0} من {1}', autoCount: 'عدّ تلقائي',
    preparing: 'يجهّز الصوت…', complete: 'اكتمل الورد', taqabbal: 'تقبّل الله منا ومنكم', streakNow: '{0} أيام متتالية', done: 'تم', stay: 'البقاء',
    title: 'العنوان', group: 'المجموعة', noGroup: 'بلا مجموعة', newGroup: 'مجموعة جديدة…', groupName: 'اسم المجموعة', days: 'أيام الورد', color: 'اللون',
    pasteHint: 'الصق نص الورد هنا كما هو، مع الأعداد مثل (٣) أو ثلاثاً أو ×10 أو ١٠٠ مرة…', convert: 'تقسيم إلى أسطر', linesN: 'الأسطر ({0})',
    addLine: 'إضافة سطر', save: 'حفظ', cancel: 'إلغاء', discardQ: 'تجاهل التغييرات؟', detected: 'اكتُشفت الأعداد تلقائياً', deleteQ: 'حذف «{0}»؟',
    needTitle: 'اكتب عنواناً', needLines: 'أضف سطراً واحداً على الأقل', saved: 'حُفظ',
    scan: 'مسح صفحة بالذكاء الاصطناعي', scanning: 'يقرأ الصفحة… ({0} ث)', scanFirst: 'أول صفحة تحمّل النموذج وقد تستغرق دقائق', scanned: 'قُرئت الصفحة — راجع النص ثم اضغط تقسيم إلى أسطر', scanOff: 'لا يوجد نموذج قراءة على الخادم بعد. نزّله من صفحة الإدارة ← قراءة الصفحات بالذكاء الاصطناعي.', scanModel: 'نموذج القراءة',
    weekdays: ['أحد', 'إثن', 'ثلا', 'أرب', 'خمي', 'جمع', 'سبت'],
    totalRec: 'مجموع الأذكار', currentStreak: 'السلسلة الحالية', bestStreak: 'أطول سلسلة', completeDays: 'أيام مكتملة', last7: 'آخر ٧ أيام',
    consistency: 'الاستمرارية (٣٠ يوماً)', perWerd: 'كل ورد', rate30: 'نسبة ٣٠ يوماً',
    server: 'خادم المكتبة والعائلة', serverAddr: 'عنوان الخادم', serverHint: 'فارغ: نفس الخادم الذي فتحت منه هذه الصفحة.', yourName: 'اسمك (يظهر للآخرين)',
    test: 'حفظ واختبار', connected: 'متصل · v{0}', notConnected: 'تعذّر الاتصال: {0}', noServer: 'هذه الصفحة غير مفتوحة من خادم «ورد». اكتب عنوانه في الإعدادات.',
    search: 'ابحث عن كتاب أو مؤلف', all: 'الكل', download: 'أضف إلى أورادي', supplications: '{0} دعاء', downloads: '{0} تحميل', familyOnly: 'للعائلة فقط',
    addAs: 'كيف تضيفه؟', oneWerd: 'ورد واحد بكل الأدعية', perOccasion: 'ورد لكل مناسبة', added: 'أُضيف {0}', libEmpty: 'لا كتب بعد في المكتبة', preview: 'معاينة',
    famIntro: 'تعاونوا على الخير: اعرف من أتمّ أوراده اليوم، وأرسل ورداً إلى قائمة أحد أفراد عائلتك.', createFamily: 'إنشاء عائلة', joinFamily: 'الانضمام برمز',
    familyName: 'اسم العائلة', inviteCode: 'رمز الدعوة', shareProgress: 'أظهر تقدّمي لعائلتي', shareProgressD: 'عناوين أورادك وما أتممته اليوم وسلسلتك فقط.',
    parent: 'وليّ أمر', child: 'ابن/ابنة', me: '{0} (أنت)', todayOf: '{0} من {1} اليوم', notSharing: 'لا يشارك تقدّمه', nothingToday: 'لا شيء اليوم بعد',
    send: 'أرسل ورداً', sendTo: 'أرسل ورداً إلى {0}', note: 'ملاحظة (اختياري)', addDirect: 'أضفه إلى قائمته مباشرة', sent: 'أُرسل',
    history: 'سجلّ التقدّم', makeParent: 'اجعله وليّ أمر', makeChild: 'اجعله ابناً', remove: 'إزالة من العائلة', removeQ: 'إزالة {0} من العائلة؟',
    leave: 'مغادرة العائلة', leaveQ: 'مغادرة العائلة؟', copied: 'نُسخ', shareInvite: 'انضم إلى عائلة «{0}» في تطبيق ورد. رمز الدعوة: {1}',
    fromFamily: 'من {0}', accept: 'أضف إلى أورادي', dismiss: 'تجاهل', missed: 'أيام فائتة', noReport: 'لم يُسجَّل شيء', today: 'اليوم', historyEmpty: 'لا سجلّ بعد.',
    language: 'اللغة', theme: 'المظهر', light: 'فاتح', dark: 'داكن', system: 'تلقائي', textSize: 'حجم الخط', tapCount: 'اضغط على السطر لتعدّ',
    autoAdvance: 'انتقل للسطر التالي عند اكتماله', vibrate: 'اهتزاز مع كل عدّة', dayStart: 'يبدأ اليوم الجديد الساعة {0}:00',
    voice: 'الصوت القارئ', voiceBrowser: 'صوت المتصفح', voiceServer: 'صوت خادم العائلة', voicePick: 'الصوت', speed: 'السرعة {0}', voiceDefault: 'الافتراضي',
    noArabicVoice: 'لا صوت عربي في هذا المتصفح؛ جرّب صوت خادم العائلة.', testVoice: 'تجربة الصوت',
    backup: 'نسخة احتياطية', exportB: 'تصدير', importB: 'استيراد', imported: 'استُوردت النسخة', reset: 'مسح كل البيانات', resetQ: 'مسح كل الأوراد والسجل من هذا المتصفح؟',
    serverHome: 'الصفحة الرئيسية للخادم', serverAdmin: 'لوحة الخادم',
    about: 'ورد على الويب · بياناتك في هذا المتصفح، والمكتبة والعائلة من خادم «ورد».', error: 'خطأ: {0}', offline: 'تعذّر الوصول إلى الخادم',
    install: 'تثبيت كتطبيق',
  },
  en: {
    awrad: 'Awrad', library: 'Library', stats: 'Statistics', family: 'Family', settings: 'Settings',
    greetMorning: 'Good morning', greetEvening: 'Good evening', todayAwrad: "Today's awrad", doneOf: '{0} of {1} done', streakDays: 'day streak',
    myAwrad: 'My awrad', newWerd: 'New werd', lines: '{0} lines', noAwrad: 'No awrad yet', noAwradSub: 'Add a werd by pasting its text, or from the library.',
    notToday: 'Not today', back: 'Back', edit: 'Edit', delete: 'Delete', duplicate: 'Duplicate', copy: ' (copy)', resetToday: "Reset today's count",
    markDone: 'Mark complete', focus: 'Focus mode', list: 'List view', bigger: 'Bigger text', smaller: 'Smaller text', required: 'Required: {0}',
    lineOf: 'Line {0} of {1}', more: 'More', listen: 'Listen', reading: 'Reading line {0} of {1}', autoCount: 'Auto-count',
    preparing: 'Preparing the voice…', complete: 'Werd complete', taqabbal: 'تقبّل الله منا ومنكم', streakNow: '{0}-day streak', done: 'Done', stay: 'Stay',
    title: 'Title', group: 'Group', noGroup: 'No group', newGroup: 'New group…', groupName: 'Group name', days: 'Werd days', color: 'Colour',
    pasteHint: 'Paste the werd as it is, with counts like (3), ثلاثاً, ×10 or 100 times…', convert: 'Split into lines', linesN: 'Lines ({0})',
    addLine: 'Add a line', save: 'Save', cancel: 'Cancel', discardQ: 'Discard your changes?', detected: 'Counts found automatically', deleteQ: 'Delete “{0}”?',
    needTitle: 'Give it a title', needLines: 'Add at least one line', saved: 'Saved',
    scan: 'Scan a page with AI', scanning: 'Reading the page… ({0} s)', scanFirst: 'The first page loads the model and can take a few minutes', scanned: 'Page read — check the text, then tap Split into lines', scanOff: 'No reading model on the server yet. Download one on the admin page → AI page reading.', scanModel: 'Reading model',
    weekdays: ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'],
    totalRec: 'Total recitations', currentStreak: 'Current streak', bestStreak: 'Best streak', completeDays: 'Complete days', last7: 'Last 7 days',
    consistency: 'Consistency (30 days)', perWerd: 'Each werd', rate30: '30-day rate',
    server: 'Library & family server', serverAddr: 'Server address', serverHint: 'Empty: the server this page was opened from.', yourName: 'Your name (shown to others)',
    test: 'Save and test', connected: 'Connected · v{0}', notConnected: 'Could not connect: {0}', noServer: "This page wasn't opened from a Werd server. Set its address in Settings.",
    search: 'Search books or authors', all: 'All', download: 'Add to my awrad', supplications: '{0} supplications', downloads: '{0} downloads', familyOnly: 'Family only',
    addAs: 'How should it be added?', oneWerd: 'One werd with every supplication', perOccasion: 'One werd per occasion', added: 'Added {0}', libEmpty: 'No books in the library yet', preview: 'Preview',
    famIntro: "Encourage each other: see who finished their awrad today, and send a werd to a family member's list.", createFamily: 'Create a family', joinFamily: 'Join with a code',
    familyName: 'Family name', inviteCode: 'Invite code', shareProgress: 'Show my progress to my family', shareProgressD: "Only your awrad titles, today's progress and your streak.",
    parent: 'Parent', child: 'Child', me: '{0} (you)', todayOf: '{0} of {1} today', notSharing: 'Not sharing progress', nothingToday: 'Nothing yet today',
    send: 'Send a werd', sendTo: 'Send a werd to {0}', note: 'A note (optional)', addDirect: 'Add it to their list directly', sent: 'Sent',
    history: 'Progress history', makeParent: 'Make a parent', makeChild: 'Make a child', remove: 'Remove from family', removeQ: 'Remove {0} from the family?',
    leave: 'Leave the family', leaveQ: 'Leave the family?', copied: 'Copied', shareInvite: 'Join the “{0}” family in the Werd app. Invite code: {1}',
    fromFamily: 'From {0}', accept: 'Add to my awrad', dismiss: 'Dismiss', missed: 'Missed days', noReport: 'Nothing recorded', today: 'Today', historyEmpty: 'No history yet.',
    language: 'Language', theme: 'Theme', light: 'Light', dark: 'Dark', system: 'System', textSize: 'Text size', tapCount: 'Tap a line to count',
    autoAdvance: 'Jump to the next line when one is done', vibrate: 'Vibrate on each count', dayStart: 'New day starts at {0}:00',
    voice: 'Read-aloud voice', voiceBrowser: 'Browser voice', voiceServer: 'Family server voice', voicePick: 'Voice', speed: 'Speed {0}', voiceDefault: 'Default',
    noArabicVoice: 'This browser has no Arabic voice; try the family server voice.', testVoice: 'Test the voice',
    backup: 'Backup', exportB: 'Export', importB: 'Import', imported: 'Backup imported', reset: 'Erase all data', resetQ: 'Erase every werd and all history from this browser?',
    serverHome: 'Server home', serverAdmin: 'Server dashboard',
    about: 'Werd on the web · your data stays in this browser; the library and family come from the Werd server.', error: 'Error: {0}', offline: "Can't reach the server",
    install: 'Install as an app',
  },
};
const MADHHABS = { general: ['عام (لكل المذاهب)', 'General'], hanafi: ['حنفي', 'Hanafi'], maliki: ['مالكي', 'Maliki'], shafii: ['شافعي', "Shafi'i"],
  hanbali: ['حنبلي', 'Hanbali'], jafari: ['جعفري', "Ja'fari"], other: ['آخر', 'Other'] };
const OCCASIONS = { morning: ['أذكار الصباح', 'Morning'], evening: ['أذكار المساء', 'Evening'], morning_evening: ['أذكار الصباح والمساء', 'Morning & evening'],
  after_prayer: ['بعد الصلاة', 'After prayer'], sleep: ['أذكار النوم', 'Sleep'], waking: ['الاستيقاظ', 'Waking'], friday: ['الجمعة', 'Friday'],
  travel: ['السفر', 'Travel'], home: ['المنزل', 'Home'], food: ['الطعام', 'Food'], mosque: ['المسجد', 'Mosque'], distress: ['الكرب', 'Distress'],
  ramadan: ['رمضان', 'Ramadan'], general: ['أدعية عامة', 'General'] };
const COLORS = ['#0E6B4E', '#C19A3E', '#2F6DB3', '#8E4FB0', '#B5523B', '#3C8C8C'];

let S; // the whole saved state
const KEY = 'werd-web-v1';
const lang = () => S.settings.lang || ((navigator.language || 'ar').toLowerCase().startsWith('en') ? 'en' : 'ar');
function t(k, ...a) {
  const v = (STR[lang()] || STR.ar)[k] ?? STR.ar[k] ?? k;
  return typeof v === 'string' ? v.replace(/\{(\d)\}/g, (_, i) => a[i] ?? '') : v;
}
const L = (pair) => pair[lang() === 'en' ? 1 : 0];
const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const uid = () => Date.now().toString(36) + Math.random().toString(36).slice(2, 7);
const $ = (s, el = document) => el.querySelector(s);

// =============================================================== state
function seed() {
  const g = [{ id: 'g1', name: 'الصباح' }, { id: 'g2', name: 'المساء' }, { id: 'g3', name: 'بعد الصلاة' }];
  const W = (title, group, color, lines) => ({ id: uid(), title, group, color, days: 127, createdAt: Date.now(),
    items: lines.map(([text, target]) => ({ id: uid(), text, target })) });
  return { groups: g, werds: [
    W('أذكار الصباح', 'g1', 1, [
      ['اللَّهُ لَا إِلَٰهَ إِلَّا هُوَ الْحَيُّ الْقَيُّومُ ۚ لَا تَأْخُذُهُ سِنَةٌ وَلَا نَوْمٌ', 1], ['قُلْ هُوَ اللَّهُ أَحَدٌ', 3], ['قُلْ أَعُوذُ بِرَبِّ الْفَلَقِ', 3],
      ['قُلْ أَعُوذُ بِرَبِّ النَّاسِ', 3], ['أَصْبَحْنَا وَأَصْبَحَ الْمُلْكُ لِلَّهِ، وَالْحَمْدُ لِلَّهِ، لَا إِلَهَ إِلَّا اللَّهُ وَحْدَهُ لَا شَرِيكَ لَهُ', 1],
      ['بِسْمِ اللَّهِ الَّذِي لَا يَضُرُّ مَعَ اسْمِهِ شَيْءٌ فِي الْأَرْضِ وَلَا فِي السَّمَاءِ وَهُوَ السَّمِيعُ الْعَلِيمُ', 3],
      ['حَسْبِيَ اللَّهُ لَا إِلَهَ إِلَّا هُوَ عَلَيْهِ تَوَكَّلْتُ وَهُوَ رَبُّ الْعَرْشِ الْعَظِيمِ', 7],
      ['رَضِيتُ بِاللَّهِ رَبًّا، وَبِالْإِسْلَامِ دِينًا، وَبِمُحَمَّدٍ صَلَّى اللَّهُ عَلَيْهِ وَسَلَّمَ نَبِيًّا', 3],
      ['سُبْحَانَ اللَّهِ وَبِحَمْدِهِ', 100], ['أَسْتَغْفِرُ اللَّهَ وَأَتُوبُ إِلَيْهِ', 100]]),
    W('أذكار المساء', 'g2', 0, [
      ['اللَّهُ لَا إِلَٰهَ إِلَّا هُوَ الْحَيُّ الْقَيُّومُ ۚ لَا تَأْخُذُهُ سِنَةٌ وَلَا نَوْمٌ', 1], ['قُلْ هُوَ اللَّهُ أَحَدٌ', 3], ['قُلْ أَعُوذُ بِرَبِّ الْفَلَقِ', 3],
      ['قُلْ أَعُوذُ بِرَبِّ النَّاسِ', 3], ['أَمْسَيْنَا وَأَمْسَى الْمُلْكُ لِلَّهِ، وَالْحَمْدُ لِلَّهِ، لَا إِلَهَ إِلَّا اللَّهُ وَحْدَهُ لَا شَرِيكَ لَهُ', 1],
      ['أَعُوذُ بِكَلِمَاتِ اللَّهِ التَّامَّاتِ مِنْ شَرِّ مَا خَلَقَ', 3], ['لَا حَوْلَ وَلَا قُوَّةَ إِلَّا بِاللَّهِ الْعَلِيِّ الْعَظِيمِ', 10],
      ['حَسْبِيَ اللَّهُ لَا إِلَهَ إِلَّا هُوَ عَلَيْهِ تَوَكَّلْتُ وَهُوَ رَبُّ الْعَرْشِ الْعَظِيمِ', 7], ['سُبْحَانَ اللَّهِ وَبِحَمْدِهِ', 100],
      ['اللَّهُمَّ صَلِّ وَسَلِّمْ عَلَى نَبِيِّنَا مُحَمَّدٍ', 10]]),
    W('التسبيح بعد الصلاة', 'g3', 2, [['أَسْتَغْفِرُ اللَّهَ', 3], ['سُبْحَانَ اللَّهِ', 33], ['الْحَمْدُ لِلَّهِ', 33], ['اللَّهُ أَكْبَرُ', 33],
      ['لَا إِلَهَ إِلَّا اللَّهُ وَحْدَهُ لَا شَرِيكَ لَهُ، لَهُ الْمُلْكُ وَلَهُ الْحَمْدُ وَهُوَ عَلَى كُلِّ شَيْءٍ قَدِيرٌ', 1]]),
  ] };
}
function load() {
  let s = null;
  try { s = JSON.parse(localStorage.getItem(KEY) || 'null'); } catch (e) { /* private mode */ }
  if (!s) s = { ...seed(), days: {}, settings: {}, cloud: {}, handled: [] };
  s.settings = Object.assign({ lang: '', theme: 'system', fs: 1, tapCount: true, autoAdvance: true, vibrate: true, dayStart: 0, focus: false,
    voice: 'browser', browserVoice: '', serverVoice: '', rate: 0.9, autoCount: false, server: '' }, s.settings);
  s.cloud = Object.assign({ token: '', userId: '', name: '', familyId: '', familyName: '', inviteCode: '', share: true }, s.cloud);
  s.days ||= {}; s.handled ||= []; s.groups ||= []; s.werds ||= [];
  return s;
}
let saveTimer = null;
function save(now) {
  clearTimeout(saveTimer);
  const w = () => { try { localStorage.setItem(KEY, JSON.stringify(S)); } catch (e) { toast(t('error', e.message)); } };
  if (now) w(); else saveTimer = setTimeout(w, 250);
}

// =============================================================== days and progress
const pad = (n) => String(n).padStart(2, '0');
function dayOf(date = new Date()) {
  const d = new Date(date.getTime() - (S.settings.dayStart || 0) * 3600e3);
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}
function shiftDay(key, n) { const [y, m, d] = key.split('-').map(Number); const x = new Date(y, m - 1, d + n); return `${x.getFullYear()}-${pad(x.getMonth() + 1)}-${pad(x.getDate())}`; }
const weekday = (key) => { const [y, m, d] = key.split('-').map(Number); return new Date(y, m - 1, d).getDay(); };
const dueOn = (w, key) => !!(w.days & (1 << weekday(key))) && dayOf(new Date(w.createdAt || 0)) <= key;
const session = (key, werdId) => ((S.days[key] ||= {})[werdId] ||= { c: {} });
const peek = (key, werdId) => S.days[key]?.[werdId];
const countOf = (key, w, it) => peek(key, w.id)?.c[it.id] || 0;
function progress(w, key = dayOf()) {
  const total = w.items.reduce((a, it) => a + it.target, 0) || 1;
  const done = w.items.reduce((a, it) => a + Math.min(countOf(key, w, it), it.target), 0);
  return { done, total, p: done / total, complete: done >= total && w.items.length > 0 };
}
function dayComplete(key) {
  const due = S.werds.filter((w) => dueOn(w, key));
  return due.length > 0 && due.every((w) => progress(w, key).complete);
}
function streak() {
  let key = dayOf(), n = 0;
  if (!dayComplete(key)) key = shiftDay(key, -1);
  while (dayComplete(key) && n < 3650) { n++; key = shiftDay(key, -1); }
  return n;
}
function bestStreak() {
  const keys = Object.keys(S.days).sort();
  if (!keys.length) return 0;
  let best = 0, run = 0, k = keys[0];
  const end = dayOf();
  while (k <= end) { if (dayComplete(k)) { run++; best = Math.max(best, run); } else run = 0; k = shiftDay(k, 1); }
  return best;
}
function count(w, it, delta) {
  const key = dayOf();
  const s = session(key, w.id);
  const before = progress(w, key).complete;
  const cur = s.c[it.id] || 0;
  const next = Math.max(0, Math.min(it.target, cur + delta));
  if (next === cur) return { changed: false };
  s.c[it.id] = next;
  const after = progress(w, key).complete;
  if (after && !before) s.done = Date.now();
  if (!after) delete s.done;
  save();
  if (S.settings.vibrate && navigator.vibrate) navigator.vibrate(next >= it.target ? [30, 40, 30] : 12);
  if (after && !before) cloud.syncSoon();
  return { changed: true, itemDone: next >= it.target, justCompleted: after && !before };
}

// A photo → base64 JPEG, longest side ≤ max (keeps uploads small and is what the models see anyway).
function shrinkImage(file, max) {
  return new Promise((res, rej) => {
    const img = new Image();
    img.onload = () => {
      const k = Math.min(1, max / Math.max(img.width, img.height));
      const c = document.createElement('canvas'); c.width = Math.round(img.width * k); c.height = Math.round(img.height * k);
      c.getContext('2d').drawImage(img, 0, 0, c.width, c.height);
      URL.revokeObjectURL(img.src);
      res(c.toDataURL('image/jpeg', 0.9).split(',')[1]);
    };
    img.onerror = () => rej(new Error('image'));
    img.src = URL.createObjectURL(file);
  });
}

// =============================================================== server
const cloud = {
  base() {
    const s = (S.settings.server || '').trim().replace(/\/+$/, '');
    if (s) return /^https?:\/\//.test(s) ? s : 'http://' + s;
    return /^https?:$/.test(location.protocol) ? location.origin : '';
  },
  configured() { return !!this.base(); },
  async call(method, path, body, auth = true) {
    const base = this.base();
    if (!base) throw new Error(t('noServer'));
    const h = { 'Content-Type': 'application/json' };
    if (auth && S.cloud.token) h.Authorization = 'Bearer ' + S.cloud.token;
    let r;
    try { r = await fetch(base + path, { method, headers: h, body: body ? JSON.stringify(body) : undefined }); }
    catch (e) { throw new Error(t('offline')); }
    if (r.status === 401 && auth && S.cloud.token) { // the server forgot this browser: sign up again once
      Object.assign(S.cloud, { token: '', userId: '', familyId: '', familyName: '', inviteCode: '' }); save(true);
      await this.ensure();
      return this.call(method, path, body, auth);
    }
    const ct = r.headers.get('content-type') || '';
    if (ct.startsWith('audio/')) { if (!r.ok) throw new Error('HTTP ' + r.status); return r.blob(); }
    const j = await r.json().catch(() => ({}));
    if (!r.ok) { const e = new Error(j.error?.message || 'HTTP ' + r.status); e.status = r.status; e.code = j.error?.code; throw e; }
    return j;
  },
  async ensure(name) {
    if (S.cloud.token) return;
    const n = (name || S.cloud.name || (lang() === 'en' ? 'Werd web' : 'ورد على الويب')).trim().slice(0, 40);
    const r = await this.call('POST', '/v1/accounts', { displayName: n }, false);
    Object.assign(S.cloud, { token: r.token, userId: r.userId, name: n }); save(true);
  },
  summary() {
    const key = dayOf();
    const due = S.werds.filter((w) => dueOn(w, key));
    return { sharing: S.cloud.share, day: key, streak: streak(), due: due.length, done: due.filter((w) => progress(w, key).complete).length,
      werds: due.map((w) => { const p = progress(w, key); return { title: w.title, done: p.done, total: p.total, completed: p.complete }; }) };
  },
  async sync() {
    if (!this.configured() || !S.cloud.token) return;
    if (S.cloud.familyId) await this.call('PUT', '/v1/progress', S.cloud.share ? this.summary() : { sharing: false }).catch(() => {});
    await this.inbox().catch(() => {});
  },
  syncSoon() { clearTimeout(this._t); this._t = setTimeout(() => this.sync(), 3000); },
  async inbox() {
    const r = await this.call('GET', '/v1/inbox');
    const fresh = (r.werds || []).filter((w) => !S.handled.includes(w.id));
    S.inbox = fresh;
    for (const w of fresh.filter((x) => x.autoAdd)) await acceptInbox(w);
    if (route().name === 'home') render();
  },
};
async function acceptInbox(w) {
  const set = w.werd || {};
  let group = '';
  if (set.group) { group = (S.groups.find((g) => g.name === set.group) || (() => { const g = { id: uid(), name: set.group }; S.groups.push(g); return g; })()).id; }
  S.werds.push({ id: uid(), title: w.title || '—', group, color: (set.colorIndex || 0) % COLORS.length, days: set.goalDaysMask > 0 && set.goalDaysMask < 128 ? set.goalDaysMask : 127,
    note: set.note || w.note || '', createdAt: Date.now(), items: (w.lines || []).map((l) => ({ id: uid(), text: l.text, target: Math.max(1, l.count || 1) })) });
  await handled(w);
}
async function handled(w) {
  S.handled.push(w.id); S.inbox = (S.inbox || []).filter((x) => x.id !== w.id); save(true);
  cloud.call('DELETE', '/v1/inbox/' + encodeURIComponent(w.id)).catch(() => {});
}

// =============================================================== read-aloud
const voice = {
  speaking: false, index: -1, reps: 0, session: 0, audio: null, clips: new Map(), preparing: false,
  browserVoices() { return (speechSynthesis?.getVoices?.() || []).filter((v) => v.lang?.toLowerCase().startsWith('ar')); },
  start(w, from, onUpdate) {
    this.stop(); this.session++; this.w = w; this.onUpdate = onUpdate;
    this.advance(from);
  },
  remaining(it) {
    const c = countOf(dayOf(), this.w, it);
    return S.settings.autoCount ? Math.max(0, it.target - c) : Math.max(1, it.target);
  },
  advance(i) {
    const w = this.w;
    while (i < w.items.length && this.remaining(w.items[i]) === 0) i++;
    if (i >= w.items.length) { this.speaking = false; this.index = -1; this.onUpdate?.(); return; }
    this.index = i; this.reps = this.remaining(w.items[i]); this.speaking = true;
    this.say();
  },
  async say() {
    const s = this.session, w = this.w, i = this.index, it = w.items[i];
    this.onUpdate?.();
    const next = () => {
      if (s !== this.session) return;
      if (S.settings.autoCount) { const r = count(w, it, 1); if (r.justCompleted) celebrate(w); }
      this.reps--;
      if (this.reps > 0) this.say(); else this.advance(i + 1);
    };
    if (S.settings.voice === 'server' && cloud.configured()) {
      try {
        this.preparing = !this.clips.has(this.clipKey(it.text)); this.onUpdate?.();
        const url = await this.clip(it.text);
        this.preparing = false;
        if (s !== this.session) return;
        const nxt = w.items[i + 1]; if (nxt) this.clip(nxt.text).catch(() => {});
        const a = this.audio = new Audio(url);
        a.playbackRate = Math.min(2, Math.max(0.5, S.settings.rate / 0.9));
        a.onended = next;
        a.onerror = () => { toast(t('error', 'audio')); this.stop(); };
        await a.play();
        return;
      } catch (e) {
        this.preparing = false;
        toast(t('error', e.message));
        // fall back to the browser voice for this line
      }
    }
    if (!('speechSynthesis' in window)) { toast(t('noArabicVoice')); this.stop(); return; }
    const u = new SpeechSynthesisUtterance(it.text);
    u.lang = 'ar';
    const v = this.browserVoices().find((x) => x.name === S.settings.browserVoice) || this.browserVoices()[0];
    if (v) u.voice = v; else if (!this._warned) { this._warned = true; toast(t('noArabicVoice')); }
    u.rate = S.settings.rate / 0.9;
    u.onend = next;
    u.onerror = (e) => { if (e.error !== 'interrupted' && e.error !== 'canceled') this.stop(); };
    speechSynthesis.cancel();
    speechSynthesis.speak(u);
  },
  clipKey(text) { return (S.settings.serverVoice || '') + '\u0000' + text.trim(); },
  async clip(text) {
    const k = this.clipKey(text);
    if (this.clips.has(k)) return this.clips.get(k);
    const p = (async () => {
      const cached = await idb.get(k).catch(() => null);
      const blob = cached || await cloud.call('POST', '/v1/tts', { text: text.trim(), voice: S.settings.serverVoice });
      if (!cached) idb.put(k, blob).catch(() => {});
      return URL.createObjectURL(blob);
    })();
    this.clips.set(k, p);
    p.catch(() => this.clips.delete(k));
    return p;
  },
  stop() {
    this.session++; this.speaking = false; this.preparing = false;
    try { speechSynthesis?.cancel(); } catch (e) { /* none */ }
    if (this.audio) { this.audio.pause(); this.audio = null; }
    this.onUpdate?.();
  },
};
// clips from the family server, kept in the browser
const idb = {
  db() {
    return this._db ||= new Promise((res, rej) => {
      if (!('indexedDB' in window)) return rej(new Error('no IndexedDB'));
      const r = indexedDB.open('werd-voice', 1);
      r.onupgradeneeded = () => r.result.createObjectStore('clips');
      r.onsuccess = () => res(r.result); r.onerror = () => rej(r.error);
    });
  },
  async get(k) { const db = await this.db(); return new Promise((res, rej) => { const q = db.transaction('clips').objectStore('clips').get(k); q.onsuccess = () => res(q.result || null); q.onerror = () => rej(q.error); }); },
  async put(k, v) { const db = await this.db(); return new Promise((res, rej) => { const tx = db.transaction('clips', 'readwrite'); tx.objectStore('clips').put(v, k); tx.oncomplete = res; tx.onerror = () => rej(tx.error); }); },
};

// =============================================================== ui helpers
const ICONS = {
  home: 'M10 20v-6h4v6h5v-8h3L12 3 2 12h3v8z', book: 'M18 2H6c-1.1 0-2 .9-2 2v16c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zM6 4h5v8l-2.5-1.5L6 12V4z',
  chart: 'M5 9.2h3V19H5zM10.6 5h2.8v14h-2.8zm5.6 8H19v6h-2.8z', family: 'M16 11c1.66 0 2.99-1.34 2.99-3S17.66 5 16 5c-1.66 0-3 1.34-3 3s1.34 3 3 3zm-8 0c1.66 0 2.99-1.34 2.99-3S9.66 5 8 5C6.34 5 5 6.34 5 8s1.34 3 3 3zm0 2c-2.33 0-7 1.17-7 3.5V19h14v-2.5c0-2.33-4.67-3.5-7-3.5zm8 0c-.29 0-.62.02-.97.05 1.16.84 1.97 1.97 1.97 3.45V19h6v-2.5c0-2.33-4.67-3.5-7-3.5z',
  gear: 'M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58c.18-.14.23-.41.12-.61l-1.92-3.32c-.12-.22-.37-.29-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54c-.04-.24-.24-.41-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96c-.22-.08-.47 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58c-.18.14-.23.41-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-.22.07-.47-.12-.61l-2.01-1.58zM12 15.6c-1.98 0-3.6-1.62-3.6-3.6s1.62-3.6 3.6-3.6 3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z',
  back: 'M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z', plus: 'M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z', minus: 'M19 13H5v-2h14v2z',
  more: 'M12 8c1.1 0 2-.9 2-2s-.9-2-2-2-2 .9-2 2 .9 2 2 2zm0 2c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2zm0 6c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2z',
  check: 'M9 16.17 4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z', play: 'M8 5v14l11-7z', pause: 'M6 19h4V5H6v14zm8-14v14h4V5h-4z',
  focus: 'M5 15H3v4c0 1.1.9 2 2 2h4v-2H5v-4zM5 5h4V3H5c-1.1 0-2 .9-2 2v4h2V5zm14-2h-4v2h4v4h2V5c0-1.1-.9-2-2-2zm0 16h-4v2h4c1.1 0 2-.9 2-2v-4h-2v4zM12 9c-1.66 0-3 1.34-3 3s1.34 3 3 3 3-1.34 3-3-1.34-3-3-3z',
  list: 'M3 13h2v-2H3v2zm0 4h2v-2H3v2zm0-8h2V7H3v2zm4 4h14v-2H7v2zm0 4h14v-2H7v2zM7 7v2h14V7H7z', undo: 'M12.5 8c-2.65 0-5.05.99-6.9 2.6L2 7v9h9l-3.62-3.62c1.39-1.16 3.16-1.88 5.12-1.88 3.54 0 6.55 2.31 7.6 5.5l2.37-.78C21.08 11.03 17.15 8 12.5 8z',
  next: 'M10 6 8.59 7.41 13.17 12l-4.58 4.59L10 18l6-6z', prev: 'M15.41 7.41 14 6l-6 6 6 6 1.41-1.41L10.83 12z', send: 'M2.01 21 23 12 2.01 3 2 10l15 2-15 2z',
  down: 'M7.41 8.59 12 13.17l4.59-4.58L18 10l-6 6-6-6z', textUp: 'M2.5 4v3h5v12h3V7h5V4h-13zm19 5h-9v3h3v7h3v-7h3V9z', search: 'M15.5 14h-.79l-.28-.27A6.47 6.47 0 0 0 16 9.5 6.5 6.5 0 1 0 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z',
};
const ic = (n, cls = 'i') => `<svg class="${cls}" viewBox="0 0 24 24" aria-hidden="true"><path d="${ICONS[n]}"/></svg>`;
function toast(msg) {
  let el = $('#toast'); if (!el) { el = document.createElement('div'); el.id = 'toast'; document.body.append(el); }
  el.textContent = msg; el.classList.remove('hidden');
  clearTimeout(toast._t); toast._t = setTimeout(() => el.classList.add('hidden'), 3200);
}
function dialog(html, onOpen) {
  const d = document.createElement('dialog');
  d.innerHTML = html; document.body.append(d);
  d.addEventListener('close', () => d.remove());
  d.addEventListener('click', (e) => { if (e.target === d) d.close(); });
  onOpen?.(d);
  d.showModal();
  return d;
}
function confirmBox(msg, okLabel = t('delete'), danger = true) {
  return new Promise((res) => {
    const d = dialog(`<h3>${esc(msg)}</h3><div class="actions"><button class="btn ghost" data-v="0">${t('cancel')}</button>
      <button class="btn ${danger ? 'danger' : ''}" data-v="1">${esc(okLabel)}</button></div>`);
    d.addEventListener('click', (e) => { const b = e.target.closest('[data-v]'); if (b) { d.close(); res(b.dataset.v === '1'); } });
    d.addEventListener('close', () => res(false));
  });
}
function menu(anchor, items) {
  document.querySelectorAll('.menu').forEach((m) => m.remove());
  const m = document.createElement('div'); m.className = 'menu';
  m.innerHTML = items.filter(Boolean).map((it, i) => `<button data-i="${i}" class="${it.danger ? 'danger' : ''}">${esc(it.label)}</button>`).join('');
  document.body.append(m);
  const r = anchor.getBoundingClientRect();
  const rtl = document.dir === 'rtl';
  m.style.top = (window.scrollY + r.bottom + 4) + 'px';
  const x = rtl ? r.left : r.right - m.offsetWidth;
  m.style.left = Math.max(8, Math.min(x, window.innerWidth - m.offsetWidth - 8)) + 'px';
  const list = items.filter(Boolean);
  m.addEventListener('click', (e) => { const b = e.target.closest('[data-i]'); if (b) { m.remove(); list[+b.dataset.i].run(); } });
  setTimeout(() => document.addEventListener('click', function off(e) { if (!m.contains(e.target)) { m.remove(); document.removeEventListener('click', off); } }), 0);
}
const ring = (p, label, cls = '') => `<div class="ring ${cls}" style="--p:${Math.round(p * 100)}"><div>${label}</div></div>`;
const fmtN = (n) => new Intl.NumberFormat('en').format(n);   // Western digits, as in the phone app

// =============================================================== router
function route() {
  const h = location.hash.replace(/^#\/?/, '');
  const [a, b, c] = h.split('/');
  if (!a) return { name: 'home' };
  if (a === 'w') return { name: 'reader', id: b };
  if (a === 'edit') return { name: 'edit', id: b };
  if (a === 'member') return { name: 'member', id: b, label: decodeURIComponent(c || '') };
  return { name: a };
}
const go = (h) => { location.hash = h; };
window.addEventListener('hashchange', () => { voice.stop(); render(); window.scrollTo(0, 0); });

function chrome() {
  const r = route().name;
  const tabs = [['home', '#/', 'home', t('awrad')], ['library', '#/library', 'book', t('library')], ['stats', '#/stats', 'chart', t('stats')],
    ['family', '#/family', 'family', t('family')], ['settings', '#/settings', 'gear', t('settings')]];
  const links = tabs.map(([n, h, i, l]) => `<a href="${h}" class="${r === n || (n === 'home' && (r === 'reader' || r === 'edit')) || (n === 'family' && r === 'member') ? 'on' : ''}"><span class="ic">${ic(i)}</span>${esc(l)}</a>`).join('');
  $('#rail').innerHTML = `<a class="logo" href="${cloud.base() || ''}/" title="${esc(t('serverHome'))}">ورد</a>${links}`;
  $('nav.bottom').innerHTML = links;
}
function applyTheme() {
  const th = S.settings.theme === 'system' ? (matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light') : S.settings.theme;
  document.documentElement.dataset.theme = th;
  document.documentElement.lang = lang();
  document.documentElement.dir = lang() === 'en' ? 'ltr' : 'rtl';
  document.documentElement.style.setProperty('--fs', S.settings.fs);
}
function render() {
  applyTheme(); chrome();
  const r = route();
  const main = $('main');
  const view = { home: viewHome, reader: viewReader, edit: viewEdit, stats: viewStats, library: viewLibrary, family: viewFamily, member: viewMember, settings: viewSettings }[r.name] || viewHome;
  view(main, r);
}

// =============================================================== home
function viewHome(main) {
  const key = dayOf();
  const due = S.werds.filter((w) => dueOn(w, key));
  const doneN = due.filter((w) => progress(w, key).complete).length;
  const hour = new Date().getHours();
  let hijri = '';
  try { hijri = new Intl.DateTimeFormat(lang() === 'en' ? 'en-u-ca-islamic-umalqura' : 'ar-SA-u-ca-islamic-umalqura-nu-latn', { day: 'numeric', month: 'long', year: 'numeric' }).format(new Date()); } catch (e) { /* old browser */ }
   const greg = new Intl.DateTimeFormat(lang() === 'en' ? 'en' : 'ar-u-nu-latn', { weekday: 'long', day: 'numeric', month: 'long' }).format(new Date());
  const st = streak();
  const groups = [...S.groups, { id: '', name: t('noGroup') }];
  const card = (w) => {
    const p = progress(w, key), isDue = dueOn(w, key);
    return `<div class="card werd ${p.complete ? 'done' : ''}" style="--accent:${COLORS[w.color % COLORS.length]}" data-open="${w.id}">
      ${p.complete ? `<div class="ring" style="--p:100;--c:${COLORS[w.color % COLORS.length]}"><div>${ic('check')}</div></div>` : ring(p.p, Math.round(p.p * 100) + '%')}
      <div class="grow"><h4>${esc(w.title)}</h4><div class="meta">${t('lines', fmtN(w.items.length))}${isDue ? '' : ' · ' + t('notToday')}</div></div>
      <button class="iconbtn" data-menu="${w.id}" aria-label="${t('more')}">${ic('more')}</button></div>`;
  };
  const inbox = (S.inbox || []).filter((w) => !w.autoAdd).map((w) => `<div class="card inbox"><div class="small" style="color:var(--gold);font-weight:700">${esc(t('fromFamily', w.from?.displayName || ''))}</div>
    <h4 style="margin:4px 0">${esc(w.title)}</h4>${w.note ? `<div class="muted small">${esc(w.note)}</div>` : ''}<div class="muted small">${t('lines', fmtN((w.lines || []).length))}</div>
    <div class="row" style="margin-top:10px"><button class="btn small" data-accept="${esc(w.id)}">${ic('plus')}${t('accept')}</button><button class="btn ghost small" data-dismiss="${esc(w.id)}">${t('dismiss')}</button></div></div>`).join('');
  main.innerHTML = `<div class="hero"><div class="page"><div class="top"><div><div class="greet">${hour < 12 ? t('greetMorning') : t('greetEvening')}</div><h1>ورد</h1></div>
      <div class="date">${esc(hijri)}<br>${esc(greg)}</div></div>
      <div class="today">${ring(due.length ? doneN / due.length : 0, `${fmtN(doneN)}/${fmtN(due.length)}`)}<div><div class="big">${t('todayAwrad')}</div><div class="sub">${t('doneOf', fmtN(doneN), fmtN(due.length))}</div></div>
      <div class="streak">🔥<div>${fmtN(st)}</div><div class="sub">${t('streakDays')}</div></div></div></div></div>
    <div class="page">${inbox ? `<div style="margin-top:14px" class="stack">${inbox}</div>` : ''}
      <div class="title"><h3>${t('myAwrad')}</h3></div>
      ${S.werds.length ? groups.map((g) => { const known = new Set(S.groups.map((x) => x.id)); const ws = S.werds.filter((w) => g.id ? w.group === g.id : !known.has(w.group)); return ws.length ? `<div class="group-h">${esc(g.name)}</div><div class="stack">${ws.map(card).join('')}</div>` : ''; }).join('')
        : `<div class="empty"><div class="big">﷽</div><h3>${t('noAwrad')}</h3><p>${t('noAwradSub')}</p></div>`}
    </div><button class="fab" onclick="go('#/edit/new')">${ic('plus')}${t('newWerd')}</button>`;
  main.onclick = async (e) => {
    const m = e.target.closest('[data-menu]');
    if (m) { e.stopPropagation(); const w = S.werds.find((x) => x.id === m.dataset.menu); werdMenu(m, w); return; }
    const a = e.target.closest('[data-accept]'); if (a) { const w = S.inbox.find((x) => x.id === a.dataset.accept); await acceptInbox(w); toast(t('added', w.title)); render(); return; }
    const d = e.target.closest('[data-dismiss]'); if (d) { await handled(S.inbox.find((x) => x.id === d.dataset.dismiss)); render(); return; }
    const o = e.target.closest('[data-open]'); if (o) go('#/w/' + o.dataset.open);
  };
}
function werdMenu(anchor, w, inReader) {
  menu(anchor, [
    { label: t('edit'), run: () => go('#/edit/' + w.id) },
    { label: t('duplicate'), run: () => { S.werds.push({ ...structuredClone(w), id: uid(), title: w.title + t('copy'), items: w.items.map((it) => ({ ...it, id: uid() })) }); save(); render(); } },
    inReader && { label: t('markDone'), run: () => { const s = session(dayOf(), w.id); w.items.forEach((it) => { s.c[it.id] = it.target; }); s.done = Date.now(); save(); cloud.syncSoon(); render(); celebrate(w); } },
    inReader && { label: t('resetToday'), run: () => { delete (S.days[dayOf()] || {})[w.id]; save(); render(); } },
    { label: t('delete'), danger: true, run: async () => { if (await confirmBox(t('deleteQ', w.title))) { S.werds = S.werds.filter((x) => x.id !== w.id); save(); go('#/'); render(); } } },
  ]);
}
function celebrate(w) {
  dialog(`<div class="celebrate"><div class="stars">✦ ✦ ✦</div><h3>${t('complete')}</h3><div class="ar">${t('taqabbal')}</div>
    <div>${t('streakNow', fmtN(Math.max(1, streak())))}</div><div class="actions" style="justify-content:center"><button class="btn ghost" data-x="stay">${t('stay')}</button>
    <button class="btn" data-x="done">${t('done')}</button></div></div>`, (d) => d.addEventListener('click', (e) => {
    const b = e.target.closest('[data-x]'); if (!b) return; d.close(); if (b.dataset.x === 'done') { voice.stop(); go('#/'); }
  }));
}

// =============================================================== reader
let focusIndex = 0;
function viewReader(main, r) {
  const w = S.werds.find((x) => x.id === r.id);
  if (!w) { go('#/'); return; }
  const key = dayOf();
  const accent = COLORS[w.color % COLORS.length];
  const firstOpen = w.items.findIndex((it) => countOf(key, w, it) < it.target);
  if (r._fresh !== false && (focusIndex >= w.items.length || main.dataset.werd !== w.id)) focusIndex = Math.max(0, firstOpen);
  main.dataset.werd = w.id;
  const p = progress(w, key);
  const draw = () => {
    const pr = progress(w, key);
    const head = `<div class="bar"><button class="iconbtn" onclick="history.length > 1 ? history.back() : go('#/')" aria-label="${t('back')}">${ic('back')}</button>
      <h2>${esc(w.title)}</h2>
      <button class="iconbtn" data-act="fs-" aria-label="${t('smaller')}" style="font-size:13px;font-weight:700">A−</button>
      <button class="iconbtn" data-act="fs+" aria-label="${t('bigger')}" style="font-size:17px;font-weight:700">A+</button>
      <button class="iconbtn" data-act="mode" aria-label="${S.settings.focus ? t('list') : t('focus')}">${ic(S.settings.focus ? 'list' : 'focus')}</button>
      <button class="iconbtn" data-act="menu" aria-label="${t('more')}">${ic('more')}</button></div>`;
    let body;
    if (S.settings.focus) {
      const i = Math.min(focusIndex, w.items.length - 1), it = w.items[i], c = countOf(key, w, it), done = c >= it.target;
      body = `<div class="focus" style="--accent:${accent}"><div class="row" style="justify-content:center">
          <button class="iconbtn" data-act="prev" ${i <= 0 ? 'disabled' : ''}>${ic(document.dir === 'rtl' ? 'next' : 'prev')}</button>
          <b>${t('lineOf', fmtN(i + 1), fmtN(w.items.length))}</b>
          <button class="iconbtn" data-act="next" ${i >= w.items.length - 1 ? 'disabled' : ''}>${ic(document.dir === 'rtl' ? 'prev' : 'next')}</button></div>
        <div class="progress"><div style="width:${pr.p * 100}%"></div></div>
        <div class="text" id="ftext"><div class="ar ${voice.speaking && voice.index === i ? 'speaking' : ''}">${esc(it.text)}</div></div>
        <button class="btn tonal small more hidden" id="fmore">${t('more')} ${ic('down')}</button>
        <div class="counter"><button class="minus" data-act="undo" ${c ? '' : 'disabled'} aria-label="undo">${ic('undo')}</button>
          <button class="tap" data-act="tap" style="${done ? 'background:var(--done)' : ''}">${ring(c / it.target, `${fmtN(c)}<small>/ ${fmtN(it.target)}</small>${done ? ic('check') : ''}`)}</button>
          <span style="width:40px"></span></div></div>`;
    } else {
      body = `<div class="card row" style="margin-bottom:10px">${ring(pr.p, Math.round(pr.p * 100) + '%')}<div class="grow"><b>${t('doneOf', fmtN(w.items.filter((it) => countOf(key, w, it) >= it.target).length), fmtN(w.items.length))}</b>
          <div class="progress" style="margin-top:6px"><div style="width:${pr.p * 100}%"></div></div></div></div>
        <div class="stack reader-pad">${w.items.map((it, i) => {
          const c = countOf(key, w, it), done = c >= it.target;
          return `<div class="card line ${done ? 'done' : ''} ${voice.speaking && voice.index === i ? 'speaking' : ''}" data-line="${i}" style="--accent:${accent}">
            <div class="row"><div class="num">${fmtN(i + 1)}</div><div class="grow"></div>${done ? `<span style="color:${accent}">${ic('check')}</span>` : ''}</div>
            <div class="ar">${esc(it.text)}</div>
            <div class="row"><div class="grow"><div class="small muted">${t('required', fmtN(it.target))}</div><div class="count">${fmtN(c)} <small>/ ${fmtN(it.target)}</small></div></div>
              ${c ? `<button class="minus" data-minus="${i}" aria-label="undo">${ic('minus')}</button>` : ''}
              <button class="plus" data-plus="${i}" ${done ? 'disabled' : ''} aria-label="+1">${ic(done ? 'check' : 'plus')}</button></div></div>`;
        }).join('')}</div>`;
    }
    const tts = `<div class="tts"><div class="page"><button class="play" data-act="tts" aria-label="${t('listen')}">${ic(voice.speaking ? 'pause' : 'play')}</button>
      <div class="grow"><b>${voice.preparing ? t('preparing') : voice.speaking && voice.index >= 0 ? t('reading', fmtN(voice.index + 1), fmtN(w.items.length)) : t('listen')}</b></div>
      <button class="chip" data-act="rate">${S.settings.rate.toFixed(2).replace(/0$/, '')}×</button>
      <button class="chip ${S.settings.autoCount ? 'on' : ''}" data-act="auto">${t('autoCount')}</button></div></div>`;
    main.innerHTML = `<div class="page">${head}${body}</div>${tts}`;
    const ft = $('#ftext');
    if (ft) {
      const more = $('#fmore');
      const upd = () => more.classList.toggle('hidden', ft.scrollTop + ft.clientHeight >= ft.scrollHeight - 4);
      ft.onscroll = upd; upd();
      more.onclick = () => ft.scrollBy({ top: ft.clientHeight * 0.8, behavior: 'smooth' });
    }
  };
  const after = (res, it, i) => {
    if (!res.changed) return;
    if (res.justCompleted) { draw(); celebrate(w); return; }
    if (res.itemDone && S.settings.autoAdvance) {
      const nxt = w.items.findIndex((x, k) => k > i && countOf(key, w, x) < x.target);
      if (nxt >= 0) {
        if (S.settings.focus) { setTimeout(() => { focusIndex = nxt; draw(); }, 300); }
        else { draw(); document.querySelector(`[data-line="${nxt}"]`)?.scrollIntoView({ behavior: 'smooth', block: 'center' }); return; }
      }
    }
    draw();
  };
  draw();
  if (!S.settings.focus && firstOpen > 0 && !p.complete) setTimeout(() => document.querySelector(`[data-line="${firstOpen}"]`)?.scrollIntoView({ block: 'center' }), 50);
  voice.onUpdate = () => { if (route().name === 'reader') { draw(); if (voice.speaking && !S.settings.focus) document.querySelector(`[data-line="${voice.index}"]`)?.scrollIntoView({ behavior: 'smooth', block: 'center' }); if (voice.speaking && S.settings.focus && focusIndex !== voice.index && voice.index >= 0) { focusIndex = voice.index; draw(); } } };
  let pressTimer = null, longPressed = false;
  main.onpointerdown = (e) => {
    const l = e.target.closest('[data-line]'); if (!l || e.target.closest('button')) return;
    longPressed = false; pressTimer = setTimeout(() => { longPressed = true; const i = +l.dataset.line; after(count(w, w.items[i], -1), w.items[i], i); }, 550);
  };
  main.onpointerup = main.onpointerleave = () => clearTimeout(pressTimer);
  main.onclick = (e) => {
    const b = e.target.closest('[data-act],[data-plus],[data-minus],[data-line]');
    if (!b) return;
    if (b.dataset.plus != null) { const i = +b.dataset.plus; after(count(w, w.items[i], 1), w.items[i], i); return; }
    if (b.dataset.minus != null) { const i = +b.dataset.minus; after(count(w, w.items[i], -1), w.items[i], i); return; }
    if (b.dataset.line != null && !b.dataset.act) { if (longPressed || !S.settings.tapCount) return; const i = +b.dataset.line; after(count(w, w.items[i], 1), w.items[i], i); return; }
    const i = Math.min(focusIndex, w.items.length - 1);
    switch (b.dataset.act) {
      case 'tap': after(count(w, w.items[i], 1), w.items[i], i); break;
      case 'undo': after(count(w, w.items[i], -1), w.items[i], i); break;
      case 'prev': focusIndex = Math.max(0, i - 1); draw(); break;
      case 'next': focusIndex = Math.min(w.items.length - 1, i + 1); draw(); break;
      case 'mode': S.settings.focus = !S.settings.focus; save(); draw(); break;
      case 'fs+': S.settings.fs = Math.min(2.2, +(S.settings.fs + 0.1).toFixed(2)); save(); applyTheme(); break;
      case 'fs-': S.settings.fs = Math.max(0.7, +(S.settings.fs - 0.1).toFixed(2)); save(); applyTheme(); break;
      case 'menu': werdMenu(b, w, true); break;
      case 'tts': if (voice.speaking) voice.stop(); else voice.start(w, S.settings.focus ? i : Math.max(0, w.items.findIndex((x) => countOf(key, w, x) < x.target)), voice.onUpdate); break;
      case 'rate': { const sp = [0.6, 0.75, 0.9, 1.1, 1.3]; const k = sp.findIndex((v) => v >= S.settings.rate - 0.01); S.settings.rate = sp[(k + 1) % sp.length]; save(); draw(); break; }
      case 'auto': S.settings.autoCount = !S.settings.autoCount; save(); draw(); break;
    }
  };
}

// =============================================================== editor
function viewEdit(main, r) {
  const orig = r.id === 'new' ? null : S.werds.find((x) => x.id === r.id);
  const w = orig ? structuredClone(orig) : { id: uid(), title: '', group: '', color: 0, days: 127, createdAt: Date.now(), items: [] };
  let dirty = false;
  const draw = () => {
    main.innerHTML = `<div class="page"><div class="bar"><button class="iconbtn" data-act="back" aria-label="${t('back')}">${ic('back')}</button>
      <h2>${orig ? t('edit') : t('newWerd')}</h2><button class="btn" data-act="save">${t('save')}</button></div>
      <div class="card"><label class="field"><span>${t('title')}</span><input type="text" id="e-title" value="${esc(w.title)}"></label>
        <label class="field"><span>${t('group')}</span><select id="e-group"><option value="">${t('noGroup')}</option>
          ${S.groups.map((g) => `<option value="${g.id}" ${g.id === w.group ? 'selected' : ''}>${esc(g.name)}</option>`).join('')}<option value="__new">${t('newGroup')}</option></select></label>
        <div class="field"><span>${t('days')}</span><div class="days">${t('weekdays').map((d, i) => `<button data-day="${i}" class="${w.days & (1 << i) ? 'on' : ''}">${d}</button>`).join('')}</div></div>
        <div class="field"><span>${t('color')}</span><div class="colors">${COLORS.map((c, i) => `<button data-color="${i}" class="${w.color === i ? 'on' : ''}" style="background:${c}"></button>`).join('')}</div></div></div>
      <div class="card" style="margin-top:12px"><textarea id="e-paste" placeholder="${esc(t('pasteHint'))}"></textarea>
        <div class="row" style="margin-top:8px;flex-wrap:wrap"><button class="btn tonal" data-act="convert">${t('convert')}</button>
          <button class="btn ghost" data-act="scan">📷 ${t('scan')}</button><select id="e-model" class="small" style="display:none;max-width:200px" aria-label="${t('scanModel')}"></select>
          <span class="muted small" id="e-det"></span></div>
        <input type="file" id="e-photo" accept="image/*" capture="environment" hidden></div>
      <div class="title"><h3>${t('linesN', fmtN(w.items.length))}</h3><button class="btn ghost small" data-act="add">${ic('plus')}${t('addLine')}</button></div>
      <div class="stack" style="padding-bottom:40px">${w.items.map((it, i) => `<div class="card edit-line"><textarea class="ar" data-text="${i}" rows="2">${esc(it.text)}</textarea>
        <div class="stack" style="flex:none"><div class="stepper"><button data-dec="${i}">−</button><input type="number" min="1" data-count="${i}" value="${it.target}"><button data-inc="${i}">+</button></div>
        <div class="row" style="justify-content:center"><button class="iconbtn" data-up="${i}" ${i === 0 ? 'disabled' : ''} style="transform:rotate(180deg)">${ic('down')}</button>
        <button class="iconbtn" data-del="${i}" style="color:var(--danger)">✕</button></div></div></div>`).join('')}</div></div>`;
  };
  const scanPage = async (file) => {
    const det = $('#e-det'); const t0 = Date.now();
    const tick = setInterval(() => { det.textContent = t('scanning', fmtN(Math.round((Date.now() - t0) / 1000))) + ' · ' + t('scanFirst'); }, 1000);
    try {
      const image = await shrinkImage(file, 1600);
      const sel = $('#e-model');
      const r = await cloud.call('POST', '/v1/ocr', { image, model: (sel && sel.style.display !== 'none' && sel.value) || S.settings.ocrModel || '' });
      const ta = $('#e-paste'); ta.value = (ta.value.trim() ? ta.value.trim() + '\n\n' : '') + r.text; ta.focus();
      clearInterval(tick); det.textContent = '✓ ' + t('scanned');
    } catch (err) { clearInterval(tick); det.textContent = ''; toast(err.code === 'ocr_off' ? t('scanOff') : err.message); }
  };
  draw();
  main.oninput = (e) => {
    dirty = true;
    if (e.target.id === 'e-title') w.title = e.target.value;
    if (e.target.dataset.text != null) w.items[+e.target.dataset.text].text = e.target.value;
    if (e.target.dataset.count != null) w.items[+e.target.dataset.count].target = Math.max(1, parseInt(e.target.value, 10) || 1);
  };
  main.onchange = (e) => {
    if (e.target.id !== 'e-group') return;
    if (e.target.value === '__new') {
      const d = dialog(`<h3>${t('newGroup')}</h3><input type="text" id="g-name" placeholder="${t('groupName')}"><div class="actions"><button class="btn ghost" data-x="0">${t('cancel')}</button><button class="btn" data-x="1">${t('save')}</button></div>`);
      d.addEventListener('click', (ev) => {
        const b = ev.target.closest('[data-x]'); if (!b) return;
        const n = $('#g-name', d).value.trim();
        if (b.dataset.x === '1' && n) { const g = { id: uid(), name: n }; S.groups.push(g); w.group = g.id; save(); }
        d.close(); draw();
      });
    } else { w.group = e.target.value; dirty = true; }
  };
  main.onclick = async (e) => {
    const b = e.target.closest('button'); if (!b) return;
    const d = b.dataset;
    if (d.day != null) { w.days ^= 1 << +d.day; if (!w.days) w.days = 1 << +d.day; dirty = true; draw(); return; }
    if (d.color != null) { w.color = +d.color; dirty = true; draw(); return; }
    if (d.inc != null) { w.items[+d.inc].target++; dirty = true; draw(); return; }
    if (d.dec != null) { w.items[+d.dec].target = Math.max(1, w.items[+d.dec].target - 1); dirty = true; draw(); return; }
    if (d.del != null) { w.items.splice(+d.del, 1); dirty = true; draw(); return; }
    if (d.up != null) { const i = +d.up; [w.items[i - 1], w.items[i]] = [w.items[i], w.items[i - 1]]; dirty = true; draw(); return; }
    switch (d.act) {
      case 'add': w.items.push({ id: uid(), text: '', target: 1 }); dirty = true; draw(); main.querySelector(`[data-text="${w.items.length - 1}"]`)?.focus(); break;
      case 'convert': {
        const raw = $('#e-paste').value; if (!raw.trim()) return;
        const res = WerdParser.parse(raw);
        if (res.title && !w.title.trim()) w.title = res.title;
        w.items.push(...res.items.map((x) => ({ id: uid(), text: x.text, target: x.count })));
        dirty = true; draw();
        $('#e-det').textContent = res.items.some((x) => x.detected) ? '✓ ' + t('detected') : '';
        break;
      }
      case 'scan': {
        if (!cloud.configured()) { toast(t('noServer')); return; }
        try {
          await cloud.ensure();
          const m = await cloud.call('GET', '/v1/ocr/models');
          if (!m.engine || !m.models.length) { toast(t('scanOff')); return; }
          const sel = $('#e-model');
          if (m.models.length > 1 && sel.style.display === 'none') {
            sel.innerHTML = m.models.map((x) => `<option value="${x.id}" ${x.id === (S.settings.ocrModel || m.default) ? 'selected' : ''}>${esc(x.name)}</option>`).join('');
            sel.style.display = '';
            sel.onchange = () => { S.settings.ocrModel = sel.value; save(); };
          }
        } catch (err) { toast(err.message); return; }
        const inp = $('#e-photo'); inp.value = '';
        inp.onchange = () => inp.files[0] && scanPage(inp.files[0]);
        inp.click();
        break;
      }
      case 'back': if (!dirty || await confirmBox(t('discardQ'), t('cancel'))) history.length > 1 ? history.back() : go('#/'); break;
      case 'save': {
        w.title = w.title.trim(); w.items = w.items.filter((it) => it.text.trim());
        if (!w.title) { toast(t('needTitle')); return; }
        if (!w.items.length) { toast(t('needLines')); return; }
        if (orig) Object.assign(orig, w); else S.werds.push(w);
        save(true); toast(t('saved')); go('#/w/' + w.id);
      }
    }
  };
}

// =============================================================== stats
function viewStats(main) {
  const end = dayOf();
  let total = 0, completeDays = 0;
  for (const [k, day] of Object.entries(S.days)) {
    for (const [wid, s] of Object.entries(day)) total += Object.values(s.c || {}).reduce((a, b) => a + b, 0);
    if (dayComplete(k)) completeDays++;
  }
  const last7 = [...Array(7)].map((_, i) => shiftDay(end, i - 6));
  const counts7 = last7.map((k) => Object.values(S.days[k] || {}).reduce((a, s) => a + Object.values(s.c || {}).reduce((x, y) => x + y, 0), 0));
  const max7 = Math.max(1, ...counts7);
  const last30 = [...Array(30)].map((_, i) => shiftDay(end, i - 29));
  const level = (k) => { const due = S.werds.filter((w) => dueOn(w, k)); if (!due.length) return 0; const d = due.filter((w) => progress(w, k).complete).length; return d / due.length; };
  main.innerHTML = `<div class="hero"><div class="page"><div class="top"><h1 style="font-family:Tajawal;font-size:26px;color:#fff">${t('stats')}</h1>
      <a class="btn tonal small" href="#/family" style="background:rgba(255,255,255,.16);color:#fff;text-decoration:none">${ic('family')}${t('family')}</a></div>
      <div class="today"><div style="font-size:36px">🔥</div><div><div class="big">${fmtN(streak())}</div><div class="sub">${t('currentStreak')}</div></div></div></div></div>
    <div class="page"><div class="stats-grid" style="margin-top:14px">
      <div class="card stat"><div class="n">${fmtN(total)}</div><div class="muted small">${t('totalRec')}</div></div>
      <div class="card stat" style="margin:0"><div class="n">${fmtN(streak())}</div><div class="muted small">${t('currentStreak')}</div></div>
      <div class="card stat" style="margin:0"><div class="n">${fmtN(bestStreak())}</div><div class="muted small">${t('bestStreak')}</div></div>
      <div class="card stat" style="margin:0"><div class="n">${fmtN(completeDays)}</div><div class="muted small">${t('completeDays')}</div></div></div>
      <div class="title"><h3>${t('last7')}</h3></div>
      <div class="card"><div class="bars">${last7.map((k, i) => `<div><div class="b" style="height:${(counts7[i] / max7) * 100}%"></div>${t('weekdays')[weekday(k)]}</div>`).join('')}</div></div>
      <div class="title"><h3>${t('consistency')}</h3></div>
      <div class="card"><div class="heat">${last30.map((k) => { const l = level(k); return `<div title="${k}" style="${l ? `background:color-mix(in srgb, var(--primary) ${Math.round(25 + l * 75)}%, transparent)` : ''}"></div>`; }).join('')}</div></div>
      <div class="title"><h3>${t('perWerd')}</h3></div>
      <div class="stack" style="padding-bottom:20px">${S.werds.map((w) => {
        const dueDays = last30.filter((k) => dueOn(w, k)); const ok = dueDays.filter((k) => progress(w, k).complete).length; const rate = dueDays.length ? ok / dueDays.length : 0;
        return `<div class="card row" style="border-inline-start:5px solid ${COLORS[w.color % COLORS.length]}">${ring(rate, Math.round(rate * 100) + '%')}<div class="grow"><b>${esc(w.title)}</b><div class="muted small">${t('rate30')} · ${fmtN(ok)}/${fmtN(dueDays.length)}</div></div></div>`;
      }).join('')}</div></div>`;
}

// =============================================================== library
let lib = { q: '', madhhab: '', books: null, error: '' };
function viewLibrary(main) {
  const draw = () => {
    main.innerHTML = `<div class="page"><div class="bar"><h2>${t('library')}</h2></div>
      ${!cloud.configured() ? `<div class="card empty">${t('noServer')}</div>` : `
      <input type="search" id="l-q" placeholder="${t('search')}" value="${esc(lib.q)}">
      <div class="chips" style="margin-top:8px"><button class="chip ${lib.madhhab ? '' : 'on'}" data-m="">${t('all')}</button>
        ${Object.entries(MADHHABS).map(([k, v]) => `<button class="chip ${lib.madhhab === k ? 'on' : ''}" data-m="${k}">${esc(L(v))}</button>`).join('')}</div>
      ${lib.error ? `<div class="card" style="color:var(--danger)">${esc(lib.error)}</div>` : ''}
      ${lib.books == null ? '<div class="card empty">…</div>' : !lib.books.length ? `<div class="card empty">${t('libEmpty')}</div>` :
        `<div class="stack" style="padding-bottom:20px">${lib.books.map((b) => `<div class="card row" data-book="${esc(b.id)}" style="cursor:pointer">
          <div class="ring" style="--p:100;--c:var(--gold)"><div>${ic('book')}</div></div>
          <div class="grow"><b>${esc(b.title)}</b>${b.author ? `<div class="small" style="color:var(--gold)">${esc(b.author)}</div>` : ''}
          <div class="muted small">${t('supplications', fmtN(b.items))} · ${t('downloads', fmtN(b.downloads))}</div>
          <div class="row" style="gap:6px;margin-top:4px">${b.visibility === 'family' ? `<span class="pill">${t('familyOnly')}</span>` : ''}${b.madhhab && MADHHABS[b.madhhab] ? `<span class="pill gold">${esc(L(MADHHABS[b.madhhab]))}</span>` : ''}</div></div>
          ${ic('down')}</div>`).join('')}</div>`}`}</div>`;
    const q = $('#l-q');
    if (q) q.oninput = () => { lib.q = q.value; clearTimeout(lib._t); lib._t = setTimeout(load, 400); };
  };
  const load = async () => {
    try {
      if (S.cloud.token || true) await cloud.ensure().catch(() => {});
      const r = await cloud.call('GET', `/v1/library/books?q=${encodeURIComponent(lib.q)}&madhhab=${encodeURIComponent(lib.madhhab)}&limit=60`);
      lib.books = r.books || []; lib.error = '';
    } catch (e) { lib.error = e.message; lib.books = lib.books || []; }
    if (route().name === 'library') { const f = document.activeElement?.id === 'l-q'; draw(); if (f) { const q = $('#l-q'); q.focus(); q.setSelectionRange(q.value.length, q.value.length); } }
  };
  draw();
  if (cloud.configured()) load();
  main.onclick = async (e) => {
    const m = e.target.closest('[data-m]'); if (m) { lib.madhhab = m.dataset.m; draw(); load(); return; }
    const b = e.target.closest('[data-book]'); if (b) openBook(lib.books.find((x) => x.id === b.dataset.book));
  };
}
async function openBook(b) {
  const d = dialog(`<h3>${esc(b.title)}</h3>${b.author ? `<div style="color:var(--gold)">${esc(b.author)}</div>` : ''}<div class="muted small">${t('supplications', fmtN(b.items))}</div>
    <div id="pv" class="stack" style="margin-top:12px">…</div><div class="actions"><button class="btn ghost" data-x="close">${t('cancel')}</button><button class="btn" data-x="add">${t('download')}</button></div>`);
  cloud.call('GET', `/v1/library/books/${encodeURIComponent(b.id)}?preview=true`).then((doc) => {
    $('#pv', d).innerHTML = (doc.items || []).map((it) => `<div class="card"><div class="small muted">${esc(it.title || '')} · ×${fmtN(it.count)}</div><div class="ar" style="font-size:19px">${esc(it.text)}</div></div>`).join('') + '<div class="muted small">…</div>';
  }).catch((e) => { $('#pv', d).textContent = e.message; });
  d.addEventListener('click', async (e) => {
    const x = e.target.closest('[data-x]'); if (!x) return;
    if (x.dataset.x === 'close') { d.close(); return; }
    d.close();
    const how = dialog(`<h3>${t('addAs')}</h3><div class="stack"><button class="btn" data-h="one" style="width:100%;justify-content:center">${t('oneWerd')}</button>
      <button class="btn tonal" data-h="occ" style="width:100%;justify-content:center">${t('perOccasion')}</button></div><div class="actions"><button class="btn ghost" data-h="x">${t('cancel')}</button></div>`);
    how.addEventListener('click', async (ev) => {
      const h = ev.target.closest('[data-h]'); if (!h) return; how.close(); if (h.dataset.h === 'x') return;
      try {
        const doc = await cloud.call('GET', `/v1/library/books/${encodeURIComponent(b.id)}`);
        const items = doc.items || [];
        const add = (title, list, color) => S.werds.push({ id: uid(), title, group: '', color, days: 127, createdAt: Date.now(), source: b.id,
          items: list.map((it) => ({ id: uid(), text: it.text, target: Math.max(1, it.count || 1) })) });
        if (h.dataset.h === 'one') add(doc.title || b.title, items, 1);
        else {
          const by = {}; items.forEach((it) => { (by[it.occasion || 'general'] ||= []).push(it); });
          Object.entries(by).forEach(([o, list], i) => add(`${L(OCCASIONS[o] || OCCASIONS.general)} — ${doc.title || b.title}`, list, i % COLORS.length));
        }
        save(true); toast(t('added', doc.title || b.title)); go('#/');
      } catch (er) { toast(t('error', er.message)); }
    });
  });
}

// =============================================================== family
let fam = { data: null, error: '' };
function viewFamily(main) {
  const draw = () => {
    const f = fam.data;
    let body;
    if (!cloud.configured()) body = `<div class="card empty">${t('noServer')}</div>`;
    else if (!S.cloud.familyId) body = `<div class="card"><p>${t('famIntro')}</p>
        <label class="field"><span>${t('yourName')}</span><input type="text" id="f-me" value="${esc(S.cloud.name)}"></label>
        <label class="field"><span>${t('familyName')}</span><input type="text" id="f-name"></label><button class="btn" data-act="create">${t('createFamily')}</button>
        <hr style="border:0;border-top:1px solid var(--line);margin:18px 0">
        <label class="field"><span>${t('inviteCode')}</span><input type="text" id="f-code" placeholder="K7Q-4MX" style="direction:ltr;text-transform:uppercase"></label>
        <button class="btn tonal" data-act="join">${t('joinFamily')}</button></div>`;
    else {
      const parent = f?.myRole === 'parent';
      const today = dayOf();
      body = `<div class="card row" style="background:color-mix(in srgb, var(--primary) 10%, var(--surface))"><div class="grow"><div class="small" style="color:var(--gold);font-weight:700">${t('inviteCode')}</div>
          <div style="font-size:24px;font-weight:700;letter-spacing:3px;direction:ltr">${esc(S.cloud.inviteCode)}</div></div>
          <button class="btn tonal small" data-act="copy" aria-label="copy">⧉</button><button class="btn small" data-act="shareinv">${ic('send')}</button></div>
        <div class="card"><div class="setting"><div class="grow"><b>${t('shareProgress')}</b><div class="muted small">${t('shareProgressD')}</div></div>
          <label class="switch"><input type="checkbox" id="f-share" ${S.cloud.share ? 'checked' : ''}><span></span></label></div></div>
        ${fam.error ? `<div class="card" style="color:var(--danger)">${esc(fam.error)}</div>` : ''}
        <div class="stack" style="margin-top:10px;padding-bottom:20px">${(f?.members || []).map((m) => {
          const p = m.progress, isToday = p && p.day === today;
          return `<div class="card"><div class="row"><div class="ring" style="--p:${isToday && p.due ? Math.round(100 * p.done / p.due) : 0}"><div>${esc((m.displayName || '؟').trim().slice(0, 1))}</div></div>
            <div class="grow"><b>${esc(m.isMe ? t('me', m.displayName) : m.displayName)}</b> <span class="pill ${m.role === 'parent' ? 'gold' : ''}">${m.role === 'parent' ? t('parent') : t('child')}</span>
              <div class="muted small">${!m.sharing || !p ? t('notSharing') : !isToday ? t('nothingToday') : t('todayOf', fmtN(p.done), fmtN(p.due))}</div></div>
            ${p && m.sharing && p.streak ? `<b>🔥 ${fmtN(p.streak)}</b>` : ''}
            ${!m.isMe ? `<button class="iconbtn" data-send="${esc(m.userId)}" aria-label="${t('send')}">${ic('send')}</button>` : ''}
            ${parent || m.isMe ? `<button class="iconbtn" data-mm="${esc(m.userId)}" aria-label="${t('more')}">${ic('more')}</button>` : ''}</div>
            ${isToday && m.sharing ? (p.werds || []).map((x) => `<div class="row small" style="margin-top:6px"><span style="color:${x.completed ? 'var(--primary)' : 'var(--muted)'}">${x.completed ? '●' : '○'}</span><span class="grow">${esc(x.title)}</span><span class="muted">${fmtN(x.done)}/${fmtN(x.total)}</span></div>`).join('') : ''}</div>`;
        }).join('')}</div><button class="btn danger" data-act="leave">${t('leave')}</button>`;
    }
    main.innerHTML = `<div class="page"><div class="bar"><h2>${esc(S.cloud.familyName || t('family'))}</h2></div>${body}</div>`;
    const sh = $('#f-share'); if (sh) sh.onchange = async () => { S.cloud.share = sh.checked; save(true); await cloud.sync(); refresh(); };
  };
  const refresh = async () => {
    if (!cloud.configured() || !S.cloud.familyId) return;
    try {
      await cloud.call('PUT', '/v1/progress', S.cloud.share ? cloud.summary() : { sharing: false });
      fam.data = await cloud.call('GET', '/v1/families/me'); fam.error = '';
      Object.assign(S.cloud, { familyName: fam.data.name, inviteCode: fam.data.inviteCode }); save();
    } catch (e) {
      if (e.status === 404) { Object.assign(S.cloud, { familyId: '', familyName: '', inviteCode: '' }); save(true); }
      fam.error = e.message;
    }
    if (route().name === 'family') draw();
  };
  draw(); refresh();
  main.onclick = async (e) => {
    const b = e.target.closest('button'); if (!b) return;
    try {
      if (b.dataset.act === 'create' || b.dataset.act === 'join') {
        const me = $('#f-me').value.trim();
        if (me) S.cloud.name = me;
        await cloud.ensure(me);
        if (me) await cloud.call('PUT', '/v1/accounts/me', { displayName: me });
        const f = b.dataset.act === 'create'
          ? await cloud.call('POST', '/v1/families', { name: $('#f-name').value.trim() || S.cloud.name })
          : await cloud.call('POST', '/v1/families/join', { inviteCode: $('#f-code').value.trim().toUpperCase() });
        Object.assign(S.cloud, { familyId: f.familyId, familyName: f.name, inviteCode: f.inviteCode }); save(true);
        draw(); refresh(); return;
      }
      if (b.dataset.act === 'copy') { await navigator.clipboard?.writeText(S.cloud.inviteCode); toast(t('copied')); return; }
      if (b.dataset.act === 'shareinv') { const txt = t('shareInvite', S.cloud.familyName, S.cloud.inviteCode); if (navigator.share) navigator.share({ text: txt }).catch(() => {}); else { await navigator.clipboard?.writeText(txt); toast(t('copied')); } return; }
      if (b.dataset.act === 'leave') {
        if (!await confirmBox(t('leaveQ'), t('leave'))) return;
        await cloud.call('DELETE', '/v1/families/me/membership');
        Object.assign(S.cloud, { familyId: '', familyName: '', inviteCode: '' }); fam.data = null; save(true); draw(); return;
      }
      if (b.dataset.send) { sendDialog(fam.data.members.find((m) => m.userId === b.dataset.send), fam.data.myRole === 'parent'); return; }
      if (b.dataset.mm) {
        const m = fam.data.members.find((x) => x.userId === b.dataset.mm);
        const parent = fam.data.myRole === 'parent';
        menu(b, [
          { label: t('history'), run: () => go(`#/member/${encodeURIComponent(m.userId)}/${encodeURIComponent(m.displayName)}`) },
          parent && !m.isMe && { label: m.role === 'parent' ? t('makeChild') : t('makeParent'), run: async () => {
            try { await cloud.call('PUT', '/v1/families/me/members/' + encodeURIComponent(m.userId), { role: m.role === 'parent' ? 'child' : 'parent' }); } catch (er) { toast(er.message); } refresh(); } },
          parent && !m.isMe && { label: t('remove'), danger: true, run: async () => {
            if (!await confirmBox(t('removeQ', m.displayName), t('remove'))) return;
            try { await cloud.call('DELETE', '/v1/families/me/members/' + encodeURIComponent(m.userId)); } catch (er) { toast(er.message); } refresh(); } },
        ]);
      }
    } catch (er) { toast(t('error', er.message)); }
  };
}
function sendDialog(m, iAmParent) {
  const canAuto = iAmParent && m.role !== 'parent';
  const d = dialog(`<h3>${esc(t('sendTo', m.displayName))}</h3><div class="stack">${S.werds.map((w, i) => `<label class="row" style="cursor:pointer"><input type="radio" name="sw" value="${w.id}" ${i === 0 ? 'checked' : ''}>
      <span class="grow">${esc(w.title)}<div class="muted small">${t('lines', fmtN(w.items.length))}</div></span></label>`).join('')}</div>
    <label class="field"><span>${t('note')}</span><input type="text" id="s-note"></label>
    ${canAuto ? `<div class="setting"><div class="grow">${t('addDirect')}</div><label class="switch"><input type="checkbox" id="s-auto" checked><span></span></label></div>` : ''}
    <div class="actions"><button class="btn ghost" data-x="0">${t('cancel')}</button><button class="btn" data-x="1">${t('send')}</button></div>`);
  d.addEventListener('click', async (e) => {
    const b = e.target.closest('[data-x]'); if (!b) return;
    if (b.dataset.x === '0') { d.close(); return; }
    const w = S.werds.find((x) => x.id === $('input[name=sw]:checked', d)?.value); if (!w) return;
    const g = S.groups.find((x) => x.id === w.group);
    try {
      await cloud.call('POST', `/v1/families/me/members/${encodeURIComponent(m.userId)}/werds`, { title: w.title, note: $('#s-note', d).value.trim(),
        lines: w.items.map((it) => ({ text: it.text, count: it.target })), autoAdd: !!$('#s-auto', d)?.checked,
        werd: { colorIndex: w.color, goalDaysMask: w.days, note: w.note || '', group: g?.name || '', reminders: [] } });
      d.close(); toast(t('sent'));
    } catch (er) { toast(t('error', er.message)); }
  });
}
function viewMember(main, r) {
  main.innerHTML = `<div class="page"><div class="bar"><button class="iconbtn" onclick="history.back()">${ic('back')}</button><h2>${esc(r.label || t('history'))}</h2></div><div id="mh" class="card empty">…</div></div>`;
  cloud.call('GET', `/v1/families/me/members/${encodeURIComponent(r.id)}/history?days=30`).then((h) => {
    const by = Object.fromEntries((h.days || []).map((x) => [x.day, x]));
    if (!h.days?.length) { $('#mh').textContent = t('historyEmpty'); return; }
    const today = dayOf();
    const first = h.days.map((x) => x.day).sort()[0];
    const span = [...Array(30)].map((_, i) => shiftDay(today, -i)).filter((k) => k >= first);
    const complete = span.filter((k) => by[k] && by[k].due > 0 && by[k].done >= by[k].due).length;
    const missed = span.filter((k) => (by[k] ? by[k].due > 0 && by[k].done < by[k].due : k !== today) && k !== today).length;
    $('#mh').outerHTML = `<div class="stats-grid" style="grid-template-columns:repeat(3,1fr)"><div class="card stat" style="margin:0"><div class="n">${fmtN(complete)}/${fmtN(span.length)}</div><div class="muted small">${t('completeDays')}</div></div>
      <div class="card stat" style="margin:0"><div class="n">${fmtN(missed)}</div><div class="muted small">${t('missed')}</div></div>
      <div class="card stat" style="margin:0"><div class="n">🔥 ${fmtN(h.days[0].streak || 0)}</div><div class="muted small">${t('currentStreak')}</div></div></div>
      <div class="stack" style="margin-top:12px;padding-bottom:20px">${span.map((k) => { const p = by[k]; const ok = p && p.due > 0 && p.done >= p.due; const miss = !ok && k !== today;
        return `<div class="card" style="${miss ? 'border-color:color-mix(in srgb, var(--danger) 40%, transparent)' : ''}"><div class="row"><b style="color:${ok ? 'var(--primary)' : miss ? 'var(--danger)' : 'var(--muted)'}">${ok ? '✓' : miss ? '✕' : '○'}</b>
          <span class="grow">${k === today ? t('today') : new Intl.DateTimeFormat(lang() === 'en' ? 'en' : 'ar-u-nu-latn', { weekday: 'short', day: 'numeric', month: 'short' }).format(new Date(k + 'T12:00'))}</span>
          <span class="muted small">${p ? t('todayOf', fmtN(p.done), fmtN(p.due)) : t('noReport')}</span></div>
          ${p && p.due ? `<div class="progress" style="margin-top:6px"><div style="width:${100 * p.done / p.due}%"></div></div>` : ''}</div>`; }).join('')}</div>`;
  }).catch((e) => { $('#mh').textContent = e.message; });
}

// =============================================================== settings
function viewSettings(main) {
  const s = S.settings;
  const sw = (id, on) => `<label class="switch"><input type="checkbox" id="${id}" ${on ? 'checked' : ''}><span></span></label>`;
  const bv = voice.browserVoices();
  main.innerHTML = `<div class="page"><div class="bar"><h2>${t('settings')}</h2></div>
    <div class="card"><div class="setting"><div class="grow"><b>${t('language')}</b></div><div class="chips" style="padding:0">
        <button class="chip ${lang() === 'ar' ? 'on' : ''}" data-lang="ar">العربية</button><button class="chip ${lang() === 'en' ? 'on' : ''}" data-lang="en">English</button></div></div>
      <div class="setting"><div class="grow"><b>${t('theme')}</b></div><div class="chips" style="padding:0">${['light', 'dark', 'system'].map((x) => `<button class="chip ${s.theme === x ? 'on' : ''}" data-theme="${x}">${t(x)}</button>`).join('')}</div></div>
      <div class="setting"><div class="grow"><b>${t('textSize')}</b><div class="ar" style="font-size:calc(21px * var(--fs))">سُبْحَانَ اللهِ وَبِحَمْدِهِ</div></div><input type="range" id="s-fs" min="0.7" max="2.2" step="0.1" value="${s.fs}"></div>
      <div class="setting"><div class="grow">${t('tapCount')}</div>${sw('s-tap', s.tapCount)}</div>
      <div class="setting"><div class="grow">${t('autoAdvance')}</div>${sw('s-adv', s.autoAdvance)}</div>
      <div class="setting"><div class="grow">${t('vibrate')}</div>${sw('s-vib', s.vibrate)}</div>
      <div class="setting"><div class="grow">${t('dayStart', s.dayStart)}</div><input type="range" id="s-day" min="0" max="6" step="1" value="${s.dayStart}"></div></div>
    <div class="title"><h3>${t('voice')}</h3></div>
    <div class="card"><div class="chips" style="padding:0 0 8px"><button class="chip ${s.voice === 'browser' ? 'on' : ''}" data-voice="browser">${t('voiceBrowser')}</button>
        <button class="chip ${s.voice === 'server' ? 'on' : ''}" data-voice="server">${t('voiceServer')}</button></div>
      ${s.voice === 'browser' ? (bv.length ? `<label class="field"><span>${t('voicePick')}</span><select id="s-bv"><option value="">${t('voiceDefault')}</option>${bv.map((v) => `<option ${v.name === s.browserVoice ? 'selected' : ''}>${esc(v.name)}</option>`).join('')}</select></label>` : `<p class="muted small">${t('noArabicVoice')}</p>`)
        : `<label class="field"><span>${t('voicePick')}</span><select id="s-sv"><option value="">${t('voiceDefault')}</option></select></label>`}
      <label class="field"><span>${t('speed', s.rate.toFixed(2))}</span><input type="range" id="s-rate" min="0.5" max="1.6" step="0.05" value="${s.rate}" style="width:100%"></label>
      <button class="btn tonal" id="s-test">${ic('play')}${t('testVoice')}</button></div>
    <div class="title"><h3>${t('server')}</h3></div>
    <div class="card"><label class="field"><span>${t('serverAddr')}</span><input type="url" id="s-server" value="${esc(s.server)}" placeholder="${esc(location.origin)}" style="direction:ltr"></label>
      <div class="muted small">${t('serverHint')}</div>
      <label class="field"><span>${t('yourName')}</span><input type="text" id="s-name" value="${esc(S.cloud.name)}"></label>
      <div class="row"><button class="btn" id="s-save">${t('test')}</button><span class="small" id="s-status"></span></div></div>
    <div class="title"><h3>${t('backup')}</h3></div>
    <div class="card row" style="flex-wrap:wrap"><button class="btn tonal" id="s-export">${t('exportB')}</button><label class="btn tonal" style="cursor:pointer">${t('importB')}<input type="file" id="s-import" accept=".json,application/json" class="hidden"></label>
      <span class="grow"></span><button class="btn danger" id="s-reset">${t('reset')}</button></div>
    <p class="muted small" style="text-align:center;padding:20px 0">${t('about')}<br><a href="${cloud.base() || ''}/">${t('serverHome')}</a> · <a href="${cloud.base() || ''}/admin">${t('serverAdmin')}</a></p></div>`;
  if (s.voice === 'server' && cloud.configured()) {
    cloud.ensure().then(() => cloud.call('GET', '/v1/tts/voices')).then((r) => {
      const sel = $('#s-sv'); if (!sel) return;
      sel.innerHTML = `<option value="">${t('voiceDefault')}${r.default ? ' · ' + esc(r.default) : ''}</option>` + (r.voices || []).map((v) =>
        `<option value="${esc(v.id)}" ${v.id === s.serverVoice ? 'selected' : ''}>${esc(v.name)}${v.provider === 'shared' ? ' · ' + v.detail : v.detail ? ' · ' + esc(v.detail) : ''}</option>`).join('');
    }).catch((e) => toast(t('error', e.message)));
  }
  const on = (id, ev, f) => { const el = $('#' + id); if (el) el[ev] = f; };
  on('s-fs', 'oninput', (e) => { s.fs = +e.target.value; save(); applyTheme(); });
  on('s-tap', 'onchange', (e) => { s.tapCount = e.target.checked; save(); });
  on('s-adv', 'onchange', (e) => { s.autoAdvance = e.target.checked; save(); });
  on('s-vib', 'onchange', (e) => { s.vibrate = e.target.checked; save(); });
  on('s-day', 'onchange', (e) => { s.dayStart = +e.target.value; save(); render(); });
  on('s-rate', 'onchange', (e) => { s.rate = +e.target.value; save(); render(); });
  on('s-bv', 'onchange', (e) => { s.browserVoice = e.target.value; save(); });
  on('s-sv', 'onchange', (e) => { s.serverVoice = e.target.value; voice.clips.clear(); save(); });
  on('s-test', 'onclick', () => {
    const w = { id: 'test', items: [{ id: 't', text: 'سُبْحَانَ اللَّهِ وَبِحَمْدِهِ، سُبْحَانَ اللَّهِ الْعَظِيمِ', target: 1 }] };
    const auto = s.autoCount; s.autoCount = false; voice.start(w, 0, () => {}); s.autoCount = auto;
  });
  on('s-save', 'onclick', async () => {
    s.server = $('#s-server').value.trim(); const name = $('#s-name').value.trim();
    save(true);
    const st = $('#s-status');
    try {
      const info = await cloud.call('GET', '/v1/info', null, false);
      if (info.name !== 'werd-server') throw new Error('not a Werd server');
      await cloud.ensure(name);
      if (name && name !== S.cloud.name) { await cloud.call('PUT', '/v1/accounts/me', { displayName: name }); S.cloud.name = name; save(true); }
      st.style.color = 'var(--primary)'; st.textContent = '✓ ' + t('connected', info.version);
    } catch (e) { st.style.color = 'var(--danger)'; st.textContent = t('notConnected', e.message); }
  });
  on('s-export', 'onclick', () => {
    const a = document.createElement('a');
    a.href = URL.createObjectURL(new Blob([JSON.stringify({ format: 'werd-web-backup', version: 1, exportedAt: Date.now(), data: { ...S, inbox: undefined } }, null, 1)], { type: 'application/json' }));
    a.download = `werd-${dayOf()}.json`; a.click();
  });
  on('s-import', 'onchange', async (e) => {
    const f = e.target.files[0]; if (!f) return;
    try {
      const j = JSON.parse(await f.text());
      if (j.format !== 'werd-web-backup' || !j.data?.werds) throw new Error('not a Werd web backup');
      S = Object.assign(load(), j.data); save(true); toast(t('imported')); render();
    } catch (er) { toast(t('error', er.message)); }
  });
  on('s-reset', 'onclick', async () => { if (await confirmBox(t('resetQ'), t('reset'))) { localStorage.removeItem(KEY); S = load(); save(true); go('#/'); render(); } });
  main.onclick = (e) => {
    const b = e.target.closest('button'); if (!b) return;
    if (b.dataset.lang) { s.lang = b.dataset.lang; save(); render(); }
    if (b.dataset.theme) { s.theme = b.dataset.theme; save(); render(); }
    if (b.dataset.voice) { s.voice = b.dataset.voice; save(); render(); }
  };
}

// =============================================================== start
S = load();
if (!localStorage.getItem(KEY)) save(true);
speechSynthesis?.addEventListener?.('voiceschanged', () => { if (route().name === 'settings') render(); });
matchMedia('(prefers-color-scheme: dark)').addEventListener?.('change', applyTheme);
document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'visible') { cloud.sync(); if (route().name === 'home') render(); } });
render();
cloud.sync();
if ('serviceWorker' in navigator && window.isSecureContext) navigator.serviceWorker.register('sw.js').catch(() => {});
