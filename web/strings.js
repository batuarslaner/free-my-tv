/*
 * On-screen text in Portuguese, English, Spanish, German, Russian and Turkish (the same copy as the Android app).
 * tr(pt, en, es): the text in the current language. The language follows the TV unless set in Home Settings.
 */
var I18N = (function () {
  'use strict';

  var lang = 'en';
  var german = {
    "Live TV": "Live-TV",
    "All Apps": "Alle Apps",
    "Settings": "Einstellungen",
    "Home Settings": "Startbildschirm",
    "TV Settings": "TV-Einstellungen",
    "Inputs": "Eingänge",
    "Shortcuts": "Verknüpfungen",
    "Weather": "Wetter",
    "No weather data right now.": "Gerade keine Wetterdaten.",
    "Sunrise ": "Sonnenaufgang ",
    "Sunset ": "Sonnenuntergang ",
    "Crypto": "Krypto",
    "24h change": "Änderung in 24 h",
    "For You": "Für dich",
    "Couldn't open ": "Konnte nicht öffnen: ",
    "Couldn't open TV Settings": "Die TV-Einstellungen ließen sich nicht öffnen",
    "Dollar": "Dollar",
    "Series": "Serie",
    "Language": "Sprache",
    "Temperature": "Temperatur",
    "Theme": "Design",
    "Automatic": "Automatisch",
    "Light": "Hell",
    "Dark": "Dunkel",
    "Look": "Darstellung",
    "Glass": "Glas",
    "Sharp": "Scharf",
    "Text size": "Textgröße",
    "Normal": "Normal",
    "Large": "Groß",
    "Weather widget": "Wetter-Widget",
    "Quotes widget": "Kurs-Widget",
    "On": "An",
    "Off": "Aus",
    "Shelf": "Leiste",
    "On · ": "In der Leiste · ",
    "OK or the arrows change the value.": "OK oder die Pfeiltasten ändern den Wert.",
    "OK adds or removes · ← → change the order": "OK fügt hinzu oder entfernt · ← → verschieben",
    "Explore": "Entdecken",
    "Movies": "Filme",
    "Shows": "Serien",
    "Clear": "Klar",
    "Mostly Sunny": "Überwiegend sonnig",
    "Mostly Clear": "Überwiegend klar",
    "Partly Cloudy": "Teilweise bewölkt",
    "Cloudy": "Bewölkt",
    "Fog": "Nebel",
    "Drizzle": "Nieselregen",
    "Heavy Rain": "Starker Regen",
    "Snow": "Schnee",
    "Thunderstorms": "Gewitter",
    "Rain": "Regen"
  };

  /** German by the English; text built at runtime by its English pattern. */
  function germanText(en) {
    if (Object.prototype.hasOwnProperty.call(german, en)) return german[en];
    var m = /^(\d+) (min|h) ago$/.exec(en);
    if (m) return 'vor ' + m[1] + (m[2] === 'min' ? ' Min.' : ' Std.');
    m = /^(\d+) seasons?$/.exec(en);
    if (m) return m[1] + (m[1] === '1' ? ' Staffel' : ' Staffeln');
    m = /^H:(\S+)(\s+)L:(\S+)$/.exec(en);
    if (m) return 'H:' + m[1] + m[2] + 'T:' + m[3];
    m = /^The shelf holds up to (\d+) apps\.$/.exec(en);
    if (m) return 'In die Leiste passen bis zu ' + m[1] + ' Apps.';
    return en;
  }

  var russian = {
    "Live TV": "Телеканалы",
    "All Apps": "Приложения",
    "Settings": "Настройки",
    "Home Settings": "Главный экран",
    "TV Settings": "Настройки ТВ",
    "Inputs": "Входы",
    "Shortcuts": "Ярлыки",
    "Weather": "Погода",
    "No weather data right now.": "Сейчас нет данных о погоде.",
    "Sunrise ": "Восход ",
    "Sunset ": "Закат ",
    "Crypto": "Криптовалюты",
    "24h change": "изменение за 24 ч",
    "For You": "Для вас",
    "Couldn't open ": "Не удалось открыть ",
    "Dollar": "Доллар",
    "Series": "Сериал",
    "now": "сейчас",
    "Language": "Язык",
    "Temperature": "Температура",
    "Theme": "Тема",
    "Automatic": "Автоматически",
    "Light": "Светлая",
    "Dark": "Тёмная",
    "Look": "Вид",
    "Glass": "Стекло",
    "Sharp": "Чёткий",
    "Text size": "Размер текста",
    "Normal": "Обычный",
    "Large": "Крупный",
    "Weather widget": "Виджет погоды",
    "Quotes widget": "Виджет котировок",
    "On": "Вкл.",
    "Off": "Выкл.",
    "Shelf": "Избранные приложения",
    "OK or the arrows change the value.": "Нажмите OK или стрелки, чтобы изменить значение.",
    "Explore": "Обзор",
    "Movies": "Фильмы",
    "Shows": "Сериалы",
    "Clear": "Ясно",
    "Mostly Sunny": "Малооблачно",
    "Mostly Clear": "Малооблачно",
    "Partly Cloudy": "Переменная облачность",
    "Cloudy": "Облачно",
    "Fog": "Туман",
    "Drizzle": "Морось",
    "Heavy Rain": "Сильный дождь",
    "Snow": "Снег",
    "Thunderstorms": "Гроза",
    "Rain": "Дождь",
    "On · ": "В избранном · ",
    "OK adds or removes · ← → change the order": "OK — добавить или убрать · ← → — изменить порядок",
    "Couldn't open TV Settings": "Не удалось открыть настройки телевизора",
    "Action & Adventure": "Боевик и приключения",
    "Sci-Fi & Fantasy": "Фантастика и фэнтези",
    "War & Politics": "Война и политика",
    "Kids": "Для детей",
    "News": "Новости",
    "Soap": "Мыльная опера",
    "Talk": "Ток-шоу",
    "": ""
  };

  function russianText(en) {
    if (Object.prototype.hasOwnProperty.call(russian, en)) return russian[en];
    var m = /^(\d+) (min|h) ago$/.exec(en);
    if (m) return m[1] + (m[2] === 'min' ? ' мин назад' : ' ч назад');
    m = /^The shelf holds up to (\d+) apps\.$/.exec(en);
    if (m) return 'В избранное можно добавить не более ' + m[1] + ' приложений.';
    return en;
  }

  var turkish = {
    "Live TV": "Canlı TV",
    "All Apps": "Tüm Uygulamalar",
    "Settings": "Ayarlar",
    "Home Settings": "Ana Ekran Ayarları",
    "TV Settings": "TV Ayarları",
    "Inputs": "Girişler",
    "Shortcuts": "Kısayollar",
    "Weather": "Hava Durumu",
    "No weather data right now.": "Şu anda hava durumu verisi yok.",
    "Sunrise ": "Gün doğumu ",
    "Sunset ": "Gün batımı ",
    "Crypto": "Kripto",
    "24h change": "24 saatlik değişim",
    "For You": "Senin İçin",
    "Couldn't open ": "Açılamadı: ",
    "Dollar": "Dolar",
    "Series": "Dizi",
    "now": "şimdi",
    "Language": "Dil",
    "Temperature": "Sıcaklık",
    "Theme": "Tema",
    "Automatic": "Otomatik",
    "Light": "Açık renk",
    "Dark": "Koyu",
    "Look": "Görünüm",
    "Glass": "Cam",
    "Sharp": "Keskin",
    "Text size": "Yazı boyutu",
    "Normal": "Normal",
    "Large": "Büyük",
    "Weather widget": "Hava durumu widget'ı",
    "Quotes widget": "Fiyat widget'ı",
    "On": "Açık",
    "Off": "Kapalı",
    "Shelf": "Raf",
    "OK or the arrows change the value.": "OK veya oklar değeri değiştirir.",
    "Explore": "Keşfet",
    "Movies": "Filmler",
    "Shows": "Diziler",
    "Clear": "Açık",
    "Mostly Sunny": "Çoğunlukla güneşli",
    "Mostly Clear": "Çoğunlukla açık",
    "Partly Cloudy": "Parçalı bulutlu",
    "Cloudy": "Bulutlu",
    "Fog": "Sisli",
    "Drizzle": "Çiseleme",
    "Heavy Rain": "Kuvvetli yağmur",
    "Snow": "Kar",
    "Thunderstorms": "Gök gürültülü fırtına",
    "Rain": "Yağmur",
    "On · ": "Rafta · ",
    "OK adds or removes · ← → change the order": "OK ekler veya çıkarır · ← → sırayı değiştirir",
    "Couldn't open TV Settings": "TV Ayarları açılamadı",
    "": ""
  };

  /** Turkish by the English; text built at runtime by its English pattern (no plural after a number). */
  function turkishText(en) {
    if (Object.prototype.hasOwnProperty.call(turkish, en)) return turkish[en];
    var m = /^(\d+) (min|h) ago$/.exec(en);
    if (m) return m[1] + (m[2] === 'min' ? ' dk önce' : ' sa önce');
    m = /^(\d+) seasons?$/.exec(en);
    if (m) return m[1] + ' sezon';
    m = /^H:(\S+)(\s+)L:(\S+)$/.exec(en);
    if (m) return 'Y:' + m[1] + m[2] + 'D:' + m[3];
    m = /^The shelf holds up to (\d+) apps\.$/.exec(en);
    if (m) return 'Rafa en fazla ' + m[1] + ' uygulama sığar.';
    return en;
  }

  function detect(setting) {
    var v = (setting || 'auto').toLowerCase();
    if (v === 'auto') v = (navigator.language || 'en').toLowerCase();
    return v.indexOf('ru') === 0 ? 'ru' : v.indexOf('de') === 0 ? 'de' : v.indexOf('tr') === 0 ? 'tr' : v.indexOf('pt') === 0 ? 'pt' : v.indexOf('es') === 0 ? 'es' : 'en';
  }

  return {
    set: function (setting) { lang = detect(setting); document.documentElement.lang = lang === 'pt' ? 'pt-BR' : lang; },
    count: function (n, one, few, many) {
      var last = Math.abs(n % 100);
      return n + ' ' + (last >= 11 && last <= 14 ? many : last % 10 === 1 ? one : last % 10 >= 2 && last % 10 <= 4 ? few : many);
    },
    get lang() { return lang; },
    tr: function (pt, en, es) { return lang === 'ru' ? russianText(en) : lang === 'de' ? germanText(en) : lang === 'tr' ? turkishText(en) : lang === 'es' ? (es || en) : lang === 'en' ? en : pt; },
    /** BCP 47 tag for dates, numbers and TMDB. */
    locale: function () { return lang === 'ru' ? 'ru-RU' : lang === 'de' ? 'de-DE' : lang === 'tr' ? 'tr-TR' : lang === 'es' ? 'es-MX' : lang === 'en' ? 'en-US' : 'pt-BR'; }
  };
})();

function tr(pt, en, es) {
  return I18N.tr(pt, en, es);
}
