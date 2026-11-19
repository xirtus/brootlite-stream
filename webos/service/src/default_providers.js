'use strict';

/**
 * Clean Open-Access Default Provider Configuration (Public Edition).
 *
 * Ships neutral BitTorrent trackers and public-domain / Creative Commons media
 * sources out-of-the-box. External `provider_pack.json` packs can be imported
 * at runtime via `Passport -> + Install / Import Pack` or `unlock-runtime.sh webos`.
 */
module.exports = {
  schemaVersion: 1,
  edition: 'public',
  name: 'BrootLite Open Media Defaults',
  trackers: [
    'udp://tracker.opentrackr.org:1337/announce',
    'udp://open.stealth.si:80/announce',
    'udp://tracker.torrent.eu.org:451/announce',
    'udp://exodus.desync.com:6969/announce',
    'udp://open.demonii.com:1337/announce',
    'udp://tracker.openbittorrent.com:6969/announce',
    'wss://tracker.openwebtorrent.com'
  ],
  stremioAddons: [],
  swarmIndexers: [
    {
      id: 'internet-archive-rss',
      name: 'Internet Archive',
      kind: 'rss',
      searchUrlTemplate:
        'https://archive.org/advancedsearch.php?q={query}+AND+mediatype%3Amovies+AND+format%3A%22Archive+BitTorrent%22&fl[]=identifier,title&rows=30&output=rss',
      defaultCategory: 'movies',
      tabGroup: 'public_domain',
      adult: false,
      imdbFallback: false
    }
  ],
  featuredFeeds: [
    {
      key: 'archive_movies',
      label: 'Public Domain · Feature Films',
      kind: 'rss',
      url: 'https://archive.org/advancedsearch.php?q=collection%3Afeature_films+AND+format%3A%22Archive+BitTorrent%22&sort[]=downloads+desc&rows=30&output=rss',
      adult: false
    },
    {
      key: 'archive_scifi',
      label: 'Public Domain · Classic Sci-Fi',
      kind: 'rss',
      url: 'https://archive.org/advancedsearch.php?q=collection%3ASciFi_Horror+AND+format%3A%22Archive+BitTorrent%22&sort[]=downloads+desc&rows=30&output=rss',
      adult: false
    },
    {
      key: 'archive_animation',
      label: 'Public Domain · Classic Animation',
      kind: 'rss',
      url: 'https://archive.org/advancedsearch.php?q=collection%3Aanimationandcartoons+AND+format%3A%22Archive+BitTorrent%22&sort[]=downloads+desc&rows=30&output=rss',
      adult: false
    }
  ],
  directSources: [
    {
      id: 'archive-cinema',
      name: 'Internet Archive · Feature Cinema',
      kind: 'internet_archive',
      collection: 'feature_films',
      adult: false
    },
    {
      id: 'archive-scifi',
      name: 'Internet Archive · Sci-Fi & Horror',
      kind: 'internet_archive',
      collection: 'SciFi_Horror',
      adult: false
    },
    {
      id: 'archive-animation',
      name: 'Internet Archive · Classic Animation',
      kind: 'internet_archive',
      collection: 'animationandcartoons',
      adult: false
    }
  ],
  kodiRepositories: []
};
