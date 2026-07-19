/**
 * The Task 26 multiplayer surfaces ({@code Dist.CLIENT} only): the torrent-world list + search on
 * {@code JoinMultiplayerScreen}, the "torrent hosting" + password option on
 * {@code CreateWorldScreen}, and the tracker adapter feeding the Minecraft-free
 * {@code TorrentWorldListView}. Event hooks over mixins — {@code nodera.mixins.json} stays empty.
 * Reachable only via {@code NoderaClientMod}; a dedicated server never classloads this package.
 */
package dev.nodera.mod.client.multiplayer;
