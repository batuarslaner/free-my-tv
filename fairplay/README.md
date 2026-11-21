# Free My TV FairPlay plugin

AirPlay screen mirroring from an iPhone or iPad needs FairPlay, Apple's key exchange: without it the TV can't unwrap
the key of the video stream. Free My TV's own AirPlay receiver (`app/src/io/freemytv/airplay/`, MIT like the rest of
the app) does everything else; this plugin does only FairPlay.

It is a separate app, `FreeMyTV-FairPlay.apk`, under the **GNU GPL version 3** (see `LICENSE`), because the only
public implementation of FairPlay is GPL-3.0. Keeping it apart keeps Free My TV MIT: the app talks to the plugin over
Android's Binder, and none of this code is in the app. Without the plugin, Free My TV works as before, without AirPlay.

## Build and install

```bash
./build.sh               # the app first: the plugin is signed with the same key (freemytv.keystore)
./build.sh --fairplay    # → FreeMyTV-FairPlay.apk
adb install -r FreeMyTV-FairPlay.apk
```

It has no screen and no icon. Only an app signed with the same key can use it: its service is guarded by a
signature permission (`io.freemytv.permission.FAIRPLAY`), and every call checks the caller's signature again.

`./build.sh --selftest` runs the known-answer tests in `test/` (with the receiver's own tests).

## Credits

- **PlayFair** by EstebanKubata, <https://github.com/EstebanKubata/playfair> (GPL-3.0): the reverse-engineered
  FairPlay (`omg_hax.c`, `sap_hash.c`, `hand_garble.c`, `modified_md5.c` and the tables in `omg_hax.h`).
- **java-airplay** by serezhka, <https://github.com/serezhka/java-airplay>: the Java port of PlayFair this plugin
  started from, and its test vectors (`test/FairPlayVectors.java`, `test/encrypted_payload`).

The port here (`src/io/freemytv/fairplay/`): `OmgHax`, `SapHash`, `HandGarble` and `ModifiedMd5` follow PlayFair's
files of the same names, `Tables` holds PlayFair's constants and tables (Base64), `FairPlay` answers `/fp-setup` and
unwraps the key, `FairPlayService` is the Binder service the app calls.

## Source

This directory is the complete source of `FreeMyTV-FairPlay.apk`; `build.sh --fairplay` builds it with the Android
SDK alone (no Gradle, no libraries).
