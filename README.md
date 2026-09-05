# SpotyMC

Listen to Spotify directly inside Minecraft. SpotyMC is a Fabric mod that brings full Spotify Connect playback into the game, with an in-game HUD for controlling music, viewing lyrics, and browsing your queue — no alt-tabbing required.

## Features

- **Spotify Connect playback** — SpotyMC appears as a Spotify Connect device, powered by [librespot](https://github.com/librespot-org/librespot)
- **In-game HUD** — player controls, track info, and progress bar rendered directly in your Minecraft UI
- **Lyrics display** — synced lyrics with customizable color, font size, and format
- **Queue & search** — browse and click through your queue and search results without leaving the game
- **Hotkeys** — control playback with `Right Ctrl` + arrow keys, including volume ramping
- **Guided setup** — built-in installer handles the librespot backend and Spotify authentication, with clear on-screen instructions

## Requirements

- Minecraft (Fabric)
- [Fabric Loader](https://fabricmc.net/) + [Fabric API](https://modrinth.com/mod/fabric-api)
- A Spotify account (Premium required for playback, per Spotify's own restrictions)
- Your own Spotify Developer Client ID (set up is guided in-app — see [Setup](#setup))

## Setup

1. Install [Fabric Loader](https://fabricmc.net/) and [Fabric API](https://modrinth.com/mod/fabric-api) for your Minecraft version.
2. Download and install SpotyMC.
3. Launch Minecraft and open the SpotyMC settings screen.
4. Follow the in-game instructions to create a Spotify Developer app and enter your Client ID.
5. Authenticate with Spotify, and the mod will handle the rest — including installing the librespot backend on first run.

## Building from source

For development setup, see the [Fabric Documentation page](https://fabricmc.net/wiki/) related to the IDE you're using.

```bash
./gradlew build
```

## Credits

SpotyMC bundles precompiled binaries of [librespot](https://github.com/librespot-org/librespot) (MIT license) to handle Spotify Connect authentication and audio streaming. librespot's code is unmodified and runs as a backend process; all HUD, lyrics, queue, and UI features are built independently by SpotyMC.

## License

[Add your project's license here]

## Disclaimer

SpotyMC is an independent project and is not affiliated with, endorsed by, or sponsored by Spotify AB.
