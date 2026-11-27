# aff-android

App Android untuk **Tracker Sharelink**, pelacak performa sharelink affiliate Shopee. App ini membungkus web [`aff`](https://github.com/RIFQIBOTZ/aff) di WebView dan menambah **gelembung melayang**, sehingga sharelink bisa dicatat dengan sekali tap tanpa meninggalkan Facebook atau app lain.

Web (repo `aff`) tetap menjadi satu-satunya sumber logika: timer, data sesi, impor laporan Shopee, dan analisis. App ini hanya menyediakan layar WebView dan gelembung yang memanggil fungsi di web.

## Fitur

- **Layar utama**: tracker lengkap (Sesi, Riwayat, Ringkasan, Analisis, Setelan) dimuat dari `https://rifqibotz.github.io/aff/`. Perubahan di web langsung ikut tanpa build ulang APK.
- **Gelembung melayang** di atas app lain, dengan panel mini berisi:
  - timer sesi dan status
  - Mulai / Jeda / Lanjut
  - Selesai (dua tap: tap pertama berubah menjadi "Yakin?")
  - empat tombol metode: Manual, GPT, Claude, Koleksi (aktif hanya saat timer jalan)
  - Undo tap terakhir (menampilkan metode yang akan dibatalkan)
  - Visit web (membuka layar utama)
- **Dua mode gelembung**: samar (setengah keluar di tepi layar, redup) dan nonjol (penuh dan jelas). Sentuhan di luar gelembung otomatis menutup panel atau membuatnya samar lagi.
- **Impor CSV Shopee** dengan pemilih file yang mendukung pilih-banyak.
- **Ekspor JSON ke folder Download** (unduhan blob biasa tidak jalan di WebView, jadi disediakan lewat bridge).
- **Cek pembaruan** di tab Setelan: memuat ulang versi web terbaru tanpa menghapus data.
- **Update APK menimpa versi lama tanpa uninstall** (kunci tanda tangan tetap).

## Cara kerja

```
 MainActivity ──► WebView layar penuh ──► https://rifqibotz.github.io/aff/
      │                                        ▲
      │ JS bridge: AndroidBridge               │ window.trackerAPI.*
      ▼                                        │
 BubbleService ──► WebView "engine" 1x1 tak terlihat (halaman web yang sama)
      │
      └──► bubble + panel (View native di jendela overlay)
```

- `BubbleService` menjalankan satu WebView kecil tak terlihat yang memuat web yang sama. Tombol di panel memanggil `window.trackerAPI` lewat `evaluateJavascript`, dan status dibaca tiap 1 detik. **Tidak ada logika timer di Kotlin.**
- Data (sesi, laporan) tersimpan di `localStorage` WebView milik app ini. Data ini **terpisah dari Chrome**. Pindahkan lewat Ekspor/Impor JSON.
- Layar utama dan engine memakai penyimpanan yang sama. Web membaca ulang `localStorage` sebelum tiap aksi, jadi keduanya tidak saling menimpa.
- Saat layar utama terbuka, gelembung disembunyikan. Saat ditinggalkan, gelembung muncul dalam mode samar dan engine dimuat ulang.

## Persyaratan

- Android 10 atau lebih baru (`minSdk 29`), target dan compile SDK 34.
- Izin **Tampil di atas aplikasi lain** (untuk gelembung) dan izin notifikasi (Android 13 ke atas, untuk notifikasi layanan latar depan).
- Koneksi internet saat pertama kali memuat web. Setelah itu service worker di web menyimpan cadangan untuk offline.

## Struktur repo

```
aff-android/
├── .github/workflows/build-apk.yml      # build otomatis di GitHub Actions
├── app/
│   ├── debug.keystore                   # kunci tanda tangan tetap (lihat bagian Keamanan)
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/id/rifqibotz/sharelink/
│       │   ├── MainActivity.kt          # WebView layar penuh, JS bridge, pemilih file
│       │   └── BubbleService.kt         # gelembung, panel, engine WebView
│       └── res/mipmap-xxxhdpi/ic_launcher.png
├── build.gradle.kts
├── settings.gradle.kts
└── gradle.properties
```

Tidak memakai library eksternal (hanya framework Android dan Kotlin standar) dan tidak menyertakan Gradle wrapper.

## Build dan instal

### Lewat GitHub Actions (cara yang dipakai)
1. Push ke branch `main` (atau jalankan workflow **Build APK** manual dari tab Actions).
2. Setelah centang hijau, buka run-nya dan unduh artifact **sharelink-apk** (zip berisi `app-debug.apk`).
3. Ekstrak lalu instal. Izinkan instal dari sumber tidak dikenal bila diminta. Play Protect mungkin memberi peringatan karena APK bukan dari Play Store.

Workflow memakai Java 17, Gradle 8.9 (lewat `gradle/actions/setup-gradle`), dan menjalankan `gradle assembleDebug -PappVersionCode=<nomor run>`.

### Lokal (belum dicoba)
Buka folder ini di Android Studio dan arahkan ke Gradle 8.9, atau jalankan `gradle assembleDebug` bila Gradle 8.9 terpasang. Hasil di `app/build/outputs/apk/debug/app-debug.apk`.

### Update tanpa uninstall
APK baru langsung menimpa versi lama dan data tetap ada, **selama** `applicationId` (`id.rifqibotz.sharelink`) dan `app/debug.keystore` tidak berubah. `versionCode` naik otomatis mengikuti nomor run GitHub Actions. Jangan ganti kunci, kalau tidak Android menolak update dan data harus dikosongkan lewat uninstall.

Catatan: kalau repo dibuat ulang, nomor run mulai dari awal dan `versionCode` bisa lebih rendah dari yang terpasang. Android menolak penurunan versi.

## Pengaturan di HP

1. Buka app, lalu Setelan, lalu **Aktifkan gelembung**. Layar izin akan terbuka. Aktifkan **Tampil di atas aplikasi lain** untuk app Sharelink, kembali ke app, lalu tekan tombolnya lagi.
2. Izinkan notifikasi.
3. Agar gelembung tidak dimatikan sistem, di pengaturan HP: matikan optimasi baterai untuk app ini, aktifkan autostart (bila ada, misalnya di XOS), dan kunci app di recent apps.

## Memakai gelembung

| Aksi | Hasil |
|---|---|
| Tap gelembung samar | menjadi nonjol |
| Tap gelembung nonjol | panel terbuka |
| Tap gelembung saat panel terbuka | panel menutup |
| Tap di luar panel | panel menutup (gelembung tetap nonjol) |
| Tap di luar gelembung yang nonjol | kembali samar |
| Seret gelembung | pindah posisi, menempel ke tepi terdekat |
| Diam | nonjol menjadi samar setelah 3,5 detik; panel menutup setelah 7 detik tanpa sentuhan |

Cincin gelembung: lime = timer jalan, amber = dijeda, abu = belum jalan. Angka di tengah = total sharelink sesi ini. Tiap tap metode memberi getar singkat dan cincin berkedip warna metodenya.

Matikan gelembung dari Setelan di web atau lewat aksi **Matikan** di notifikasi.

## Kontrak dengan web

### `window.AndroidBridge` (disediakan `MainActivity`, hanya ada di layar utama)
| Metode | Fungsi |
|---|---|
| `isBubbleEnabled()` | `true` bila service gelembung sedang berjalan |
| `hasOverlayPermission()` | status izin tampil di atas app lain |
| `setBubble(on)` | menyalakan/mematikan gelembung; mengembalikan `"on"`, `"off"`, atau `"need_permission"` (sekaligus membuka layar izin) |
| `saveFile(name, content)` | menyimpan teks ke folder Download; mengembalikan `true/false` |
| `refreshApp()` | menghapus cache HTTP WebView, memuat ulang engine dan layar utama; `localStorage` tidak disentuh |

### `window.trackerAPI` (didefinisikan `script.js` di web, dipanggil gelembung)
| Fungsi | Hasil |
|---|---|
| `state()` | `{active, running, elapsed, counts:{manual,gpt,claude,koleksi}, total, last}` |
| `main()` | mulai / jeda / lanjut sesuai status saat ini, lalu mengembalikan `state()` |
| `tap(metode)` | +1 sharelink pada metode (`manual`, `gpt`, `claude`, `koleksi`) |
| `undo()` | membatalkan tap terakhir |
| `stopNow()` | menyelesaikan sesi tanpa dialog konfirmasi (konfirmasi dua-tap ada di panel) |

Kalau metode baru ditambahkan di web, tambahkan juga ke daftar `METHODS` di `BubbleService.kt` supaya muncul di panel.

## Konfigurasi

Di kode (tanpa file pengaturan):
- `APP_URL` (`BubbleService.kt`): alamat web yang dimuat.
- `PANEL_DP` (`BubbleService.kt`): lebar panel (272 dp).
- Waktu otomatis samar (3,5 detik) dan tutup panel (7 detik): fungsi `scheduleHide()`.
- Posisi dan status gelembung disimpan di SharedPreferences `bubble` (`enabled`, `right`, `y`).

## Pemecahan masalah

- **Gelembung tidak muncul**: pastikan izin "Tampil di atas aplikasi lain" aktif, lalu nyalakan lagi dari Setelan. Service berhenti sendiri bila izin hilang.
- **Gelembung hilang setelah lama di latar belakang**: kemungkinan dimatikan penghemat baterai. Atur optimasi baterai dan autostart (lihat Pengaturan di HP).
- **Gelembung menampilkan "…" atau angka tidak berubah**: halaman web belum selesai dimuat di engine atau `trackerAPI` belum tersedia. Buka app sebentar, atau tekan Cek pembaruan di Setelan.
- **Perubahan web tidak terlihat**: tunggu GitHub Pages selesai deploy (biasanya 1 sampai 2 menit), lalu Setelan, **Cek pembaruan**. Versi web yang sedang dimuat tertulis di kotak itu.
- **File CSV tidak bisa dipilih atau hanya satu yang terpilih**: di pemilih file Android, tekan lama satu file lalu tap file kedua untuk memilih beberapa sekaligus.
- **Update APK ditolak ("bentrok dengan paket yang sudah ada")**: kunci tanda tangan APK baru berbeda dari yang terpasang. Uninstall satu kali (Ekspor JSON dulu), lalu instal. Setelah itu update berikutnya langsung menimpa.

## Batasan

- Tidak bisa dibuild atau diuji di lingkungan tanpa akses Android SDK. Perilaku di perangkat (terutama gelembung di berbagai versi Android dan OEM) bisa memerlukan penyesuaian.
- WebView engine tersembunyi bergantung pada Android tidak membekukan WebView saat app di latar belakang. Bila ini terjadi di perangkat tertentu, logika waktu perlu dipindah ke Kotlin.
- Data app terpisah dari Chrome dan tidak tersinkron.

## Keamanan

- `app/debug.keystore` adalah **kunci debug standar** (alias `androiddebugkey`, password `android`) yang sengaja dikomit supaya update APK bisa menimpa versi lama. Siapa pun bisa menandatangani APK dengan kunci yang sama, jadi **jangan dipakai untuk rilis publik atau Play Store**. Untuk rilis resmi, buat kunci sendiri dan simpan di GitHub Secrets.
- WebView hanya mengizinkan navigasi ke host `rifqibotz.github.io`. Link lain dibuka di browser. JS bridge hanya terpasang di layar utama, tidak di engine.
- Tidak ada data yang dikirim ke server mana pun oleh app ini. Semua tersimpan lokal di perangkat.
