# PacketScreen

Virtual image and video screens for Minecraft Java Edition servers, drawn **only with packets**.
No block, map item, armor stand, item frame or any other entity is created in the world: every
screen exists only in the clients of the players that are close to it.

- Images from URLs (PNG, JPEG, GIF, BMP, WebP) and videos from direct file URLs (MP4, MOV, WebM, MKV, AVI, FLV, OGG, MPEG-TS, MPEG-PS, animated GIF).
- Animated GIF and WebP play in a loop on image screens (`create`), with their own frame timing and without FFmpeg.
- Any size in blocks, including fractional sizes such as `4 x 2.25`, changeable at any time.
- `FIT`, `FILL` and `STRETCH` modes, preserved or free aspect ratio, independent processing resolution.
- Videos with play, pause, resume, stop, restart, loop and a shared clock for every viewer.
- Fixed wall screens have a solid black back (the colour is configurable), so a player walking behind one sees
  the back of a monitor instead of seeing through it.
- Hologram mode: the screen turns to face each player, independently for every viewer.
- Optional web panel (Javalin) where administrators upload images and videos through a temporary, single-use link.
- English and Brazilian Portuguese messages, everything player-facing is in `messages.yml`.
- Spigot, Paper and Folia, Minecraft 1.8.8 to 26.3.

## How it works

Each screen is a grid of virtual item frames (glow item frames on 1.17+) holding filled maps. The plugin
sends the spawn, metadata, map data and destroy packets with [PacketEvents](https://github.com/retrooper/packetevents),
using reserved entity ids and map ids that never collide with the real ones. Images are converted to the
map palette of the server version (36, 52, 59 or 62 base colours) with dithering, split into 128 x 128
tiles and sent only to players in range. Videos are decoded by FFmpeg into raw frames on worker threads;
only the pixels that changed since the previous frame are sent, within a bandwidth budget per player.

Three sizes are kept apart:

| Concept | Example | Command |
|---|---|---|
| Source resolution | the file is 1920 x 1080 | read from the file |
| Processing resolution | frames are processed at 512 x 288 pixels | `/packetscreen resolution` |
| Virtual size | the screen is 4 x 2.25 blocks | `create`, `resize`, `scale` |

The processing resolution changes only the detail and the processing cost, never the size or the position
of the screen. Each block is 128 map pixels, so a 4 x 2.25 screen has 512 x 288 visible pixels; the automatic
processing resolution matches that for images and uses half of it for videos (`processing.video-resolution-scale`).

## Requirements

| Item | Version |
|---|---|
| Server | Spigot, Paper or Folia 1.8.8 - 26.3 |
| Java | 8 or newer (whatever your server version requires) |
| [PacketEvents](https://modrinth.com/plugin/packetevents) | 2.14.0 or newer (installed as a plugin) |
| FFmpeg | any recent build, only for video screens |
| Java 17+ | only for the optional web panel |

## Installation

1. Install PacketEvents (`packetevents-spigot-2.14.0.jar` or newer) in `plugins/`.
2. Copy `PacketScreen-1.0.0.jar` to `plugins/` and start the server.
3. For videos, install FFmpeg and make `ffmpeg` available in the `PATH`, or set the full path in `video.ffmpeg-path`.
4. Adjust `plugins/PacketScreen/config.yml` and run `/packetscreen reload`.

The plugin never grants anything to regular players: every permission defaults to operators only.

## Building

Requires JDK 17 or newer and Maven 3.9+ (the plugin core is compiled for Java 8, the web panel for Java 17).

```bash
mvn package
```

The jar is written to `target/PacketScreen-1.0.0.jar`. `mvn package` also runs the unit tests (geometry,
fit modes, palette, frame processing, HTTP security, uploads, sessions and the web panel running on Javalin).

## Quick start

Look at a wall and run:

```text
/packetscreen create lobby https://example.com/banner.png 4 2.25
```

The block you are looking at becomes the bottom-left block of the screen, which grows to the right and up.
Floor and ceiling screens (1.13+) are oriented so that the image faces the player who creates them.

```text
/packetscreen resize lobby 6 3 FILL
/packetscreen aspect lobby free
/packetscreen resize lobby 6 3 STRETCH
/packetscreen scale lobby 0.5
/packetscreen resolution lobby 128
/packetscreen resolution lobby auto
/packetscreen rotate lobby 90
/packetscreen move lobby 100 70 -20 north world
/packetscreen range lobby 64

/packetscreen video cinema https://example.com/trailer.mp4 8 4.5
/packetscreen fps cinema 15
/packetscreen pause cinema
/packetscreen play cinema
/packetscreen loop cinema false
/packetscreen restart cinema
/packetscreen stop cinema
/packetscreen url cinema https://example.com/other.webm
/packetscreen info cinema
```

Coordinates can follow the URL with or without a size: `/packetscreen create hall <url> ~ ~ ~` or
`/packetscreen create hall <url> 4 3 <x> <y> <z> [facing] [world]` (the console must give the coordinates and the
world). Two screens facing the same way cannot share blocks: creating, moving, rotating, resizing or scaling into
another screen is refused with the name of the screen in the way. A Google Images result link is replaced by the
image it points to, and a video given to `create` (or an image given to `video`) is answered with the right command.

## Commands

| Command | Permission | Description |
|---|---|---|
| `/packetscreen create <name> <url> [width height] [x y z [facing] [world]]` | `packetscreen.create` | Image screen (size and coordinates are both optional) |
| `/packetscreen video <name> <url> [width height] [x y z [facing] [world]]` | `packetscreen.video` | Video screen |
| `/packetscreen url <name> <url>` | `packetscreen.create` | Replace the source without recreating the screen |
| `/packetscreen refresh <name>` | `packetscreen.create` | Download the source again, bypassing the cache |
| `/packetscreen resize <name> <width> <height> [FIT\|FILL\|STRETCH]` | `packetscreen.resize` | Change size and fit mode |
| `/packetscreen scale <name> <factor>` | `packetscreen.resize` | Multiply the current size |
| `/packetscreen aspect <name> <preserve\|free>` | `packetscreen.resize` | Keep the source aspect ratio or not |
| `/packetscreen resolution <name> <width> [height]` or `auto` | `packetscreen.resize` | Processing resolution (height follows the screen proportions when omitted) |
| `/packetscreen fps <name> <value>` | `packetscreen.video` | Playback rate |
| `/packetscreen play\|pause\|stop\|restart <name>` | `packetscreen.video` | Playback control |
| `/packetscreen loop <name> <true\|false>` | `packetscreen.video` | Loop the video |
| `/packetscreen move <name> [x y z [facing] [world]]` | `packetscreen.move` | Move to the looked block or to coordinates |
| `/packetscreen rotate <name> <0\|90\|180\|270>` | `packetscreen.move` | Rotate the image clockwise |
| `/packetscreen range <name> <blocks>` | `packetscreen.move` | View distance |
| `/packetscreen hologram <name> <true\|false>` | `packetscreen.move` | The screen turns to face each player |
| `/packetscreen remove <name>` | `packetscreen.remove` | Remove and free every resource |
| `/packetscreen list` | `packetscreen.list` | List screens |
| `/packetscreen info <name>` | `packetscreen.list` | Type, URL, virtual size, source and processing resolution, fit, FPS, state |
| `/packetscreen web [revoke]` | `packetscreen.web` | Temporary link to the web panel / revoke every link and session |
| `/packetscreen reload` | `packetscreen.reload` | Reload config.yml and the messages |

Alias: `/pscreen`. `packetscreen.admin` grants everything. The permission of each subcommand can be changed in
`command-permissions` in `config.yml`.

## Web panel

The panel lets administrators upload files and show them on screens from a browser.

1. Set `web.enabled: true`, choose `web.port` (open it in the firewall or ask your host) and, if needed, `web.host`
   (the IP or domain written in the links). Run `/packetscreen reload`.
2. In game or in the console run `/packetscreen web`. The link is valid for `web.token-minutes` (10 by default)
   and works only once. Opening it starts a session of `web.session-minutes` (30 by default).
3. Upload images and videos, show them on existing screens or create new screens. The creation form starts at the
   position where the link was generated and moves next to each screen created, so screens do not overlap.
   Uploaded files can also be used in commands as `upload:<id>`, for example
   `/packetscreen create hall upload:banner-1a2b3c.png 4 3`.

Security model:

- No public page works without a link generated by someone with `packetscreen.web`; the panel inherits that
  person's create, video and remove permissions.
- The token travels in the URL fragment (`#...`), which the browser never sends to the server or to proxies; the
  page exchanges it for an `HttpOnly`, `SameSite=Strict` session cookie and removes it from the address bar.
- Every change requires the session's CSRF token and a same-origin request. Logins are rate limited per address.
- Uploads are validated by their real content (file signature, decodable image or video, size and resolution
  limits, storage quota) and stored under random names in `plugins/PacketScreen/uploads`, outside any public folder.
- Strict Content-Security-Policy, `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`, `Cache-Control: no-store`.
- The panel speaks HTTP. On a public server put it behind an HTTPS reverse proxy (set `web.bind-address: 127.0.0.1`
  and `web.public-url: https://panel.example.com`) or keep the port restricted.
- `/packetscreen web revoke` invalidates every link and session immediately.

## Configuration highlights

| Path | Default | Meaning |
|---|---|---|
| `defaults.*` | 3 x 2, FIT, preserve, 48 blocks, 10 FPS | values for new screens |
| `limits.max-screens` / `max-concurrent-videos` | 20 / 2 | global limits |
| `limits.max-width` / `max-height` | 10 / 10 | size limits in blocks |
| `limits.max-fps` / `max-processing-*` | 20 / 1280 | processing limits |
| `image.max-file-size-kb` / `max-source-*` | 10 MB / 8192 px / 32 MP | image limits, checked before decoding |
| `image.max-animation-frames` / `max-animation-memory-mb` | 1000 / 32 MB | animated GIF and WebP limits; longer animations skip frames to fit the memory |
| `video.*` | 100 MB, 600 s, 1920 x 1080 | video limits, FFmpeg path, temp folder, autoplay |
| `cache.*` | 32 MB, 30 min | in-memory cache of downloaded images |
| `http.*` | 5 s / 10 s / 30 s, 3 redirects | timeouts and redirects |
| `security.*` | private addresses blocked, ports 80 and 443 | SSRF protection, host allow and block lists |
| `network.*` | 1 MB/s and 600 packets/s per player, 8 MB/s total | packet budget |
| `rendering.*` | AUTO, invisible frames, Floyd-Steinberg for images, ordered for video, black back | rendering strategy |
| `errors.*` | KEEP_LAST, 1 retry | what screens show when content fails |
| `web.*` | disabled, port 8125 | web panel |

Every value is validated on load; invalid values fall back to the default with a console warning.

## Rendering per version

| Server version | Frame | Transparency and fractional sizes | Lighting |
|---|---|---|---|
| 1.8.8 - 1.15.2 | item frame, border visible | transparent pixels show the frame backing | follows world light |
| 1.16 - 1.16.5 | invisible item frame | fully transparent | follows world light |
| 1.17 - 26.3 | invisible glow item frame | fully transparent | full brightness |

`rendering.frame-type: ITEM_FRAME` forces regular frames on 1.17+ (the screen then follows the world lighting).
Floor and ceiling screens require 1.13+. With ViaVersion, clients older than the server are translated by Via,
including the map colours.

## Security

- URLs must be http or https, without credentials, on an allowed port; each redirect is validated again.
- The resolved address is checked against loopback, private, link-local, CGNAT, multicast, reserved, IPv4-mapped,
  NAT64 and 6to4 ranges, and the connection is made to that same address (no DNS rebinding window).
- Downloads have connect, read and total timeouts (the total limit closes the connection even while headers or
  the TLS handshake trickle in) and a size limit; HTML pages are rejected with a clear message.
- Images are checked for size and resolution before being decoded and are decoded with subsampling.
- Videos are downloaded by the plugin (never by FFmpeg), FFmpeg is restricted to the local file protocol with the
  container forced from the file signature (playlists and other protocols are never opened), runs without a shell
  with `-nostdin` and limited threads, has its duration and resolution checked, and is killed on stop, removal,
  errors and shutdown.
- Screen names, coordinates, sizes, FPS and resolutions are validated; a cooldown limits heavy commands.

## Limitations

- Maps have at most 248 colours (fewer on old versions); images are dithered and fine gradients become grainy.
- Maps are 128 x 128 pixels per block. A screen is always a whole grid of maps: a 4 x 2.25 screen uses a 4 x 3
  grid and the extra area is transparent, which is invisible on 1.16+ but shows the frame backing on 1.8 - 1.15.
- Item frames intercept clicks: players cannot break or use blocks right behind a screen.
- Item frames can only face north, south, east, west, up or down, so hologram screens turn in 90 degree steps
  (the side closest to the player, with a small dead zone so they do not flicker on the diagonals); floor and
  ceiling holograms keep the image upright for each player. A smooth 360 degree rotation is not possible with maps.
- The solid back exists only on fixed wall screens. It uses one extra virtual frame per map (one shared black map
  for whole tiles), and it is left out where another screen is mounted back to back on the same blocks.
- Map ids `rendering.map-id-min` to `map-id-max` are reserved (24000 - 32767 by default). Real maps with those ids
  would show screen content, so keep the range above the ids used by your world.
- Videos have no sound, are decoded on the server CPU and cost bandwidth per viewer: every viewer receives the
  changed pixels of every frame. Keep video screens small and the FPS moderate on busy servers.
- Animated images always loop; `play`, `pause`, `stop`, `restart`, `loop` and `fps` are video commands. Every frame
  is converted to maps when the image loads (ordered dithering, which does not shimmer between frames), playback is
  limited to `limits.max-fps` updates per second, and animations that do not fit in `image.max-animation-memory-mb`
  keep fewer frames, with the same total duration.
- Only direct media files work. Pages such as YouTube, social networks or streaming services are rejected
  (Google Images result links are the exception: the image they point to is used).
- Minecraft limits chat input to 256 characters, so very long URLs cannot be typed in game; upload the file through
  the web panel or use a shorter link.
- Before the first playback the plugin does not know the video aspect ratio; with `preserve` the screen size is
  adjusted when the video starts.
- The web panel requires Java 17+ and speaks plain HTTP (use a TLS reverse proxy on public servers).
- PacketEvents decides the protocol encoding; PacketScreen supports the versions supported by the installed
  PacketEvents (2.14.0 supports up to 26.3).

## Tests

See `TESTING.md` for what was executed, on which versions and with which results.

## License notices

Bundled libraries and their licenses are listed in `THIRD-PARTY-NOTICES.txt` (also inside the jar).
