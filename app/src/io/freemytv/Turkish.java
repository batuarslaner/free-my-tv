package io.freemytv;

/** Turkish copy for text drawn directly by the custom UI. Source keys are the existing English copy. */
final class Turkish {
    private Turkish() {}

    static String text(String english) {
        switch (english) {
            // AirPlay
            case "AirPlay name": return "AirPlay adı";
            case "AirPlay code": return "AirPlay kodu";
            case "First time": return "İlk seferde";
            case "Always": return "Her zaman";
            case "Needs the FairPlay plugin": return "FairPlay eklentisi gerekli";
            case "Paused after errors": return "Hatalardan sonra duraklatıldı";
            case "Empty: the TV's name": return "Boş: TV'nin adı";
            case "Save": return "Kaydet";
            case "Type this code on your iPhone or iPad": return "Bu kodu iPhone veya iPad'e girin";
            case "Press Back again to stop AirPlay": return "AirPlay'i durdurmak için Geri'ye tekrar basın";
            case "AirPlay crashed 3 times in a row and is paused; turn Free My TV on again to retry.": return "AirPlay arka arkaya 3 kez çöktü ve duraklatıldı; yeniden denemek için Free My TV'yi tekrar açın.";
            case "Live TV": return "Canlı TV";
            case "All Apps": return "Tüm Uygulamalar";
            case "Settings": return "Ayarlar";
            case "Home Settings": return "Ana Ekran Ayarları";
            case "TV Settings": return "TV Ayarları";
            case "Inputs": return "Girişler";
            case "Shortcuts": return "Kısayollar";
            case "Weather": return "Hava Durumu";
            case "No weather data right now.": return "Şu anda hava durumu verisi yok.";
            case "Sunrise ": return "Gün doğumu ";
            case "Sunset ": return "Gün batımı ";
            case "Crypto": return "Kripto";
            case "24h change": return "24 saatlik değişim";
            case "For You": return "Senin İçin";
            case "Couldn't open ": return "Açılamadı: ";
            // "3 of 8" reads "3, toplam 8"
            case " of ": return ", toplam ";
            case "← → to move · OK to drop · Back to cancel": return "← → taşı · OK bırak · Geri iptal";
            case "Moving ": return "Taşınıyor: ";
            case ", position ": return ", sıra ";
            case ", tab ": return ", sekme ";
            case " of 3": return ", toplam 3";
            case "Dollar": return "Dolar";
            case "Series": return "Dizi";
            case "prices from ": return "fiyatlar: ";
            case "Starting…": return "Başlatılıyor…";
            case "Original": return "Orijinal";
            case "Home Screen, ": return "Ana Ekran, ";
            case "Screensaver, preview": return "Ekran Koruyucu, önizleme";
            case "no ads, with the new screensaver": return "reklamsız, yeni ekran koruyucuyla";
            case "the TV's factory home screen": return "TV'nin fabrika ana ekranı";
            case ". In use": return ". Kullanımda";
            case " of 2": return ", toplam 2";
            case "Your TV's home screen and screensaver": return "TV'nin ana ekranı ve ekran koruyucusu";
            case "Home Screen": return "Ana Ekran";
            case "Screensaver": return "Ekran Koruyucu";
            case "Preview": return "Önizle";
            case "On the TV: tick “Always allow” and press OK.": return "TV'de “Her zaman izin ver”i işaretleyip OK'e basın.";
            case "RECOMMENDATIONS": return "ÖNERİLER";
            case "No TMDB key: see the README (config.properties).": return "TMDB anahtarı yok: README'ye bakın (config.properties).";
            case "Error: ": return "Hata: ";
            case "Fetching titles…": return "İçerikler yükleniyor…";
            case "This product uses the TMDB API but is not endorsed or certified by TMDB.": return "Bu ürün TMDB API'sini kullanır, ancak TMDB tarafından onaylanmış veya sertifikalandırılmış değildir.";
            case "COMPARISON": return "KARŞILAŞTIRMA";
            case "Home button": return "Ana ekran tuşu";
            case "Free memory": return "Boş bellek";
            case "Home screen memory": return "Ana ekran belleği";
            case "NOTICES": return "BİLDİRİMLER";
            case "None yet.": return "Henüz yok.";
            case "No ads, with the new screensaver": return "Reklamsız, yeni ekran koruyucuyla";
            case "The TV's factory home screen": return "TV'nin fabrika ana ekranı";
            case "Switching takes a few seconds; the screen blinks while it measures.": return "Geçiş birkaç saniye sürer; ölçüm sırasında ekran yanıp söner.";
            case "now": return "şimdi";
            case "Measuring the original home screen…": return "Orijinal ana ekran ölçülüyor…";
            case "Switching the home screen and screensaver…": return "Ana ekran ve ekran koruyucu değiştiriliyor…";
            case "Android didn't accept the new home screen": return "Android yeni ana ekranı kabul etmedi";
            case "Turning off apps (": return "Uygulamalar kapatılıyor (";
            case "Measuring Free My TV…": return "Free My TV ölçülüyor…";
            case "Couldn't turn on Free My TV: ": return "Free My TV açılamadı: ";
            case "Turning the apps back on…": return "Uygulamalar yeniden açılıyor…";
            case "Turning apps back on (": return "Uygulamalar yeniden açılıyor (";
            case "Restoring the original home screen…": return "Orijinal ana ekran geri yükleniyor…";
            case "Original mode: the factory home screen, screensaver and apps are back.": return "Orijinal mod: fabrika ana ekranı, ekran koruyucu ve uygulamalar geri geldi.";
            case "the original home screen": return "orijinal ana ekran";
            case "Original mode, but I couldn't turn back on: ": return "Orijinal mod, ancak şunlar yeniden açılamadı: ";
            case "Couldn't go back to the original: ": return "Orijinale dönülemedi: ";
            case "Free My TV's home screen crashed 3 times in a row; I switched back to the original one.": return "Free My TV ana ekranı art arda 3 kez çöktü; orijinal ana ekrana dönüldü.";
            case "the home screen": return "ana ekran";
            case "the screensaver": return "ekran koruyucu";
            case "Couldn't check Free My TV: ": return "Free My TV denetlenemedi: ";
            case "The TV update turned back on ": return "TV güncellemesi şunları yeniden açtı: ";
            case "Turned off what was missing: ": return "Eksik kalanlar kapatıldı: ";
            case "The TV was updated; Free My TV is still on.": return "TV güncellendi; Free My TV hâlâ açık.";
            case "Connecting…": return "Bağlanıyor…";
            case "Accept “Allow USB debugging?” on the TV (tick “Always allow”).": return "TV'de “USB hata ayıklamaya izin verilsin mi?” sorusunu onaylayın (“Her zaman izin ver”i işaretleyin).";
            case "Free My TV's home screen ran into a problem.\nGoing back to the TV's original home screen…": return "Free My TV ana ekranında bir sorun oluştu.\nTV'nin orijinal ana ekranına dönülüyor…";
            case "the TV's first setup saved the Wi-Fi, and turning it off would erase those networks. "
                    + "In Settings → Network & Internet, forget the network and connect again (networks out of range don't "
                    + "show there: Claude Code can remove them from your computer), then try once more.":
                return "Wi-Fi ağlarını TV'nin ilk kurulumu kaydetti ve onu kapatmak bu ağları siler. "
                        + "Ayarlar → Ağ ve İnternet'te ağı unutup yeniden bağlanın (kapsama dışındaki ağlar orada "
                        + "görünmez: Claude Code bunları bilgisayardan kaldırabilir), sonra yeniden deneyin.";
            // Home Settings, Shelf and Remote Buttons
            case "Language": return "Dil";
            case "Temperature": return "Sıcaklık";
            case "Theme": return "Tema";
            case "Power on": return "Açılışta";
            case "Automatic": return "Otomatik";
            case "Light": return "Açık renk";
            case "Dark": return "Koyu";
            case "Look": return "Görünüm";
            case "Glass": return "Cam";
            case "Sharp": return "Keskin";
            case "Text size": return "Yazı boyutu";
            case "Normal": return "Normal";
            case "Large": return "Büyük";
            case "Weather widget": return "Hava durumu widget'ı";
            // What TalkBack reads on the drawer's weather card (#47)
            case ". High ": return ". En yüksek ";
            case ", low ": return ", en düşük ";
            case " Next hours: ": return " Sonraki saatler: ";
            case " Sunrise ": return " Gün doğumu ";
            case " Sunset ": return " Gün batımı ";
            case "City": return "Şehir";
            case "Searching…": return "Aranıyor…";
            case "Type the city's name": return "Şehrin adını yazın";
            case "Search": return "Ara";
            case "Cancel": return "İptal";
            case "No city with that name.": return "Bu adda bir şehir yok.";
            case "Couldn't search now: ": return "Şu anda aranamadı: ";
            case "Quotes widget": return "Fiyat widget'ı";
            case "On": return "Açık";
            case "Off": return "Kapalı";
            case "Shelf": return "Raf";
            case "on the shelf, place ": return "rafta, sıra ";
            case "not on the shelf": return "rafta değil";
            case "Remote Buttons": return "Kumanda Tuşları";
            case "Netflix button": return "Netflix tuşu";
            case "YouTube button": return "YouTube tuşu";
            case "Prime Video button": return "Prime Video tuşu";
            case "TCL Channel button": return "TCL Channel tuşu";
            case "Tabii button": return "Tabii tuşu";
            case "Netflix, YouTube and Prime Video: the TV itself controls those buttons.": return "Netflix, YouTube ve Prime Video: bu tuşları TV'nin kendisi yönetir.";
            case "Nothing": return "Hiçbiri";
            case "Not installed": return "Yüklü değil";
            case ", selected": return ", seçili";
            case "OK or the arrows change the value.": return "OK veya oklar değeri değiştirir.";
            case "OK picks, Back cancels.": return "OK seçer, Geri iptal eder.";
            case "Disney and Globoplay: the TV itself controls those buttons.": return "Disney ve Globoplay: bu tuşları TV'nin kendisi yönetir.";
            case "YouTube, Prime Video and Google Play: the TV itself controls those buttons.": return "YouTube, Prime Video ve Google Play: bu tuşları TV'nin kendisi yönetir.";
            case "These are the TCL remote's buttons: on this TV, the buttons work as they always did.": return "Bunlar TCL kumandasının tuşları: bu TV'de tuşlar her zamanki gibi çalışır.";
            case "Explore": return "Keşfet";
            case "Movies": return "Filmler";
            case "Shows": return "Diziler";
            case "Clear": return "Açık";
            case "Mostly Sunny": return "Çoğunlukla güneşli";
            case "Mostly Clear": return "Çoğunlukla açık";
            case "Partly Cloudy": return "Parçalı bulutlu";
            case "Cloudy": return "Bulutlu";
            case "Fog": return "Sisli";
            case "Drizzle": return "Çiseleme";
            case "Heavy Rain": return "Kuvvetli yağmur";
            case "Snow": return "Kar";
            case "Thunderstorms": return "Gök gürültülü fırtına";
            case "Rain": return "Yağmur";
            case "On · ": return "Rafta · ";
            case "OK adds or removes · ← → change the order": return "OK ekler veya çıkarır · ← → sırayı değiştirir";
            case "Couldn't open TV Settings": return "TV Ayarları açılamadı";
            case "THIS MORNING": return "BU SABAH";
            case "THIS AFTERNOON": return "BU ÖĞLEDEN SONRA";
            case "TONIGHT": return "BU AKŞAM";
            // TV genres that TMDB may still return in English
            case "Action & Adventure": return "Aksiyon ve Macera";
            case "Sci-Fi & Fantasy": return "Bilim Kurgu ve Fantastik";
            case "War & Politics": return "Savaş ve Politika";
            case "Kids": return "Çocuk";
            case "News": return "Haber";
            case "Soap": return "Pembe Dizi";
            case "Talk": return "Talk Show";
            case "": return "";
            // Adb's errors, shown after "Couldn't turn on Free My TV: "
            case "debugging is off on the TV (port 5555 closed)": return "TV'de hata ayıklama kapalı (5555 bağlantı noktası kapalı)";
            case "debugging permission wasn't accepted on the TV": return "TV'de hata ayıklama izni verilmedi";
            case "the TV's ADB didn't respond": return "TV'nin ADB'si yanıt vermedi";
            case "the TV asked for TLS (wireless debugging); use USB debugging": return "TV TLS istedi (kablosuz hata ayıklama); USB hata ayıklamayı kullanın";
            case "unexpected reply from ADB": return "ADB'den beklenmeyen yanıt";
            case "the TV rejected the app's key": return "TV uygulamanın anahtarını reddetti";
            case "ADB refused the command": return "ADB komutu reddetti";
            case "invalid ADB message": return "geçersiz ADB mesajı";
            case "ADB message too large": return "ADB mesajı çok büyük";
            default:
                // Notices are saved in the language in use when they were generated.
                String[] prefixes = {"Couldn't check Free My TV: ", "Couldn't turn on Free My TV: ",
                        "Couldn't go back to the original: ", "Original mode, but I couldn't turn back on: "};
                for (String p : prefixes) if (english.startsWith(p)) return text(p) + text(english.substring(p.length()));
                String shelfPrefix = "OK adds or removes (up to ";
                String shelfSuffix = "). ← → change the order.";
                if (english.startsWith(shelfPrefix) && english.endsWith(shelfSuffix)) {
                    String n = english.substring(shelfPrefix.length(), english.length() - shelfSuffix.length());
                    return "OK ekler veya çıkarır (en fazla " + n + "). ← → sırayı değiştirir.";
                }
                String prefix = "Free My TV is on: new home screen and screensaver, ";
                if (english.startsWith(prefix) && english.endsWith(" apps turned off.")) {
                    String n = english.substring(prefix.length(), english.length() - " apps turned off.".length());
                    return "Free My TV açık: yeni ana ekran ve ekran koruyucu, " + n + " uygulama kapatıldı.";
                }
                if (english.startsWith("On · ") && english.endsWith(" titles from the TV's apps, 3 new ones every hour.")) {
                    String n = english.substring(5, english.length() - " titles from the TV's apps, 3 new ones every hour.".length());
                    return "Açık · TV'deki uygulamalardan " + n + " içerik, her saat 3 yeni.";
                }
                // Turkish keeps the noun singular after a number: "3 gün önce"
                if (english.endsWith(" min ago")) return english.substring(0, english.length() - 8) + " dk önce";
                if (english.endsWith(" h ago")) return english.substring(0, english.length() - 6) + " sa önce";
                if (english.endsWith(" days ago")) return english.substring(0, english.length() - 9) + " gün önce";
                return english;
        }
    }
}
