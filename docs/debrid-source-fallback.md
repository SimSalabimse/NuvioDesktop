# Uncached debrid sources

Playing or downloading a TorBox-style debrid source that is not cached tries the next source instead of stopping on that failure. The same path is used for auto-play, a source picked from the list, the player source switch, and the next episode. A normal HTTP source is played as it is.

The next sources stay in the list order. A source already marked not cached is skipped. Up to three later sources are tried. The first one that opens is the one that plays. A resolve error does not walk that list. Auto-play can still move on to its own next pick; a manual pick shows the error.

When nothing else opens and the account can queue the torrent, a confirmation asks to add it. Confirm queues it without the cached-only limit. Dismiss leaves it uncached. The result is a short notice either way.

## Manual check

The packaged desktop app was not run here.

1. Connect a TorBox account under Settings, Debrid.
2. Open a title whose source list has an uncached debrid source followed by another playable source.
3. Auto-play that title, or pick the uncached source. Playback should continue on the later source.
4. Pick an uncached source when no later source opens. Confirm Add to Torbox. The notice should say it was added, or that TorBox did not accept it.
