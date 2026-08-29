# GT Wake — store "What's new" copy

Paste-ready release copy for **Google Play** ("What's new", **500 character limit per
language**) and **Huawei AppGallery** ("New features" / version description, same text works).

Rules that keep this useful:
- User-facing only. No file names, no API names, no internal terminology.
- Lead with what changed for the person holding the phone, not what changed in the code.
- Keep every language under 500 characters — Play truncates silently.
- Technical detail belongs in `docs/release-notes.md`, not here.

The full engineering changelog for each version is in `docs/release-notes.md`.

---

## 1.0.9 — 2026-08-29

Ships **both** apps (phone `versionCode 9`, watch `code 1000008`). The watch fixes are the
substantive part; upload the watch `.app` alongside the phone build.

### EN (418 chars)
```
Watch fixes

• Your alarm no longer snoozes itself. Raising your wrist, or letting the always-on display take over, used to be read as a snooze — it isn't any more.
• The alarm stays on your watch screen until you dismiss or snooze it yourself.
• Dismissing on your phone now reliably stops the alarm on your watch too.

Also: a new Help entry explaining why an alarm can ring without opening full screen.
```

### RU (441 chars)
```
Исправления для часов

• Будильник больше не откладывается сам. Раньше поднятие руки или переход в режим постоянного экрана воспринимались как «Отложить» — теперь нет.
• Будильник остаётся на экране часов, пока вы сами его не выключите или не отложите.
• Выключение на телефоне теперь надёжно останавливает будильник и на часах.

Также: новый раздел справки о том, почему будильник может звонить, не открываясь на весь экран.
```

### UK (446 chars)
```
Виправлення для годинника

• Будильник більше не відкладається сам. Раніше підняття руки або перехід у режим постійного екрана сприймалися як «Відкласти» — тепер ні.
• Будильник залишається на екрані годинника, доки ви самі його не вимкнете або не відкладете.
• Вимкнення на телефоні тепер надійно зупиняє будильник і на годиннику.

Також: новий розділ довідки про те, чому будильник може дзвонити, не відкриваючись на весь екран.
```

### BY (450 chars)
```
Выпраўленні для гадзінніка

• Будзільнік больш не адкладваецца сам. Раней паднясенне рукі або пераход у рэжым пастаяннага экрана ўспрымаліся як «Адкласці» — цяпер не.
• Будзільнік застаецца на экране гадзінніка, пакуль вы самі яго не выключыце або не адкладзяце.
• Выключэнне на тэлефоне цяпер надзейна спыняе будзільнік і на гадзінніку.

Таксама: новы раздзел даведкі пра тое, чаму будзільнік можа званіць, не адкрываючыся на ўвесь экран.
```

### PL (424 chars)
```
Poprawki dla zegarka

• Alarm nie włącza już sam drzemki. Podniesienie nadgarstka lub przejście na ekran always-on było odczytywane jako drzemka — już nie jest.
• Alarm pozostaje na ekranie zegarka, dopóki sam go nie wyłączysz lub nie odłożysz.
• Wyłączenie alarmu w telefonie niezawodnie zatrzymuje go teraz także na zegarku.

Ponadto: nowy wpis w pomocy wyjaśniający, dlaczego alarm może dzwonić bez otwarcia pełnego ekranu.
```

### ZH-CN (168 chars)
```
手表修复

• 闹钟不再自动稍后提醒。此前抬腕或进入息屏常显会被误判为「稍后提醒」，现已修复。
• 闹钟会一直显示在手表上，直到你自己关闭或选择稍后提醒。
• 在手机上关闭闹钟，现在也能可靠地同时停止手表上的闹钟。

另外：帮助中新增说明，解释闹钟为何有时会响起但不全屏显示。
```

**Translations were written by Claude.** RU/BY/UK/PL should get a native read before
publishing — the phrasing around "snoozes itself" is the part most likely to land oddly.
