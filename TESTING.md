# Testing

This file records what was actually executed. Nothing listed under "not tested" should be read as verified.

Environment: Windows 11 (10.0.26300), JDK 21 for the build, Temurin 8 and 17, Oracle JDK 21 and 25 for the
servers, FFmpeg 9.0 (gyan.dev full build), PacketEvents 2.14.0, ViaVersion and ViaBackwards 5.12.1.

## 1. Unit tests (`mvn package`)

87 tests, all passing:

| Area | What is checked |
|---|---|
| `ScreenGeometryTest` | FIT, FILL and STRETCH rectangles, preserved aspect (1920x1080 on 4 x 2.25), square source on a rectangular screen, vertical video on a horizontal screen, processing resolution independent of the virtual size, clamping, automatic video resolution |
| `ScreenLayoutTest` | block positions for every facing, rotation, floor and ceiling orientation, anchor at the bottom-left, yaw to facing, footprints used by the overlap check, hologram turns (wall and floor) and the side chosen for a viewer without flickering on the diagonals |
| `BackFaceTest` | solid back maps: one shared map for whole tiles, partial maps cut to fractional sizes and mirrored for the viewer behind, tiles hidden by a screen mounted back to back, reversed rotation, reuse |
| `MapPaletteTest` | 36 / 52 / 59 / 62 base colours per protocol, legacy clients never receive unknown colours, lookup table close to the exact search, flat colours (black backs, stopped videos) map to neutral black |
| `FrameProcessorTest` | resampling, transparency, bottom-left alignment of fractional sizes, dithering, video frames limited to the destination rectangle, letterbox |
| `RenderedFrameTest` | delta rectangles, unchanged tiles keep their version, full tiles for large changes |
| `AnimationDecoderTest` | animated GIF frames composited with offsets and "restore to background", GIF delays (including 0 treated as 100 ms), animated WebP partial frames drawn on the canvas, still images report one frame, frame limit |
| `HttpFetcherTest` | plain, chunked and gzip bodies, redirects (followed, limited and validated per hop), size limits, HTML rejection, HTTP errors, private address blocking, URL validation, internal IPv4 and IPv6 ranges, headers and bodies trickled past the total timeout, silent servers, refused connections, Google Images result links |
| `ImageLoaderTest` | PNG, JPEG, GIF, BMP and WebP (lossless and lossy) detection and decoding, resolution limits, subsampling, videos given to image screens and images given to video screens |
| `UploadStoreTest` | safe ids from hostile names, signature validation, size, quota, incomplete uploads, validator, container detection |
| `WebAccessTest` | single-use tokens, expiry, revocation, rate limit, constant-time comparison |
| `JsonTest` | escaping and strict parsing |
| `JavalinPanelTest` | the real Javalin panel in process: security headers, sessions, hardened cookie, CSRF and origin checks, uploads, ranges, deletion, permissions, rate limit |
| `SettingsTest` | the bundled config loads without warnings, invalid values fall back |

## 2. Integration tests on real servers

A headless client ([minecraft-protocol](https://github.com/PrismarineJS/node-minecraft-protocol)) joined each
server, the screens were created from the console, and the client recorded every spawn, metadata, map and destroy
packet. The images were rebuilt from the received map data (`shots/` in the test folder) and inspected.

Steps per server (27, 26 on Java 8): plugin enabled; image with preserved aspect; solid black back behind the fixed
screen (12 back frames facing the other way, 8 solid and 4 cut to the 2.25 block height); hologram screen turning to
face the client moved south, east, north and west of it, without back frames, and back to a fixed screen with its
back; FIT, FILL and STRETCH at 4 x 3; processing resolution 64 x 48 with the same virtual size; scale 0.5 rebuilding
the grid; rotation 90; floor screen (1.13+) or the compatibility message (older); transparent PNG, WebP and JPEG;
invalid sources (web page, 64 MB file, HTTP 404, redirect, a server that trickles bytes past the total timeout, ftp);
animated GIF and animated WebP served over HTTP to `create`, with the picture changing while the client watches;
video playback at 10 FPS; pause, resume, stop, play and restart; three viewers in sync and a viewer leaving and
rejoining during playback; resize while playing and the end of a non-looping video; removal destroying every frame
and back frame; a real item frame summoned as a positive control for the world check; no real item frame in the
world; web panel link, login, upload and screen creation (Java 17+); persistence across a restart; no PacketScreen
exception in the log; no FFmpeg process left after stop.

Results of the final build (the jar in `target/`), every server with the same jar:

| Server | Java | Headless client | Result | Rendering reported by the plugin | Video (10 FPS, 4 x 2.25) | Animated GIF / WebP (20 frames, 2 x 2) |
|---|---|---|---|---|---|---|
| Paper 1.8.8 | 8 | 1.8.8 | 26/26 | item frames | 44 map packets/s | 60 / 60 map packets in 1.5 s |
| Paper 1.9.4 | 8 | 1.9.4 | 26/26 | item frames | 42.7 map packets/s | 64 / 60 |
| Paper 1.12.2 | 8 | 1.12.2 | 26/26 | item frames | 44 map packets/s | 60 / 60 |
| Paper 1.13.2 | 8 | 1.13.2 | 26/26 | item frames | 42.7 map packets/s | 60 / 64 |
| Paper 1.16.5 | 8 | 1.16.5 | 26/26 | invisible item frames | 44.7 map packets/s | 60 / 60 |
| Paper 1.17.1 | 17 | 1.17.1 | 27/27 | invisible glow item frames | 43.7 map packets/s | 60 / 52 |
| Paper 1.20.4 | 21 | 1.20.4 | 27/27 | invisible glow item frames | 44 map packets/s | 60 / 60 |
| Paper 1.21.4 | 21 | 1.21.4 | 27/27 | invisible glow item frames | 42.7 map packets/s | 60 / 60 |
| Paper 1.21.11 | 21 | 1.21.11 | 27/27 | invisible glow item frames | 44 map packets/s | 64 / 60 |
| Paper 26.1.2 | 25 | 26.1 | 27/27 | invisible glow item frames | 45.3 map packets/s | 60 / 60 |
| Paper 26.3 | 25 | 26.1 through ViaBackwards | 27/27 | invisible glow item frames | 42.7 map packets/s | 60 / 60 |
| Folia 26.2 | 25 | 26.1 through ViaBackwards | 27/27 | invisible glow item frames, Folia schedulers | 44 map packets/s | 60 / 60 |
| Spigot 26.2 (BuildTools) | 25 | 26.1 through ViaBackwards | 27/27 | invisible glow item frames | 44 map packets/s | 60 / 60 |

The machine was shut down while the run was on Paper 1.21.11, so that server and the four after it were run again
afterwards with the same jar; the table shows the complete runs. The back colour was palette index 119 (neutral
black) on every version.

Java 8 servers skip the web panel step (the panel needs Java 17). The headless client has no 26.2 or 26.3
protocol yet, so those servers were exercised through ViaBackwards: the plugin encodes the 26.2 / 26.3 packets
(PacketEvents, protocol 776 / 777) and ViaBackwards translates them for the 26.1 client, which then reported the
expected entities, metadata and map contents.

About 43 map packets per second for a 12-map video means that, on average, only about a third of the maps change
between two frames of the test clip: unchanged maps are not resent and changed maps carry only the changed rectangle.
The animated test images change every pixel on every frame, so all 4 maps are resent at 10 frames per second.

World check: on 1.13+ `execute if entity @e[type=minecraft:item_frame]` (and `glow_item_frame` on 1.17+) reported no
entity after every scenario. On 1.8-1.12 the console command gives no conclusive output, so the client counts every
item frame the server spawns with a non-virtual id; a real item frame summoned next to the client is detected by
that counter (positive control), and none appeared during the scenarios. The only world access in the source is a
read-only `getType()` used to find the block a player is looking at.

Tab completion (`bot/tabcheck.js`): 58 partial command lines covering every subcommand and argument position were
sent to Paper 1.12.2, 1.21.11, 26.3 and Folia 26.2 (and to 1.8.8, 1.13.2, 26.1.2 and Spigot 26.2 before the last
changes); no server logged a completion error. Servers on 1.13+ do not answer requests without suggestions, which is
why they answered 42 of 58. The same check against the build before the fix reproduced the error reported in the
manual session (9 completion errors).

Console checks of the final build: `create <name> <url> x y z facing world` without a size; a second screen on the
same blocks refused on create, move and resize with the name of the screen in the way, while the same position with
another facing is accepted; `create` with an MP4 and `video` with a PNG answered with the right command;
`resolution <name> 128` on a 4 x 2.25 screen set 128 x 72; a Google Images result link stored and loaded as the image
it points to; on Folia 26.2, a giphy animated WebP (360 x 200, 132 frames) and a klipy GIF (480 x 480, 114 frames)
loaded as animations, and sample frames decoded from both were checked visually.

## 3. Resources

`bot/memory.js` on Paper 26.1.2 with the final build (two headless viewers, three images, two videos and one
animated GIF playing): heap after GC 276 MB before, 283 MB while playing, 275 MB after removing every screen; the two
viewers received 955 and 952 map packets (13.1 MB of map data each; the counters were read a few milliseconds apart
while frames were still arriving); the video threads and both FFmpeg processes ended on removal and no FFmpeg
process was left after the server stopped. The download and worker pools keep their two threads each.

## 4. Real client

A real 26.2 client (the project owner's) was used for manual sessions on Folia 26.2: uploads through the panel
(a 4096 x 2304 PNG and an MP4), scale, resolution, rotate, aspect, range, pause, and animated GIF and WebP links.
The sessions led to the overlap check, the coordinates-first `create` form, the single-number `resolution`, the
Google Images link handling, the tab completion fix, the solid back, the hologram mode and native animated images.
In-game screenshots for the resource page were not taken; the web panel screenshots in `screenshots/` were taken
with a headless browser at 1440 px and 390 px wide.

## 5. Not tested

- Spigot builds other than 26.2, and Folia versions other than 26.2.
- Linux and macOS servers (only Windows was available).
- Proxies (BungeeCord, Velocity) and ViaRewind (1.8 clients on newer servers).
- Large numbers of players: at most three simultaneous clients per screen.
- Long-running stability beyond the minutes each scenario takes.
- Real clients other than the 26.2 client of section 4; every other version was exercised only through the
  headless client. The hologram turns and the black back were checked through the packets the client received,
  not looked at in a real client by the author.
