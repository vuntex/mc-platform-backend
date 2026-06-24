# MineChaos-PvP — Feature-Inventar (Bestandsaufnahme)

> **Zweck:** Reine Bestandsaufnahme des alten 1.8.9-Monolithen (`de.truhera.minechaos.pvp`) als
> Grundlage für die Migration nach **Spring-Boot-Backend (Source of Truth) + Paper-1.21-Client
> (REST + Redis-Pub/Sub) + Inventar-Menü-Framework**.
> **Kein Code, keine Migration, keine Architektur-Entscheidung.** Dies ist die Diskussionsgrundlage
> für das Review. Offene Fragen am Ende.

## Tech-Kontext (Ist-Zustand)

- **Code-Größe:** ~823 Java-Dateien, ~40 `*Manager`-Klassen, ~135 Command-Klassen, tiefer
  `others/`-Feature-Baum. Gut strukturiert (Manager + Feature-Package + Commands + Events).
- **Persistenz heute:**
  - **MariaDB via HikariCP** (`manager/DatabaseManager`) — der Hauptspeicher. Async-Flush-Framework
    in `db/` (`TimedDatabaseUpdate`, `AsyncTimedUpdate`, `Handler*`): RAM-Cache + getakteter Flush.
  - **YAML-Dateien** (`file/`-Package + `FileBase`) — Welt-/Config-/Setup-Daten (Warps, Mines-Layout,
    Duel-Maps, Season, Border, Team, Shop, Holograms, Casino-Boards …).
  - **Item-NBT** (`tr7zw/item-nbt-api`) — Pickaxe-Enchants, Soulbound, Voucher-IDs.
  - **Binäre Datei-Blobs** — `inventorysaver` (eine Datei pro UUID).
- **Kein Redis / kein Pub-Sub / kein Bungee-Messaging heute.** Alles "Live" läuft Single-Server über
  Bukkit-Scheduler + Broadcasts. Das ist die größte Lücke zur Zielarchitektur.
- **Externe Abhängigkeiten:** WorldGuard/WorldEdit/FAWE, LuckPerms (Ränge/Permissions),
  ProtocolLib (Captcha-Maps, Fake-Walls), HologramAPI, FakeMobs, NuVotifier, ViaVersion,
  ItemNBT-API, MineChaosUMCSpigot, MineChaosDiscordBot, PolarLoader (Anticheat).
  - ✅ **Ränge/Permissions migriert (Foundation, Branch `002-permission-rank-system`):** Das
    LuckPerms-gekoppelte Autoritätsmodell ist durch ein backend-autoritatives Permission-/Rank-System
    hinter dem `PermissionResolver`-Port ersetzt (flache Rollen, mehrere Rang-Grants mit Ablauf,
    additive Wildcard-Auflösung, Live-Entzug via `mc:permission:changed`). Details: PROGRESS.md,
    Abschnitt „Permissions/Ranks — viertes Feature". Das Permission-Modell ist bereits einheitlich
    (eine Welt, kein zweites Authority-Enum).
  - ✅ **Web-Auth-Bridge gebaut (Greenfield-Infra, Branch `003-web-auth-bridge`):** Kein Altplugin-
    Vorgänger (das alte System hatte nur onlinegems/onlinemoney-Webshop-Hinweise, keine Account-Bindung).
    Spieler verbindet ingame seine UUID mit einem Web-Account (`/web link`/`resetPassword` → kurzlebiger
    Single-use-Token in der DB → Passwort im Web). State-stored, kein Live-Pfad, BCrypt hinter Port.
    Details: PROGRESS.md, „Web-Auth-Bridge — fünftes Feature". Verschoben: JWT-Login-Session, `/web unlink`,
    Plugin-`feature.web` (separates Repo).

### Kategorien-Legende
- **DATA-CENTRIC** = lebt von persistenten Daten → Backend wird Source of Truth (Domäne + Persistenz + REST + ggf. Pub-Sub).
- **GAMEPLAY** = überwiegend lokale Spielmechanik → bleibt großteils im Plugin, Backend höchstens für Config.
- **HYBRID** = beides nennenswert.

---

## Übersichts-Tabelle

| # | Feature | Kategorie | Persistenz heute | Live/Pub-Sub | Zielgruppe | UI | Komplex. |
|---|---------|-----------|------------------|--------------|------------|-----|----------|
| 1 | UserData (Profil-Kern) | HYBRID | DB `UserData` (+JSON-Blobs) | ja (Identität) | beide | – | L |
| 2 | Money | DATA-CENTRIC | DB `Currency.Money` | **ja** | beide | – | S |
| 3 | Gems | DATA-CENTRIC | DB `Currency.Gems` | **ja** | beide | – | S |
| 4 | Casino-Chips | DATA-CENTRIC | DB `Currency.CasinoChips` | **ja** | beide | Bank-Menü | S |
| 5 | Event-Currencies (Xmas/Easter) | DATA-CENTRIC | DB `Currency.*Points` | nein | beide | – | S |
| 6 | Transaction/Kaufbestätigung | GAMEPLAY | RAM | nein | Spieler | Confirm | M |
| 7 | Eco-Statistik | DATA-CENTRIC | DB-Aggregat + SystemData | nein | Team | Text | M |
| 8 | Player Stats | HYBRID | DB `Stats` | ja (Leaderboards) | beide | Profil | L |
| 9 | Settings/Toggles | DATA-CENTRIC | DB `UserData.SettingsData` (JSON) | nein | Spieler | Toggle | S |
| 10 | Homes | DATA-CENTRIC | DB `Homes` | nein | beide | – | M |
| 11 | Enderchest | DATA-CENTRIC | DB `EnderChest` (Blob) | **ja (Dupe-Risiko)** | beide | Liste/Seiten | M |
| 12 | Async-DB-Framework | (Infra) | – | (Wurzel der Sync-Lücke) | intern | – | M |
| 13 | Clans | HYBRID | DB `Clan`,`ClanMember` | ja | beide | Chat+Menü | L |
| 14 | Clan-Coins | DATA-CENTRIC | DB `Clan.Coins` | ja | beide | (Clan-Shop?) | S |
| 15 | Clan-Chest | HYBRID | DB `Clan.Chest` (Blob) | **ja (Dupe-Risiko)** | Spieler | 54-Slot-Chest | M |
| 16 | Direct Trade | GAMEPLAY | RAM | nein (same-server) | Spieler | Dual-Confirm | M |
| 17 | Marketplace | HYBRID | DB `Market` (Blob) | **ja** | beide | Liste/Confirm | L |
| 18 | Casino-Hub/Toggle | GAMEPLAY | YAML | nein | beide | – | S |
| 19 | Crash | GAMEPLAY | YAML (Board) + RAM | nein | beide | In-World | L |
| 20 | Blackjack | GAMEPLAY | RAM | nein | Spieler | Aktions-Menü | M |
| 21 | Horse Race | HYBRID | YAML (Track) + RAM | nein | beide | Wett-Menü | L |
| 22 | Coinflip (Money) | GAMEPLAY | RAM | (Lobby pub-sub?) | Spieler | Liste | M |
| 23 | Spawnerflip | GAMEPLAY | RAM | (Lobby pub-sub?) | Spieler | Liste | M |
| 24 | Jackpot (Money) | GAMEPLAY | RAM + SystemData | (netz-weit?) | beide | Create/Spectate | M |
| 25 | Spawnerpot | GAMEPLAY | RAM + SystemData | (netz-weit?) | beide | Create/Spectate | M |
| 26 | Lotto | GAMEPLAY | RAM (fragil!) | nein | beide | – | S |
| 27 | Kopfgeld/Bounty | DATA-CENTRIC | DB `Stats.Kopfgeld` | (online-only) | Spieler | – | S |
| 28 | Crates / Loot-Packs | HYBRID | YAML pro Crate | teilw. | Spieler | Hub/Liste/Anim | L |
| 29 | Crate-Designer (Admin) | DATA-CENTRIC | YAML | nein | Team | Editor-Menü | L |
| 30 | Vouchers | DATA-CENTRIC | DB `CrimeVouchers` | **ja (Doppel-Redeem)** | beide | Item-basiert | M |
| 31 | Booster (Spieler+Global) | HYBRID | DB(UserData)+YAML global | **ja (Global!)** | beide | Hub/Liste | L |
| 32 | Crate-Drops-Config | DATA-CENTRIC | YAML | nein | Team | – | S |
| 33 | Battlepass | HYBRID | DB `BattlepassData` + YAML | ja | Spieler | Roadmap/Liste | L |
| 34 | Daily/Weekly Challenges | HYBRID | DB(JSON) + YAML | ja | Spieler | (in Battlepass) | L |
| 35 | Event-Pass | **STUB (leer)** | – | – | Spieler? | – | S→L |
| 36 | Events (Goal) | GAMEPLAY | RAM (+Eco) | ja (Gesamt-Topf) | beide | – | M |
| 37 | EXP-Event / Savexp | GAMEPLAY | RAM / Item | (Event netz-weit?) | Team/Spieler | – | S |
| 38 | Seasons | HYBRID | YAML `season.yml` + SQL-Reset | ja (Reset 1×) | beide | Info/Hub | L |
| 39 | Ranking / Leaderboards | DATA-CENTRIC | DB-Read-Model (RAM-Cache) | nein (recompute) | Spieler | Liste/Hub | L |
| 40 | Ranking-Hologramme | HYBRID | YAML + DB-Content | nein (per-Server) | Team/Spieler | In-World | M |
| 41 | Liga | HYBRID | abgeleitet + DB-Claim | mild | Spieler | Liste | M |
| 42 | AMS (Spawner-Maschine) | HYBRID | DB `AMS` | teilw. (Eco) | beide | Hub+History | L |
| 43 | AMS-Captcha | GAMEPLAY | RAM | nein | Spieler | Map-Render | M |
| 44 | AMS-History | DATA-CENTRIC | DB `AMSHistory(_Data)` | nein | beide | Liste | M |
| 45 | Base-Protection | HYBRID | DB `BaseProtect` + WG-Region | nein | beide | Hub/Seiten | L |
| 46 | Combat-Tag & -Wall | GAMEPLAY | YAML (Region) + RAM | nein | beide | – | M |
| 47 | Reports | GAMEPLAY→DC | **RAM (verloren bei Restart)** | **ja** | beide | – | S |
| 48 | Support/Tickets | GAMEPLAY | **RAM** | **ja** | beide | Menü | M |
| 49 | Kontakt-Buch | GAMEPLAY | hardcoded | nein | Spieler | Buch | S |
| 50 | Polls/Umfragen | DATA-CENTRIC | DB `Polls` (JSON) | **ja** | beide | Liste/Vote/Ergebnis | M-L |
| 51 | Duels | GAMEPLAY | RAM (+Eco) | ja (intern) | Spieler | Builder/Liste | L |
| 52 | Duel-Kits | GAMEPLAY | hardcoded | nein | Spieler | (in Builder) | S |
| 53 | Duel-Maps/Pools | DATA-CENTRIC | YAML `duel/maps.yml` | nein | Team | Chat | M |
| 54 | Won-Inventory-Claim | GAMEPLAY | RAM (5min) | nein | Spieler | Item-Viewer | S-M |
| 55 | Last Man Standing | GAMEPLAY | RAM (+Crate) | ja (intern) | beide | – (Item/SB) | L |
| 56 | Arena (FFA) | HYBRID | YAML (Kit/Spawns) + RAM | ja (intern) | beide | Join-Hub | M-L |
| 57 | Arena-Kit | DATA-CENTRIC | YAML | nein | Team | – | S |
| 58 | `/kit`-System (Reward-Kits) | HYBRID | DB(UserData JSON) | nein | Spieler | Paged-Hub | L |
| 59 | Mine-Welt/Gamemode | HYBRID | YAML-Layout + RAM | ja (Mine-weit) | beide | Hub/Liste | L |
| 60 | Mine-Progression (Minepoints/Level) | DATA-CENTRIC | DB `MineData` | ja (shared row) | Spieler | – | M |
| 61 | Mine-Pickaxe-Enchants | HYBRID | Item-NBT | nein | Spieler | Upgrade-Menü | M-L |
| 62 | Lucky Blocks | GAMEPLAY | RAM | ja (globale Rewards) | Spieler | – | L |
| 63 | Mine-Sell | HYBRID | DB(JSON-Inventory) | nein | Spieler | Sell-Menü | M |
| 64 | Upgradepoints | DATA-CENTRIC | DB `MineData` | ja (shared row) | Spieler | (in Upgrade) | S |
| 65 | Mine-Hilfe-Buch | GAMEPLAY | hardcoded | nein | Spieler | Buch | S |
| 66 | Cosmetic-Extras-Hub | HYBRID | DB(UserData JSON) | ja (Ownership) | beide | Liste/Toggle | L |
| 67 | Prefix-Registry | DATA-CENTRIC | YAML-Katalog + DB-Besitz | ja | beide | Liste | M |
| 68 | Hat/Head | GAMEPLAY | – | nein | Spieler | – | S |
| 69 | Fake-Entities (NPCs) | HYBRID | YAML | nein (per-Server) | Team | Chat | L |
| 70 | Fake-Entity-Effekte | GAMEPLAY | RAM | nein | Team/Spieler | – | M |
| 71 | Giant-Item-Mobs | HYBRID | YAML | nein | Team | – | S-M |
| 72 | Scoreboard/Tab | HYBRID | RAM (+Toggle in DB) | nein | Spieler | – | L |
| 73 | Christmas-Event | HYBRID | YAML + DB-Points | teilw. | beide | Shop | L |
| 74 | Easter-Event | HYBRID | YAML + DB-Points | teilw. | beide | Shop | M-L |
| 75 | Inventory-Saver | DATA-CENTRIC | Binär-Datei pro UUID | nein | Spieler | – | M |
| 76 | Warps | HYBRID | YAML `WarpFile` | nein | beide | Hub/Liste | M |
| 77 | Teleport/TPA/Back/RTP | GAMEPLAY | RAM | (cross-server TPA?) | beide | – | S-M |
| 78 | Friends | DATA-CENTRIC | DB `UserData.FriendData` (JSON) | (Presence!) | Spieler | Liste | M |
| 79 | Party | GAMEPLAY? | RAM (?) | (?) | Spieler | Menü | S (offen) |
| 80 | Ignore | DATA-CENTRIC | DB `UserData.IgnoreData` | nein | Spieler | – | S |
| 81 | Private Messages + Spy | HYBRID | RAM + DB(Spy-Toggle) | (cross-server DM?) | beide | – | M |
| 82 | Teamchat | GAMEPLAY | – | (cross-server?) | Team | – | S |
| 83 | Chat-Moderation (Filter/Mute/Clear) | HYBRID | YAML (Wortliste) + RAM | **ja (Mute netz-weit)** | Team | – | S-M |
| 84 | Broadcast/Fakemessage | GAMEPLAY | – | (netz-weit?) | Team | – | S |
| 85 | Voting | DATA-CENTRIC | DB `UserData.VoteData` (JSON) | nein | Spieler | – | M |
| 86 | Voteshop | HYBRID | DB-Votepoints + Katalog | nein | Spieler | Liste | M |
| 87 | Outpost | HYBRID | YAML + Clan | ja (1 Instanz) | beide | Info-Menü | M-L |
| 88 | Newbie-Schutz | DATA-CENTRIC | DB `UserData.NewbieMode` | nein | Spieler | – | S |
| 89 | Worldborder | DATA-CENTRIC | YAML `BorderFile` | nein | Team | – | S |
| 90 | Serverteam-Liste | HYBRID | YAML `TeamFile` | nein | beide | Menü | S-M |
| 91 | Staff/Utility-Commands (Bundle) | GAMEPLAY | RAM/keine | nein | überw. Team | teilw. Menü | L (nur Menge) |

---

## Detail-Blöcke nach Domäne

### A. Kern-Daten, Economy & Player-State

**1. UserData / Profil-Kern** — Zentrales Spielerkonto (Name, Rang, Cosmetics, Perks, Rewards, Friends, Ignores, dutzende Progress-Flags), persistiert über Sessions.
- Klassen: `user/User`, `user/UserData` (~69KB God-Class!), `user/UserCrateData`, `manager/UserManager`.
- DATA: DB-Tabelle `UserData` (UUID, LastKnownName, NewbieMode, + viele JSON-Blobs: SettingsData, IgnoreData, VoteData, RewardData, EffectData, ColorData, PerkData, PrefixData, RankData …). Rang zusätzlich in LuckPerms.
- LIVE: ja — heute Single-Server-RAM-Cache, getakteter Flush; bei Mehrfach-Login droht Überschreiben. **Wurzel-Feature**, von fast allem referenziert.
- AUDIENCE: beide. UI: keine direkt (speist andere Menüs). KOMPLEXITÄT: **L** — 69KB-Monolith, viele JSON-Sub-Collections, Reward/Cooldown-State-Machines.
- NOTES: Beim Neuschnitt zerlegen — was wird Identität (Kern), was wandert in Economy/Cosmetics/Social? Friends/Ignores/Profiles könnten in eine Social-Domäne.

**2–5. Währungen (Money / Gems / Casino-Chips / Event-Points)** — Spieler verdienen/geben Geld (`$`), Premium-Gems (`✦`), Casino-Chips (`⛂`), Saison-Points aus.
- Klassen: `user/UserCurrency`, `CommandMoney/Eco/Gems/Gemshop/Casinochips`, `TransactionManager`.
- DATA: **eine DB-Tabelle `Currency`** mit Spalten `Money, Gems, CasinoChips, ChristmasPoints, EasterPoints` (alle long/int). `CurrencyType`-Enum kennt außerdem `SPAWNER` (über AMS bezahlt, NICHT in `Currency`).
- LIVE: **ja** für Money/Gems/Chips — autoritative Balance über Server hinweg, heute lost-write-Risiko. → **Pub-Sub / Backend-Autorität.** Event-Points: nein.
- AUDIENCE: beide (Admin-Edit perm-gated). UI: nur Gemshop/Bank-Menü. KOMPLEXITÄT: **S** je Währung.
- NOTES: **Klarer Economy-Backend-Kandidat (ggf. schon abgedeckt).** `onlinegems`/`onlinemoney`-Admin-Varianten deuten auf Online-Shop-Integration (Webinterface?) → Frage 1.

**6. Transaction / Kaufbestätigung** — Vor jedem Kauf ein Confirm/Cancel-Menü mit Preis & Währung.
- Klassen: `manager/TransactionManager` (Listener), `Transaction`, `TransactionItem`. DATA: RAM. CATEGORY: GAMEPLAY. UI: Confirm. KOMPLEXITÄT: M (4 Währungstypen inkl. AMS-Spawner). NOTES: Geteilte Infra, von vielen Shops genutzt — bleibt Client-seitig, ruft künftig Economy-REST.

**7. Eco-Statistik** — (Admin) Server-weite Eco-Kennzahlen (Geld/Chips/Spawner im Umlauf, Steuern, Ausgaben pro System).
- Klassen: `others/EcoStatistics`, `CommandEco`. DATA: SQL-`SUM()` + Zähler in `GeneralManager.SystemData`. CATEGORY: DATA-CENTRIC. AUDIENCE: Team. KOMPLEXITÄT: M. NOTES: **Starker Webinterface/Admin-Dashboard-Kandidat → Frage 2.**

**8. Player Stats** — Kills/Deaths/Killstreak/Trophies/Playtime/Bounty/Elo/Crates-opened, mehrperiodig (week/month/season/all-time), gerankt.
- Klassen: `user/UserStats` (`StatsType`,`StatsPeriod`), `CommandStats`, Profil via `MuxProfiles`. DATA: DB `Stats` (viele Spalten + `EloData` JSON), SQL-Season-Rank. LIVE: ja (Scoreboards, Rankings, getaktete Resets). KOMPLEXITÄT: **L**. NOTES: Speist Ranking/Liga/Season; Leaderboards evtl. Webinterface.

**9. Settings/Toggles** — Menü für Präferenzen (PMs, Scoreboard, Chat, Death/Join-Msgs, Filter, Spawn, Cosmetics-Sichtbarkeit).
- Klassen: `others/EnumSettings`, `CommandSettings`, `inventory/SettingsInventory`. DATA: JSON in `UserData.SettingsData` (nur Nicht-Defaults). CATEGORY: DATA-CENTRIC. UI: Toggle. KOMPLEXITÄT: S. NOTES: Möglicher Latent-Bug in `getOption` (`>=`-Bounds) — nicht eigenmächtig fixen, nur gemerkt.

**10. Homes** — Benannte Heimat-Punkte setzen & teleportieren; Limit per Permission.
- Klassen: `user/UserHomes`, `others/Home`, `CommandHome`. DATA: DB `Homes` (UUID,Name,XYZ,Yaw,Pitch,World,Dates). CATEGORY: DATA-CENTRIC. UI: keine (Text). KOMPLEXITÄT: M. NOTES: homeLimit aus LuckPerms-Node.

**11. Enderchest** — Persönliche Mehrseiten-Enderchest; Admins sehen/resizen fremde.
- Klassen: `user/UserEnderchest`, `CommandEnderchest`. DATA: DB `EnderChest` (UUID, serialisierter Blob), slots. LIVE: **ja — Item-Dupe-Risiko bei Multi-Server** (kein Lock). UI: Liste/Seiten. KOMPLEXITÄT: M. NOTES: Hoher Sync-/Locking-Bedarf.

**12. Async-DB-Framework (Infra)** — Batcht & flusht Player-Daten getaktet auf Background-Threads.
- Klassen: `db/Handler`,`AsyncTimedUpdate`,`TimedDatabaseUpdate`,`HandlerGroup(s)`,`CompletionState*`, `DatabaseManager`. NOTES: **Migrations-Kernfrage** — das neue Backend ersetzt vermutlich dieses RAM-Cache+Flush-Modell. Die Cross-Server-Autorität fehlt heute genau hier → Frage 3.

### B. Clans, Trade, Markt

**13. Clans** — Spieler gründen/joinen Clan (max 6 Zeichen Tag), gemeinsame Identität, Member-Ränge, Kill/Death-Stats, Clan-Ranking.
- Klassen: `clan/Clan`,`ClanMember`,`MemberList`, `ClanManager`, `CommandClan` (1108 Zeilen). DATA: DB `Clan`(+`ClanMember`); Invites nur RAM. CATEGORY: HYBRID. UI: überw. Chat + 2 Menüs. KOMPLEXITÄT: **L**. NOTES: 3 Ränge; Outpost/Scoreboard-Kopplung.

**14. Clan-Coins** — Clan-interne Währung (z.B. aus Outposts).
- DATA: `Clan.Coins`. NOTES: **Nicht** = Spieler-Money. Frage: in neue Economy modellieren oder Clan-Domäne? → Frage 4.

**15. Clan-Chest** — Geteilte 54-Slot-Truhe für Clan-Mitglieder, freischaltbare Slots.
- Klassen: `clan/ClanChest`. DATA: Base64-Blob in `Clan.Chest`. LIVE: **ja — Dupe-Risiko** (mehrere Viewer gleichzeitig). UI: 54-Slot-Chest. KOMPLEXITÄT: M.

**16. Direct Trade** — P2P-Tausch-Anfrage → beidseitiges Confirm-Fenster, Item-Swap.
- Klassen: `TradeManager`, `trade/*`, `CommandTrade`. DATA: RAM. CATEGORY: GAMEPLAY. LIVE: nur same-server/online. UI: Dual-Confirm. KOMPLEXITÄT: M. NOTES: Kein Geld-Leg (nur Items).

**17. Marketplace** — Spieler listet Item zu Festpreis (`/sell`), andere browsen paginiertes Menü & kaufen; Erlös minus Steuer an Verkäufer (auch offline).
- Klassen: `MarketManager`, `others/market/*`, `CommandMarketplace`. DATA: DB `Market` (ID,Timestamp,Seller,Price,`SoldItem` BLOB) + `marketplace`-YAML; `UserData.UnannouncedMarketIncome`. LIVE: **ja** — Verkauf muss überall sofort invalidieren (Doppelkauf). UI: Liste/Hub/Confirm + Filter. KOMPLEXITÄT: **L**. NOTES: **Economy-Integration (Erlös/Steuer/Offline-Income)**; Steuer-Calc liegt kurioserweise in `CasinoManager` (Kopplung prüfen).

### C. Gambling / Casino

**18. Casino-Hub/Toggle** — `/casino` warpt in den Casino-Bereich, global an/aus.
- Klassen: `CommandCasino`,`CasinoConfig`,`CasinoManager`,`CasinoEvents`. DATA: YAML (`CasinoEnabled`). CATEGORY: GAMEPLAY. NOTES: `CasinoManager` = Aggregator (Steuern `MONEY_TAXES`/`SPAWNER_TAXES`, `god`-Steuerreduktion, 2% Dailypot-Skim, handlePayin/out).

**19–21. Crash / Blackjack / Horse Race** — Chips-Tischspiele (steigender Multiplikator / Blackjack vs. Haus / Pferderennen-Wette).
- Klassen: `gamble/crash/*`,`gamble/blackjack/*`,`gamble/horserace/*`. DATA: Crash/HorseRace YAML (Board/Track-Geometrie) + RAM; Blackjack RAM. CATEGORY: GAMEPLAY (HorseRace HYBRID). LIVE: nein (1 Spiel pro Server, lokal via Scheduler + FakeEntity). KOMPLEXITÄT: L/M/L. NOTES: `CasinoGame`-Enum listet `ROULETTE` ohne Impl (geplant/entfernt?).

**22–23. Coinflip / Spawnerflip** — Offener 50/50-Einsatz (Money bzw. AMS-Spawner), anderer joint, Winner-takes-all.
- Klassen: `gamble/flip/*`, `CoinflipManager`/`SpawnerflipManager`. DATA: RAM-Lobby. CATEGORY: GAMEPLAY. LIVE: Lobby ist geteilter Server-State → **Pub-Sub-Kandidat falls netzweite Lobby gewünscht.** UI: Liste + Wheel. Spawnerflip hängt hart an **AMS**.

**24–25. Jackpot / Spawnerpot** — Getakteter Topf (Money bzw. Spawner), gewichteter Zufalls-Gewinner; benötigt Discord-Verify.
- Klassen: `gamble/jackpot/*`,`gamble/spawnerpot/*`, `JackpotManager`/`SpawnerpotManager`, `*CreateInventory`. DATA: RAM + Zähler in SystemData. LIVE: server-weiter Broadcast, 1 aktiv/Server → **Pub-Sub-Kandidat für netzweiten Topf.** UI: Create/Spectate/Wheel. Spawnerpot ≈ Jackpot-Klon mit Spawner-Währung.

**26. Lotto** — Tickets (30k$) kaufen, Admin löst Gewinner aus.
- Klassen: `CommandLotto` (komplett im Command, **RAM-Felder → bei Restart verloren**). NOTES: Fragil, nutzt keine Casino-Infra; evtl. in Jackpot-System falten.

**27. Kopfgeld/Bounty** — Geld zahlen, um Kopfgeld auf einen Spieler zu setzen.
- Klassen: `CommandKopfgeld`. DATA: persistiert auf `UserStats.Kopfgeld`; Cooldown RAM. NOTES: Nur Platzierung hier; Auszahlung bei Kill liegt im Combat/Stats-Pfad. Ziel muss online sein (cross-server → Pub-Sub).

### D. Crates, Vouchers, Booster

**28. Crates / Loot-Packs** — Crate-Item öffnen → Animation → gewichteter Reward.
- Klassen: `CrateManager`, `gamble/packs/*` (`DefinedCrate`,`PublicCrate`,`CrateOpening`,`CrateView`…), `events/PackEvents`. DATA: **eine YAML pro Crate** (`<id>.yml`) + Crate-Statistik. CATEGORY: HYBRID. UI: Hub/Liste/Anim. KOMPLEXITÄT: **L**. NOTES: `Migrater.java` = Einmal-Konverter (wahrsch. tot). Reward-Money/Points überlappen Economy.

**29. Crate-Designer (Admin)** — In-Game-Editor für Crates & Reward-Inhalte (typisierte Optionen, Anvil/Chat-Input, ~30 Templates).
- Klassen: `gamble/packs/designer/*` (context/input/template/predefined inkl. `_old/`). DATA: schreibt zurück in Crate-YAML. CATEGORY: DATA-CENTRIC. AUDIENCE: Team. KOMPLEXITÄT: **L**. NOTES: **Starker Webinterface-Kandidat (CRUD-Editor) → Frage 5.** `_old/` = Altlast.

**30. Vouchers** — Voucher-Item einlösen → Booster/Rang/Perk/Prefix/Enderchest-Slots.
- Klassen: `VoucherManager`, `others/voucher/*` (factory, repository, impl: booster/rank/perk/prefix/enderchest). DATA: **DB `CrimeVouchers`** + RAM-Cache. CATEGORY: DATA-CENTRIC. LIVE: **ja — Doppel-Redeem-Race über Server ohne Pub-Sub.** UI: Item-basiert. KOMPLEXITÄT: M. NOTES: Rang/Perk/Prefix-Payloads überlappen Cosmetics/Permissions.

**31. Booster (Spieler + Global)** — Zeitlich begrenzte Multiplikatoren (Sell/Percentage/Trophies/Lucky/Upgrade/AMS); Admins schalten server-weite Global-Booster.
- Klassen: `BoosterManager`, `others/booster/impl/*`. DATA: Spieler-Booster in `UserData`; **Global-Booster in YAML `game/globalboosters.yml`**; aktive Stacks RAM. LIVE: **ja — "Global" ist heute irreführend Single-Server** (YAML + lokaler Broadcast). **Klarste Pub-Sub-Lücke.** UI: Hub/Liste. KOMPLEXITÄT: **L**.

**32. Crate-Drops-Config** — Wie viele Crates aus Mine/AMS droppen.
- Klassen: `file/CrateDropsFile`. DATA: YAML (`mineDrops`,`amsDrops`). CATEGORY: DATA-CENTRIC. AUDIENCE: Team. NOTES: Webinterface-Kandidat.

### E. Progression: Battlepass, Events, Seasons, Ranking

**33. Battlepass** — Reward-Points sammeln, monatliche Tier-Reihe (Free + Premium), Rewards im Roadmap-Menü claimen.
- Klassen: `BattlepassManager`, `others/battlepass/*`, `user/UserBattlepassData`, `CommandBattlepass`. DATA: DB `BattlepassData` (UUID, Premium, RewardPoints, LastResetDate, + JSON: CollectedRewards/FinishedChallenges/Progress); Tier-Defs **hardcoded** in `DefaultCollection` (pro Monat); Manager-State in YAML `game/battlepass.yml`. CATEGORY: HYBRID. UI: Roadmap/Liste. KOMPLEXITÄT: **L**. NOTES: Premium-Aktivierung wohl Economy/Shop; Tier-Authoring evtl. Webinterface.

**34. Daily/Weekly Challenges** — Rotierende Tages-/Wochen-Aufgaben → Battlepass-Points.
- Klassen: `others/battlepass/challenges/*` (~3 daily, ~15 weekly), Registry in `BattlepassManager`. DATA: Fortschritt in `BattlepassData`-JSON; Rotation in YAML + RAM. KOMPLEXITÄT: **L**. NOTES: Jede Challenge koppelt an ein anderes Subsystem (Crates/AMS/Mine/Casino/Clan/Duel/Vote/Markt). Katalog/Rotation evtl. config-/web-getrieben.

**35. Event-Pass** — **Leerer Stub** (`EventPassManager {}`, neueste Datei, Mai 2025).
- NOTES: **Greenfield.** Vermutlich Battlepass-Modell auf Event skaliert. **Backend-first / Webinterface-authored bauen? → Frage 6.**

**36. Events (Goal)** — Community-Geld-Ziel, in das Spieler einzahlen.
- Klassen: `EventManager` (`GoalEvent`), `CommandGoal`. DATA: überw. RAM (Payins, Refund-Logik). CATEGORY: GAMEPLAY. LIVE: ja (geteilter Gesamt-Topf, Broadcast). NOTES: Geld-Fluss überlappt **Economy**; Gesamt-Topf-Sync offen.

**37. EXP-Event / Savexp** — Admin schaltet XP-Multiplikator-Event; Spieler "bottlen" XP in Item.
- Klassen: `CommandEventxp` + `others/EventXP` (Enum), `CommandSavexp`. DATA: aktiver Modus RAM. NOTES: Event-State ohne Persistenz — übersteht Restart nicht; netzweite Anwendung offen.

**38. Seasons** — Zeitlich begrenzte Wettkampf-Saisons (Start/Ende geplant), Season-Stats-Reset, Top-3-Podest-Mobs, Info-Menü.
- Klassen: `SeasonManager` (`RankedSeasonMob`), `CommandSeason`. DATA: YAML `season.yml` + **SQL-Mass-Reset** `UPDATE Stats SET SeasonKills=0…`. CATEGORY: HYBRID. LIVE: ja — Reset/Broadcast muss genau **1×** netzweit laufen (Koordination!). UI: Info/Hub. KOMPLEXITÄT: **L**. NOTES: Season-End-Prämien auto-vergeben? offen.

**39. Ranking / Leaderboards** — Multi-Kategorie-Bestenlisten (Kills/Trophies/Money/Playtime/Mine/Clan/Crates/Chips) über Perioden.
- Klassen: `RankingManager`, `CommandRanking`/`CommandTptop`, `StatsPeriod`. DATA: **DB-Read-Model**, RAM-Cache, periodische `SELECT … ORDER BY … LIMIT 10`. CATEGORY: DATA-CENTRIC. LIVE: nein (recompute). UI: Liste/Hub. KOMPLEXITÄT: **L** (44KB). NOTES: **Starker Webinterface-Kandidat (reines Read-Model) → Frage 2.**

**40. Ranking-Hologramme** — In-World-Hologramme für Top-Chips & Chips-Lost.
- Klassen: `others/rankinghologram/*`, HologramAPI. DATA: YAML-Platzierung + DB-Content. NOTES: Nur 2 Typen implementiert; Welt-gebunden → bleibt server-seitig.

**41. Liga** — Trophy-basierte Ligen (Bronze/Silber/Gold/Platin) + Liga-Reward-Menü.
- Klassen: `others/EnumLiga`, `inventory/LigaRewardInventory`, `CommandLiga`. DATA: Liga **abgeleitet** aus Trophies; Claim-State in `UserData`. CATEGORY: HYBRID. UI: Liste. NOTES: Reward-Auszahlung überlappt Economy; Schwellen hardcoded.

### F. AMS, Base, Combat, Moderation, Polls

**42. AMS (Spawner-Maschine / Auto-Money-System)** — Spieler platziert "Maschine", die aus eingefütterten Spawnern passiv Geld/Crates/Booster generiert; Ertrag wird abgeholt.
- Klassen: `others/ams/*`, `AMSManager`, `PowerUpgrade`. DATA: DB `AMS` (Owner, Location, GenMode, Spawners, Coins, Caps, OfflineTime …). CATEGORY: HYBRID. LIVE: Eco-Werte ja, Maschine an Location gebunden (Single-Server-Gameplay). UI: Hub + History. KOMPLEXITÄT: **L**. NOTES: Kern-Eco-Engine des Gamemodes; Output speist Economy, aber Tick/Gen-Logik bleibt im Plugin.

**43. AMS-Captcha** — Beim Abholen erscheint Zahlen-Captcha auf Map, im Chat eintippen (Anti-Macro).
- Klassen: `others/ams/captcha/*`, ProtocolLib/NMS. DATA: RAM. **Wichtige Korrektur:** Fehlschlag bricht nur die Abholung ab — **kein Ban/Kick, keine Strafe.** → **Kein Punishments-Overlap.** KOMPLEXITÄT: M.

**44. AMS-History** — Log dessen, was an einer Maschine gesammelt/getan wurde.
- Klassen: `AMSHistoryHolder`, `others/ams/history/*` + generisches `others/history/*` (EAV). DATA: DB `AMSHistory` + `AMSHistory_Data`. CATEGORY: DATA-CENTRIC. UI: Liste. NOTES: Generisches History-Framework — evtl. in Audit-Log-Backend/Webinterface.

**45. Base-Protection** — "Base-Protector"-Block erzeugt WorldGuard-Region um Basis; bezahlte Lizenzen (Chest/Tech/Brewing), Friend-Zugang, PvP-Toggle.
- Klassen: `others/baseprotect/*`, `BaseManager`, `CommandBaseprotector`. DATA: DB `BaseProtect` + WorldGuard-Region. CATEGORY: HYBRID. UI: Hub/Seiten. KOMPLEXITÄT: **L**. NOTES: Kosten via `UserCurrency` (Economy-Kopplung); WG-gebunden → bleibt Gameplay.

**46. Combat-Tag & -Wall** — 10s "im Kampf"; Glaswand um PvP-Arena-Region.
- Klassen: `CombatManager`, `others/CombatWall`, `CommandCombatwall`. DATA: Region in config-YAML; Rest RAM. CATEGORY: GAMEPLAY. UI: keine (Packet-Wall). KOMPLEXITÄT: M.

**47. Reports** — Spieler meldet Spieler mit Grund; Online-Staff wird benachrichtigt.
- Klassen: `ReportManager`, `others/Report`, `CommandReport`. DATA: **RAM (verloren bei Restart!)**. LIVE: **ja.** KOMPLEXITÄT: S. NOTES: **Starker Overlap mit neuem Punishments-Backend** (Input-Seite) → Frage 7.
- ✅ **MIGRIERT (Backend, Slice 1, 2026-06-22)** — eigenständiges Moderation-Modul, state-stored,
  getrennt von Punishments (Constitution-Prinzip 16). RAM → persistente Backend-Speicherung; erzeugt nie
  eine Strafe. Optionaler öffentlicher Chat-Kontext-Schnappschuss ergänzt. Spec/Plan: `specs/001-reports/`;
  Details in PROGRESS.md („Reports — drittes Feature"). Verschoben: PNs im Chat-Kontext, Lese-Endpoint
  für abgeschlossene Reports, Retention/Purge, Referenz Report→Punishment, Plugin-Menü.

**48. Support/Tickets** — Spieler fordert Live-Support; Staff nimmt an, privater Chat, danach Bewertung.
- Klassen: `SupportManager` (inner `Support`), `CommandSupport`/`CommandKontakt`. DATA: **RAM** (Queue, Sessions, Ratings verworfen). LIVE: **ja.** UI: Menü. KOMPLEXITÄT: M. NOTES: Ticketing/Webinterface-Kandidat; Ratings würden persistiert.

**49. Kontakt-Buch** — `/kontakt` → Buch mit Discord/TS/YouTube-Links.
- Klassen: `CommandKontakt`. DATA: hardcoded. NOTES: → Config/Webinterface.

**50. Polls/Umfragen** — Admin-erstellte Umfragen (Ja/Nein oder Multiple-Choice), Live-Zwischenergebnis, Archiv.
- Klassen: `others/poll/*`, `PollManager`, `CommandPoll`. DATA: DB `Polls` (Antworten + Stimmen als JSON in der Zeile). CATEGORY: DATA-CENTRIC. LIVE: **ja** (netzweite Aggregation/Live-Ergebnis). UI: Liste/Vote/Ergebnis. KOMPLEXITÄT: M-L. NOTES: Stimmen denormalisiert (JSON, keine Votes-Tabelle) — bei Migration normalisieren; Webinterface-Admin-View.

### G. Minispiele: Duel, LMS, Arena, Kit

**51. Duels** — Spieler fordert Spieler/Team/Clan zu konfigurierbarem Duell auf konfigurierter Map mit Einsatz.
- Klassen: `DuelManager`, `duel/setup/*`, `duel/game/*`, `CommandDuel`. DATA: überw. RAM; Maps persistent (s.u.); Einsätze via Economy; Battlepass `WIN_DUEL`. CATEGORY: GAMEPLAY. UI: Builder/Liste. KOMPLEXITÄT: **L** (3 Setup-Varianten). NOTES: Wetten (`winMoney`/`winSpawners`) → Economy; keine Ergebnis-History persistiert (Stats wären net-new). Legacy-Import `de.byrizon…Manager_Sumo` (Altlast).

**52. Duel-Kits** — Preset-Loadouts (Basic/Ritter/Gapple/Potion/Taktik) für Duelle.
- Klassen: `duel/kit/*`. DATA: **hardcoded.** CATEGORY: GAMEPLAY. NOTES: Editierbar = net-new.

**53. Duel-Maps/Pools** — Admin definiert Arenen (Region + Spawns) in Map-Pools.
- Klassen: `duel/MapConfig`,`MapPool`,`MapRegistry`. DATA: YAML `duel/maps.yml`. CATEGORY: DATA-CENTRIC. AUDIENCE: Team. NOTES: Webinterface-Kandidat.

**54. Won-Inventory-Claim** — Gewinner eines "eigenes Inventar"-Duells claimt 5 Min lang das verlorene Inventar.
- Klassen: `duel/InventoryWin`. DATA: RAM (5 Min). UI: Item-Viewer. KOMPLEXITÄT: S-M.

**55. Last Man Standing** — Rechtsklick startet FFA-Event im 40×40-Bereich; letzter Überlebender gewinnt Crate.
- Klassen: `others/lms/*`, `LastManStandingManager`, state-impl, `LMSWall extends CombatWall`. DATA: RAM (+Crate). CATEGORY: GAMEPLAY. LIVE: ja intern (Single-Server, 1 Instanz). KOMPLEXITÄT: **L** (State-Machine, Packet-Walls). NOTES: `// TODO add pvp ban`; Crate-Reward → Economy/Crate. Discord-Verify nötig.

**56. Arena (FFA)** — Persistente FFA-Arena mit Shared-Kit; nach Countdown PvP an, Kill-Tracking.
- Klassen: `others/arena/*` (countdown/events/file/game), `CommandArena`. DATA: YAML (Kit/Spawns) + RAM. CATEGORY: HYBRID. UI: Join-Hub. KOMPLEXITÄT: M-L (4 verkettete Countdowns).

**57. Arena-Kit** — Das eine Loadout, das jeder Arena-Teilnehmer bekommt.
- Klassen: `arena/game/Kit`, `arena/file/KitFile`. DATA: YAML. AUDIENCE: Team (snapshot des Admin-Inventars).

**58. `/kit`-System (Reward-Kits)** — `/kit`-Menü: periodische Gratis-Loadouts (Cooldown), kaufbare & einmalige Kits.
- Klassen: `KitManager`, `others/kit/*` (Timed/Buyable/Unique/CustomUnique), `CommandKit`. DATA: Defs **hardcoded**; Cooldowns + claimed-unique als **JSON in `UserData`**. CATEGORY: HYBRID. UI: Paged-Hub. KOMPLEXITÄT: **L**. NOTES: 3 unterschiedliche "Kit"-Begriffe im Code (Duel-Kit / Arena-Kit / `/kit`) — nicht verwechseln. BuyableKit → Economy.

### H. Mine-Gamemode

**59. Mine-Welt/Gamemode** — Dedizierte Mine-Welt mit 25 freischaltbaren Mines, regenerierende Blöcke, Prestige-Level.
- Klassen: `others/mines/*`, `MinesManager` (1309 Z.), `MineEvents` (836 Z.). DATA: YAML-Layout + RAM-Runtime. CATEGORY: HYBRID. UI: Hub/Liste. KOMPLEXITÄT: **L**. NOTES: `RankedMinesMob`-Leaderboard-NPCs evtl. eigenes Feature/Webinterface.

**60/64. Mine-Progression (Minepoints/Level) & Upgradepoints** — Mining gibt MineXP/Level (schaltet Mines frei); Upgradepoints für Pickaxe-Enchants.
- Klassen: `user/UserMineData`, `MinesManager.checkRankup`. DATA: DB `MineData` (Level, Minepoints, Upgradepoints, BlocksMined, MinePlaytime, SellAmount, Luckyblocks, `Inventory` JSON). CATEGORY: DATA-CENTRIC. NOTES: Minepoints = XP (nicht spendable); Upgradepoints = Währung → evtl. in neue Economy. UI-Label "MineXP" vs DB "Upgradepoints" (Naming klären). `T-N`-Crate als Rankup-Reward (Crate-Kopplung).

**61. Mine-Pickaxe-Enchants** — Custom-Enchants (Explosion/Veinmining/AutoSell/Crusher/LuckyFinder/Fortune) auf Mining-Pickaxe.
- Klassen: `others/mines/enchants/*`. DATA: **Item-NBT.** CATEGORY: HYBRID. UI: Upgrade-Menü (Confirm). KOMPLEXITÄT: M-L. NOTES: Kosten in Upgradepoints.

**62. Lucky Blocks** — Beacon-Lucky-Blocks in Mines → Zufalls-Reward (Potions/Fly/Crate/Drop-Boost …).
- Klassen: `others/mines/luckyblock/*` (8+ Reward-Typen). DATA: RAM (+ Zähler in MineData). CATEGORY: GAMEPLAY. LIVE: **ja für globale Rewards** (Broadcast an alle in der Mine). KOMPLEXITÄT: **L**.

**63. Mine-Sell** — Geminte Blöcke gegen Minepoints verkaufen (Menü/Insta/AutoSell).
- Klassen: `MinesManager.sellInventory`, `MineBlock` (28 Block-Werte hardcoded). DATA: virtuelles Inventar als JSON in `MineData`. CATEGORY: HYBRID. UI: Sell-Menü. NOTES: Block-Werte hardcoded → evtl. Economy/Config; `MINE_SELL`-Booster.

**65. Mine-Hilfe-Buch** — In-Game-Buch erklärt Mines/Level/Enchants. DATA: hardcoded. NOTES: → Webinterface/Wiki.

### I. Cosmetics, Fake-Entities, Scoreboard, Seasonal-Events, Infra

**66. Cosmetic-Extras-Hub** — Menüs zum Anzeigen/Ausrüsten besessener Cosmetics (Kill-Effekte, Partikel, Trails, Chat-Farben, Perks, Prefixes).
- Klassen: `ExtrasManager`, `others/extras/*`, `inventory/ExtrasInventory`, `CommandExtras`. DATA: Besitz/Auswahl als JSON in `UserData` (EffectData/ColorData/PerkData/PrefixData); Runtime-Render RAM. CATEGORY: HYBRID. LIVE: ja für Ownership-Grants. UI: Liste/Toggle. KOMPLEXITÄT: **L**. NOTES: Grant/Revoke überlappt Economy/Shop & Admin-Webinterface.

**67. Prefix-Registry** — Chat/Tab-Prefix aus Katalog wählen; Admin verwaltet Katalog.
- Klassen: `others/extras/Prefix`, `ExtrasManager`, `CommandNitro`/`NitroInventory`. DATA: **Katalog YAML + Besitz DB(UserData).** CATEGORY: DATA-CENTRIC. LIVE: ja (Scoreboard/Chat). NOTES: Katalog-YAML vs DB-Besitz inkonsistent — vereinheitlichen. "Nitro"-Prefix = Boost-Integration?

**68. Hat/Head** — `/hat` (Item als Helm), `/head` (eigener Kopf). DATA: keine. KOMPLEXITÄT: S.

**69. Fake-Entities (NPCs)** — Admin platziert benannte NPCs mit Nametags, klickbar → Shop/Casino/RTP/Mine/Event …
- Klassen: `FakeEntityManager`, `others/fakeentity/*` (~20 Optionen). DATA: YAML pro Entity. CATEGORY: HYBRID. AUDIENCE: Team. KOMPLEXITÄT: **L**. NOTES: Optionen = Hooks in viele andere Features (Integrationspunkt). YAML-pro-Server vs zentrale Registry = Design-Frage.

**70. Fake-Entity-Effekte** — Partikel-Animationen um NPCs (Stars/Beam/Sparkle/Matrix/Spiral/Helix). DATA: RAM. Sub-Feature von #69.

**71. Giant-Item-Mobs** — Admin spawnt Riesen-Entity, das ein Item hält. DATA: YAML. Externe FakeMobs-Lib. KOMPLEXITÄT: S-M.

**72. Scoreboard/Tab** — Kontext-Sidebar (Lobby/Mine/Fight/LMS) + Tab mit Team-Farben/Prefixes/Vanish.
- Klassen: `ScoreboardManager`, `manager/scoreboard/*`. DATA: überw. RAM (+ Toggle in `UserData`). CATEGORY: HYBRID. KOMPLEXITÄT: **L**. NOTES: Stark gekoppelt (Content aus vielen Subsystemen); Render bleibt In-Game.

**73/74. Christmas / Easter-Event** — Saison-Mobs (Schneemann/Hase) an Admin-Punkten töten → Saison-Points → Event-Shop (Crates/Rang-Upgrades).
- Klassen: `others/christmas/*`, `others/eastern/*` (+ FakeEntity-Optionen). DATA: YAML (Spawnpoints/Enabled) + DB-Points (`Currency`). CATEGORY: HYBRID. UI: Shop. KOMPLEXITÄT: L / M-L. NOTES: **Christmas & Easter sind nahezu Klone (Code dupliziert)** → in 1 parametrisiertes "Seasonal-Event"-Framework mergen. Rang-Upgrade-Rewards überlappen Permissions.

**75. Inventory-Saver** — Voll-Inventar+Rüstung sichern (z.B. bei Event-Eintritt) & wiederherstellen, restart-fest.
- Klassen: `others/inventorysaver/*`. DATA: **Binär-Datei pro UUID** in `game/inventorybackup/`. CATEGORY: DATA-CENTRIC. NOTES: Fragil/legacy → in DB/Blob-Store; programmgesteuert von Events/Arena/LMS genutzt.

### J. Teleport, Social, Chat, Vote, Outpost, Staff-Tools

**76. Warps** — `/warp`-GUI (oder `/warp <name>`) zu Server-Locations (Spawn/FPS/End/Nether).
- Klassen: `CommandWarp`, `WarpManager`, `file/WarpFile`, `others/Warp`. DATA: YAML (Warps + Live-Counter-Config). CATEGORY: HYBRID. UI: Hub/Liste. NOTES: Warp-Namen hardcoded in `loadInventories`.

**77. Teleport/TPA/Back/RTP/Staff-TP** — `/tpa`,`/tpahere`,`/back`,`/random` (RTP) + Staff `/tp`,`/tphere`,`/tpall`,`/forcetp`,`/tptop`,`/fixposition`.
- Klassen: `CommandTpa`/`Back`/`Random`/`Teleport`/…, `others/TPRequest`,`TPDelay`, `GeneralManager`. DATA: RAM. CATEGORY: GAMEPLAY. NOTES: Cross-Server-TPA bräuchte Pub-Sub (heute nicht).

**78. Friends** — Freunde hinzufügen, Liste mit Online-Status, "ist online"-Alerts.
- Klassen: `CommandFriends`/`others/MuxFriends`, `UserData.FriendData`. DATA: DB-JSON. CATEGORY: DATA-CENTRIC. LIVE: Presence nur Single-Server → **cross-server Presence = Pub-Sub-Kandidat** ("Mux"-Naming deutet das an). UI: Liste.

**79. Party** — Party-Menü (Gruppe). Klassen: `CommandParty`, `inventory/PartyInventory` (nicht inspiziert). DATA: vermutl. RAM. NOTES: **Offen — Datenmodell in `inventory/PartyInventory`; evtl. Clan-Überlapp → Frage 8.**

**80. Ignore** — Spieler blockieren. DATA: DB `UserData.IgnoreData`. CATEGORY: DATA-CENTRIC. NOTES: Evtl. in Social/Moderation-Modul.

**81. Private Messages + Spy** — `/msg`/Reply; Staff-Spy auf Messages/Commands.
- Klassen: `CommandMsg`,`CommandMessagespy`,`CommandCommandspy`, `others/SpyMode`. DATA: Msgs RAM; Spy-Toggle DB. CATEGORY: HYBRID. NOTES: Cross-Server-DM/Spy = Pub-Sub-Kandidat. Kein separates Reply-Command.

**82. Teamchat** — Staff-only Chat-Kanal. DATA: keine. NOTES: Cross-Server-Staff-Chat = klassischer Pub-Sub-Kandidat.

**83. Chat-Moderation (Filter/Wordfilter/Globalmute/Chatclear)** — Filter-Toggle, Blacklist-Wortliste, abgestufter Global-Mute, Chat leeren.
- Klassen: `CommandChatfilter`/`Wordfilter`/`Globalmute`/`Chatclear`, `GeneralManager`, `ConfigFile`. DATA: Wortliste YAML; Mute/Filter RAM-Flags. CATEGORY: HYBRID. LIVE: **ja — Mute sollte netzweit wirken** (Pub-Sub). NOTES: **Mute überlappt Punishments-Backend → Frage 7.**

**84. Broadcast/Fakemessage** — Server-Ansage; Nachricht im Namen eines anderen Spielers faken.
- DATA: keine. AUDIENCE: Team. NOTES: Fakemessage = Troll/Mod-Tool, im modernen Staff-Toolset überdenken.

**85. Voting** — `/vote` → klickbare Vote-Links; Rewards/Points bei registriertem Vote.
- Klassen: `CommandVote`, `events/VotifierListener`, `UserData.VoteData`, NuVotifier. DATA: DB-JSON. CATEGORY: DATA-CENTRIC. NOTES: Vote-URLs hardcoded → Config/Webinterface; Reward-Payout überlappt Economy.

**86. Voteshop** — Vote-Points in GUI ausgeben. Klassen: `CommandVoteshop`, `inventory/RewardInventory`. DATA: Points DB + Katalog (außerhalb Command). NOTES: Reward-Katalog evtl. via VoucherManager.

**87. Outpost** — Clans erobern/halten umkämpften Outpost → Rewards/Standing; Staff setzt/löscht Spot.
- Klassen: `CommandOutpost`, `others/Outpost`, `inventory/OutpostInventory`, `ClanManager`. DATA: YAML (Location/Health/Ownership) + Clan-HeldTime. CATEGORY: HYBRID. LIVE: ja (1 autoritative Instanz nötig → Pub-Sub/Shared-State). UI: Info-Menü. KOMPLEXITÄT: M-L. NOTES: Hart an **Clan** gekoppelt; Reward → Economy.

**88. Newbie-Schutz** — Neue Spieler geschützt bis `/newbie` (Opt-out). DATA: DB `UserData.NewbieMode` (boolean). CATEGORY: DATA-CENTRIC. NOTES: Enforcement in Combat-Listenern.

**89. Worldborder** — Pro-Welt-Border setzen. DATA: YAML `BorderFile`. CATEGORY: DATA-CENTRIC. AUDIENCE: Team.

**90. Serverteam-Liste** — Staff-Roster (Rang) für Spieler sichtbar + `TeamInventory`-GUI. DATA: YAML `TeamFile`. CATEGORY: HYBRID. NOTES: Überlappt Ranks/Permissions/Webinterface.

**91. Staff/Utility-Commands (Bundle)** — Großer Eimer stateless QoL/Staff-Commands: `/fly /god /heal /feed /gamemode /vanish /speed /kill /more /stack /rename /relore /iteminfo /sudo /playertime /time /fix /ifix /clear /workbench /anvil /smelt /build /break /near /ping /list /youtube /regeln /help /spenden /goldswitch /switchglass /strafe /immortal /serverpvp /zaubertisch …`
- DATA: überw. **RAM/keine** (Flags in GeneralManager/UserData, reset bei Logout/Restart) oder reine Item-/World-Mutation.
- AUDIENCE: überw. Team; einige Spieler-Info (`/list /ping /near /help /regeln /youtube /spenden`).
- UI: meist keine; **Menü-Sub-Flags:** `/invsee`,`/bodysee` (fremdes Inventar), einige geben Item-/Confirm-GUIs.
- **Nicht-trivial (eigenständig migrieren):** `/worldborder` (#89), `/serverteam` (#90), `/giveall` (vergibt Voucher an alle → DB-Voucher, Economy-gekoppelt), `/vanish`+`/god` (Flags von vielen Listenern gelesen; netzweites Vanish = Pub-Sub-Kandidat; `/god` berührt CrateManager), `/relore`+`/rename` (Item-Meta-Editor, keine Persistenz), `/superpickaxe` (Admin-NBT-Tool), `/serverpvp` (setzt World-PvP, nicht persistiert).
- KOMPLEXITÄT: S je Command; Bundle ist **L** nur durch Menge.

---

## 1. Vorgeschlagene Migrations-Reihenfolge

**Heuristik:** Erst etwas Einfaches & Abhängigkeitsfreies (testet die Import-Pipeline risikoarm) →
Foundation-Features (von denen andere abhängen) → komplexe/Lieblings-Features später.

### Phase 0 — Pipeline-Test an etwas Risikoarmem
1. **Worldborder (#89)** *oder* **Warps (#76)** — kleine, isolierte YAML→Backend-Config, kein
   Spieler-Datenrisiko, eigenes Menü (testet REST-Config + Menü-Framework end-to-end).
2. **Settings/Toggles (#9)** — kleines persistentes Spieler-Datum, klares Toggle-Menü; testet den
   User-Datenpfad (REST read/write) ohne Geld-Risiko.

### Phase 1 — Foundation (von vielen referenziert)
3. **UserData-Kern / Identität (#1, #12)** — *die* Wurzel. Erst hier die Source-of-Truth-Frage &
   Cross-Server-Autorität klären; ersetzt das `db/`-Flush-Framework. Alles andere baut darauf.
4. **Economy (#2–#6): Money, Gems, Casino-Chips, Event-Points, Transaction** — Foundation für
   Shop/Markt/Casino/Crates/Base/Kit/Goal/Liga/Vote. **Prüfen, ob das neue Economy-Backend das
   schon abdeckt** (s. Abschnitt 2) — dann nur Client-Anbindung statt Neubau.
5. **Permissions/Ranks-Brücke** — Ränge liegen in LuckPerms + `UserData`; klären, wie das neue
   System Ränge/Perks hält (betrifft Cosmetics, Vouchers, Serverteam, Event-Shops).

### Phase 2 — Datenzentrische Features auf der Foundation
6. **Stats (#8)** → **Ranking/Leaderboards (#39)** → **Liga (#41)** → **Seasons (#38)** — eine
   Kette; Stats zuerst, dann die Read-Models darauf. (Leaderboards evtl. Webinterface, s. Frage 2.)
7. **Homes (#10), Ignore (#80), Newbie (#88), Voting (#85)** — kleine, unabhängige
   UserData-/DB-Features; gute "Breite gewinnen"-Batch nach der Foundation.
8. **Enderchest (#11)** — datenzentrisch, aber **Dupe-/Locking-Design zuerst lösen** (Multi-Server).
9. **Vouchers (#30)** — schon DB-/Repository-strukturiert; braucht Pub-Sub gegen Doppel-Redeem.
   Foundation für Crate-/Event-/Booster-Rewards.

### Phase 3 — Größere datenzentrische Domänen
10. **Clans (#13–#15)** (+ später **Outpost #87**, das daran hängt) — DB-strukturiert, aber groß;
    Clan-Chest braucht dasselbe Dupe-Locking wie Enderchest.
11. **Marketplace (#17)** — hängt an Economy + Vouchers; Pub-Sub für Invalidierung.
12. **Booster (#31)** — hängt an UserData + Economy; **Global-Booster = erste echte Pub-Sub-Nutzung**.
13. **Battlepass (#33) + Challenges (#34)** — hängt an Stats/Season/Economy + viele Subsysteme.
14. **Cosmetics-Hub (#66) + Prefix-Registry (#67)** — hängt an UserData/Permissions; YAML/DB-Besitz
    vereinheitlichen.

### Phase 4 — Moderation/Social (Pub-Sub-Kandidaten)
15. **Reports (#47) + Chat-Mute (#83)** — **prüfen ob Punishments-Backend** (s. Frage 7); sonst
    persistent + netzweit neu. **Support (#48), Teamchat (#82), Polls (#50), Friends-Presence (#78)**
    — Pub-Sub-Features, gut gebündelt anzugehen.

### Phase 5 — Gameplay-lastige / Lieblings-Features (spät, bleiben großteils Client)
16. **AMS (#42–#44)** — Kern-Eco-Engine, komplex; Output an Economy, Logik bleibt Plugin.
17. **Mine-Gamemode (#59–#65)** — eigener großer Block; MineData→Backend, Rest Client.
18. **Casino (#18–#27)**, **Duels (#51–#54)**, **LMS (#55)**, **Arena (#56)**, **`/kit` #58** —
    überwiegend lokale Spielmechanik; Backend nur für Config/Wetten/Cooldowns.
19. **Base-Protection (#45)** — WG-gebunden, spät.
20. **Seasonal-Events (#73/#74)** — **als gemeinsames Framework mergen** statt Christmas+Easter doppelt.
21. **Fake-Entities/Cosmetic-Render/Scoreboard (#69–#72)**, **Staff-Utility-Bundle (#91)**,
    seasonale/Deko-Reste — zuletzt, da überwiegend reines Client-Gameplay/Render.

**Begründungs-Kurzform:** Phase 0 validiert die Pipeline risikolos. UserData + Economy sind die
Foundation, an der praktisch alle datenzentrischen Features hängen — sie müssen früh und sauber
sitzen (inkl. Cross-Server-Autorität, die heute fehlt). Danach datenzentrische Features in
Abhängigkeits-Reihenfolge. Gameplay-Schwergewichte (AMS/Mine/Casino/Duel) kommen spät, weil sie
großteils im Plugin bleiben und nur dünn ans Backend andocken.

---

## 2. Evtl. schon durch Economy / Punishments abgedeckt (nicht doppelt bauen)

**Durch ein neues Economy-Backend wahrscheinlich abgedeckt:**
- **#2–#5 Währungen** (Money, Gems, Chips, Event-Points) — Kernkandidat. Frage: modelliert das neue
  Economy `CurrencyType` generisch (dann sind Chips/Points nur weitere Typen)?
- **#6 Transaction/Kaufbestätigung** — Logik wird Economy-REST-Call; nur das Menü bleibt Client.
- **#7 Eco-Statistik** — gehört in Economy-Reporting/Dashboard.
- Geld-Legs vieler Features (**#17 Markt, #24/#26 Jackpot/Lotto, #36 Goal, #41 Liga-Reward,
  #45 Base-Kosten, #58 Buyable-Kit, #85/#86 Vote-Rewards, #87 Outpost**) — nutzen künftig die
  Economy-API statt eigener Money-Mutationen.
- **#14 Clan-Coins, #60/#64 Mine-Minepoints/Upgradepoints, #27 Kopfgeld** — *könnten* als weitere
  Economy-Currencies modelliert werden (offen, s. Fragen 4 & 9).

**Durch ein neues Punishments-Backend wahrscheinlich abgedeckt:**
- **#47 Reports** — Input-Seite einer Moderations-Pipeline; heute RAM-only/Single-Server.
- **#83 Globalmute** — Mute gehört klar zu Punishments (persistent + netzweit).

**NICHT betroffen (Klarstellung):**
- **#43 AMS-Captcha** — **erzeugt KEINE Bans/Strafen** (Fehlschlag bricht nur die Abholung ab).
  → Kein Punishments-Overlap, nichts zu migrieren außer der lokalen Mechanik.

---

## 3. Features, die in die neue Welt evtl. nicht (mehr) ins Menü passen — als Fragen an dich

Diese als **Rückfragen**, nicht eigenmächtig entschieden:

- **F2 — Webinterface statt In-Game-Menü?** Folgende sind reine Read-Models / Config-CRUD und
  passen evtl. besser ins Webinterface: **Ranking/Leaderboards (#39)**, **Eco-Statistik (#7)**,
  **Crate-Designer (#29)**, **Crate-Drops-Config (#32)**, **Battlepass/Event-Pass-Tier-Authoring
  (#33/#35)**, **Challenge-Katalog (#34)**, **Liga-Reward-Tabellen (#41)**, **Duel-Maps (#53)**,
  **Serverteam-Liste (#90)**, **Vote-/Discord-Links + Warp-Namen (Config)**, **Polls-Admin (#50)**,
  **AMS-/Audit-History (#44)**, **Mine-/Kontakt-Hilfetexte (#65/#49)**. Welche davon willst du
  bewusst im Spiel-Menü behalten?

(Weitere Detail-Fragen siehe unten.)

---

## Offene Rückfragen (vor Migration klären)

1. **Online-Shop / `onlinegems`-`onlinemoney`:** Es gibt Admin-Varianten, die in dieselbe
   `Currency`-Tabelle schreiben — gibt es ein externes Webshop-System, das künftig Source of Truth
   für Käufe ist? Wie spielt das mit dem Economy-Backend zusammen?
2. **Leaderboards/Statistiken:** ins Webinterface oder im In-Game-Menü behalten? (#39, #7)
3. **Cross-Server-Autorität:** Läuft das neue System wirklich Multi-Server? Falls ja, ist das
   RAM-Cache+Flush-Modell (#12) durch Backend-als-SoT zu ersetzen — und Enderchest/Clan-Chest
   (#11/#15) brauchen ein **Item-Dupe-Locking-Konzept**. Bestätigst du Multi-Server als Ziel?
4. **Clan-Coins (#14):** eigene Economy-Currency im Backend oder Clan-internes Feld?
5. **Crate-Designer (#29):** In-Game-Editor behalten oder durch Webinterface-CRUD ersetzen?
6. **Event-Pass (#35):** Der Manager ist ein **leerer Stub**. Soll der im neuen System frisch
   gebaut werden (Backend-first), und ist er konzeptuell = "Battlepass auf Event skaliert"?
7. **Punishments-Scope:** Deckt das neue Punishments-Backend **Reports (#47)** und **Mute (#83)**
   ab? Sollen wir Reports/Support dorthin verlagern (persistent + netzweite Staff-Notify)?
8. **Party (#79):** Datenmodell steckt in `inventory/PartyInventory` (nicht analysiert). Eigenes
   Feature oder Teil von Clans/Social? Soll ich es noch tiefer ansehen?
9. **Mine-Währungen (#60/#64):** Minepoints (XP, nicht spendbar) vs. Upgradepoints (spendbar).
   Upgradepoints als Economy-Currency modellieren? Naming ("MineXP" UI vs "Upgradepoints" DB) klären.
10. **Seasonal-Events (#73/#74):** Christmas & Easter sind Code-Klone. In **ein** parametrisiertes
    Framework mergen (empfohlen) — einverstanden?
11. **`de.byrizon` / `de.teamhardcore`-Pakete & `Manager_Sumo`-Import in Duel:** Das wirkt wie
    Altcode/Legacy. Soll ich separat klären, was davon noch aktiv ist (Kandidat zum Streichen)?
12. **Lotto (#26):** Sehr fragil (RAM, Restart-Verlust), getrennt von Casino-Infra. In Jackpot-System
    integrieren oder als eigenes Backend-Feature sauber neu?

---

*Stand: Bestandsaufnahme. Bitte Review — nichts wird migriert, bevor die Reihenfolge & die offenen
Fragen geklärt sind.*
