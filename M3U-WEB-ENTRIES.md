# Web entries in M3U playlists (`x-web`)

An entry whose link is a **page**, not a stream. On play, StreamBD opens the page in its built-in browser
(you complete any "Verify you are human" check; otherwise the page stays hidden), watches the page's requests,
and plays the first https `.m3u8` it sees.

```
#EXTM3U
#EXTINF:-1 group-title="Sports" tvg-logo="https://x/logo.png" x-web="1" x-web-hosts="player.example.net" x-stream-referer="https://player.example.net/",Channel Name
https://mysite.com/player.php?id=abc|Referer=https://mysite.com/&User-Agent=Mozilla/5.0
```

| Where | Meaning |
|---|---|
| `x-web="1"` | marks a web entry (`1`, `true`, `yes`, `on`) |
| `\|Header=Value&H2=V2` after the link, and `x-web-<Header>` | headers of the **page** request (`x-web-referer`, `x-web-user-agent`, ...). `x-web-*` wins over `\|` |
| `x-web-hosts="a.com,b.net"` | extra hosts the page may load/navigate to (the page's own site is always allowed) |
| `x-web-timeout="300"` | seconds to wait (20-600, default 300) |
| `x-stream-<Header>` | headers forced onto the **captured stream** request: `x-stream-referer`, `x-stream-origin`, `x-stream-user-agent`, `x-stream-cookie`, `x-stream-authorization`, ... |

Header names: the part after `x-web-` / `x-stream-` with each hyphen-part capitalised (`user-agent` -> `User-Agent`; `referrer` -> `Referer`).

Which Referer/Origin does the player use? By default the ones the browser sent for the `.m3u8` request (often the embed
site, not your page). If the stream answers 403/404, set `x-stream-referer` / `x-stream-origin` explicitly.

Rules: https pages only; the browser can only visit the page's own site and `x-web-hosts`; popups are blocked; the playlist
never sees cookies. Only open pages from playlists you trust.
