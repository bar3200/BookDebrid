// ========== SEARCH MODULE ==========
import { state } from './state.js';
import { emit } from './event-bus.js';
import { showToast, escapeHtml, formatTime } from './utils.js';
import { $, $$, searchInput, searchClear, resultsSection, resultsContainer, detailView, detailInfo, detailTracks, backBtn, queueAllBtn, shuffleBtn, searchMoreBtn, searchMoreMenu, domState } from './dom.js';
import { showLoading, hideLoading, showError, showEmptyState } from './ui.js';
import { isInLibrary, toggleLibrary } from './data.js';

// ========== SEARCH ==========
let searchTimeout = null;
let activeSearchController = null;
let searchRequestId = 0;
let nativeSearchSequence = 0;
const nativeSearchRequests = new Map();

window.FreedifyAndroidSearch = {
    resolve(requestId, payload) {
        const pending = nativeSearchRequests.get(requestId);
        if (!pending) return;
        nativeSearchRequests.delete(requestId);
        pending.cleanup();
        pending.resolve(payload);
    },
    reject(requestId, message) {
        const pending = nativeSearchRequests.get(requestId);
        if (!pending) return;
        nativeSearchRequests.delete(requestId);
        pending.cleanup();
        pending.reject(new Error(message || 'AudiobookBay search failed'));
    },
};

function searchAudiobookBayWithAndroid(query, page, signal) {
    const bridge = window.FreedifyAndroid;
    if (typeof bridge?.searchAudiobookBay !== 'function') return null;

    const requestId = `abb-${Date.now()}-${++nativeSearchSequence}`;
    return new Promise((resolve, reject) => {
        const onAbort = () => {
            nativeSearchRequests.delete(requestId);
            bridge.cancelAudiobookBaySearch?.(requestId);
            reject(new DOMException('Search cancelled', 'AbortError'));
        };
        const cleanup = () => signal?.removeEventListener('abort', onAbort);
        nativeSearchRequests.set(requestId, { resolve, reject, cleanup });
        signal?.addEventListener('abort', onAbort, { once: true });
        if (signal?.aborted) {
            onAbort();
            return;
        }
        bridge.searchAudiobookBay(requestId, query, page);
    });
}

function rankAudiobookResults(results, query) {
    const tokens = String(query).toLowerCase().match(/[a-z0-9]+/g) || [];
    const normalizedQuery = tokens.join(' ');
    return [...(results || [])]
        .map((item, index) => {
            const title = String(item.title || '').toLowerCase().match(/[a-z0-9]+/g)?.join(' ') || '';
            const author = String(item.author || '').toLowerCase().match(/[a-z0-9]+/g)?.join(' ') || '';
            const description = String(item.description || '').toLowerCase().match(/[a-z0-9]+/g)?.join(' ') || '';
            const words = `${title} ${author} ${description}`.split(' ');
            const titleWords = title.split(' ');
            const authorWords = author.split(' ');
            const textHits = tokens.filter(token => words.includes(token)).length;
            const titleHits = tokens.filter(token => titleWords.includes(token)).length;
            const authorHits = tokens.filter(token => authorWords.includes(token)).length;
            let score = textHits * 10 + titleHits * 20 + authorHits * 20;
            if (normalizedQuery && (title.includes(normalizedQuery) || author.includes(normalizedQuery))) score += 100;
            if (tokens.length && (titleHits === tokens.length || authorHits === tokens.length)) score += 50;
            return { item, index, score };
        })
        .sort((a, b) => b.score - a.score || a.index - b.index)
        .map(entry => entry.item);
}
// Only search on Enter key press (not as-you-type to avoid rate limiting)

searchInput.addEventListener('input', (e) => {
    if (!e.target.value.trim()) {
        if (state.searchType === 'audiobook') emit('renderMyBooksView');
        else if (state.searchType === 'podcast') emit('renderMyPodcastsView');
        else showEmptyState();
    }
});

searchInput.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && !e.isComposing) {
        e.preventDefault();
        clearTimeout(searchTimeout);
        const query = searchInput.value.trim();
        if (query) {
            performSearch(query);
        }
        searchInput.blur();
    }
});

searchClear.addEventListener('click', () => {
    searchInput.value = '';
    if (state.searchType === 'audiobook') emit('renderMyBooksView');
    else if (state.searchType === 'podcast') emit('renderMyPodcastsView');
    else showEmptyState();
    searchInput.focus();
});

// Toggle Search More Menu
if (searchMoreBtn) {
    searchMoreBtn.addEventListener('click', (e) => {
        e.stopPropagation();
        searchMoreMenu.classList.toggle('hidden');
    });
}

// Close menu when clicking elsewhere
document.addEventListener('click', (e) => {
    if (searchMoreMenu && !searchMoreMenu.contains(e.target) && e.target !== searchMoreBtn) {
        searchMoreMenu.classList.add('hidden');
    }
});

// Search type selector
// Re-select all type buttons including new menu items
const allTypeBtns = document.querySelectorAll('.type-btn, .type-btn-menu');

allTypeBtns.forEach(btn => {
    btn.addEventListener('click', () => {
        if (btn.id === 'search-more-btn' || btn.id === 'ai-menu-btn') return; // Skip toggle and AI modal buttons

        allTypeBtns.forEach(b => b.classList.remove('active'));
        btn.classList.add('active');

        // If it's a menu item, highlight the "More" button too as a visual indicator
        if (btn.classList.contains('type-btn-menu')) {
            searchMoreBtn.classList.add('active');
            searchMoreMenu.classList.add('hidden'); // Close menu on selection
        }

        state.searchType = btn.dataset.type;
        searchInput.placeholder = state.searchType === 'audiobook'
            ? 'Search audiobooks or paste an AudiobookBay link'
            : state.searchType === 'podcast'
                ? 'Search podcasts'
                : 'Search music or paste a link';

        // Special types
        if (state.searchType === 'favorites') {
            emit('renderPlaylistsView');
            return;
        } else if (state.searchType === 'rec') {
            emit('renderRecommendations');
            return;
        } else if (state.searchType === 'podcast') {
            const query = searchInput.value.trim();
            if (query) {
                performSearch(query);
            } else {
                emit('renderMyPodcastsView');
            }
            return;
        } else if (state.searchType === 'audiobook') {
            const query = searchInput.value.trim();
            if (query) {
                performSearch(query);
            } else {
                emit('renderMyBooksView');
            }
            return;
        }

        const query = searchInput.value.trim();
        if (query) {
            performSearch(query);
        } else {
            // Show Jump Back In dashboard when switching to music search types
            showEmptyState();
        }
    });
});

// Sort Filter Removed

// Crossfade Toggle
const crossfadeCheckbox = $('#crossfade-checkbox');
if (crossfadeCheckbox) {
    // Initialize from state
    crossfadeCheckbox.checked = state.crossfadeEnabled;

    crossfadeCheckbox.addEventListener('change', () => {
        state.crossfadeEnabled = crossfadeCheckbox.checked;
        localStorage.setItem('freedify_crossfade', state.crossfadeEnabled);
        showToast(state.crossfadeEnabled ? 'Crossfade enabled' : 'Crossfade disabled');
    });
}

// ========== SEARCH & RESULTS ==========

export async function performSearch(query, append = false) {
    const requestedQuery = String(query || '').trim();
    if (!requestedQuery) return;

    const requestedType = state.searchType;
    if (!append) activeSearchController?.abort();
    const controller = new AbortController();
    activeSearchController = controller;
    const requestId = ++searchRequestId;

    // Track search state for Load More
    if (!append) {
        state.searchOffset = 0;
        state.lastSearchQuery = requestedQuery;
    }

    showLoading(append ? 'Loading more...' : `Searching for "${requestedQuery}"...`);

    try {
        let data;
        const useNativeAudiobookSearch = requestedType === 'audiobook'
            && !/^https?:\/\//i.test(requestedQuery)
            && typeof window.FreedifyAndroid?.searchAudiobookBay === 'function';

        if (useNativeAudiobookSearch) {
            const page = Math.floor(state.searchOffset / 8) + 1;
            const payload = await searchAudiobookBayWithAndroid(requestedQuery, page, controller.signal);
            data = {
                results: rankAudiobookResults(payload?.results, requestedQuery),
                query: requestedQuery,
                type: 'audiobook',
                source: 'audiobookbay-webview',
                offset: state.searchOffset,
            };
        } else {
            const response = await fetch(
                `/api/search?q=${encodeURIComponent(requestedQuery)}&type=${encodeURIComponent(requestedType)}&offset=${state.searchOffset}`,
                { signal: controller.signal },
            );
            data = await response.json();
            if (!response.ok) throw new Error(data.detail || 'Search failed');
        }

        // A slower previous request must never replace results for the term the
        // user is currently looking at, which is especially noticeable on mobile.
        if (requestId !== searchRequestId) return;

        hideLoading();

        // Check if it was a Spotify URL
        // Check if it was a Spotify/Imported URL
        if (data.is_url) {
            // Auto-open detail view for albums/playlists
            if (data.tracks && (data.type === 'album' || data.type === 'playlist' || data.type === 'artist')) {
                emit('showDetailView', data.results[0], data.tracks);
                return;
            }
            // Auto-open audiobook modal when a direct ABB URL is pasted
            if (data.type === 'audiobook' && data.results && data.results.length > 0) {
                const book = data.results[0];
                emit('openAudiobook', book.id);
                return;
            }
            // Auto-play single track (e.g. YouTube link)
            if (data.results && data.results.length === 1 && data.type === 'track') {
                const track = data.results[0];
                emit('playTrack', track);
                showToast(`Playing imported track: ${track.name}`);
                // Also render it so they can see it
            }
        }

        renderResults(data.results, data.type || requestedType, append);

        // Update offset for next load
        state.searchOffset += data.results.length;

        // Show/hide Load More button
        const loadMoreBtn = $('#load-more-btn');
        if (loadMoreBtn) {
            // ABB returns ~15 results per page, so use a lower threshold for audiobooks
            const loadMoreThreshold = (data.type === 'audiobook') ? 5 : 20;
            if (data.results.length >= loadMoreThreshold) {
                loadMoreBtn.classList.remove('hidden');
            } else {
                loadMoreBtn.classList.add('hidden');
            }
        }

    } catch (error) {
        if (error.name === 'AbortError' || requestId !== searchRequestId) return;
        console.error('Search error:', error);
        showError(error.message || 'Search failed. Please try again.');
    }
}

export function renderResults(results, type, append = false) {
    const loadMoreBtn = $('#load-more-btn');
    if (type === 'audiobook') resultsContainer.dataset.androidView = 'search';

    // Store results for re-rendering (when sort changes)
    if (!append) {
        state.lastSearchResults = results || [];
        state.lastSearchType = type;
    } else if (results) {
        state.lastSearchResults = [...state.lastSearchResults, ...results];
    }

    if (!results || results.length === 0) {
        if (!append) {
            resultsContainer.innerHTML = `
                <div class="empty-state">
                    <span class="empty-icon">\u{1F50D}</span>
                    <p>No results found</p>
                </div>
            `;
            if (loadMoreBtn) loadMoreBtn.classList.add('hidden');
        }
        return;
    }

    let grid;
    // Helper to get or create Load More button
    let persistentLoadMoreBtn = document.getElementById('load-more-btn');
    if (persistentLoadMoreBtn) {
        persistentLoadMoreBtn.remove(); // Rescue it
    } else {
        // Create fresh if missing (e.g. after view switch)
        persistentLoadMoreBtn = document.createElement('button');
        persistentLoadMoreBtn.id = 'load-more-btn';
        persistentLoadMoreBtn.className = 'load-more-btn hidden';
        persistentLoadMoreBtn.textContent = 'Load More Results';
        persistentLoadMoreBtn.addEventListener('click', () => {
             if (state.lastSearchQuery) {
                 performSearch(state.lastSearchQuery, true);
             }
        });
    }

    if (append) {
        // Get existing grid or create new
        grid = resultsContainer.querySelector('.results-grid') || resultsContainer.querySelector('.results-list');
        if (!grid) {
            grid = document.createElement('div');
            // Use list layout for tracks, grid for others
            grid.className = (type === 'track') ? 'results-list' : 'results-grid';
            resultsContainer.innerHTML = '';
            resultsContainer.appendChild(grid);
        }
    } else {
        grid = document.createElement('div');
        // Use list layout for tracks, grid for others
        grid.className = (type === 'track') ? 'results-list' : 'results-grid';

        resultsContainer.innerHTML = '';
        resultsContainer.appendChild(grid);
    }

    // For 'podcast' we reuse album card style + add favorite heart overlay
    if (type === 'podcast') {
        results.forEach(item => {
            const isFav = isPodcastFavorited(item.id);
            const cardHtml = renderAlbumCard(item);
            // Wrap with a container that includes the heart button
            grid.innerHTML += `<div class="podcast-search-card-wrapper" style="position:relative;">
                ${cardHtml}
                <button class="podcast-fav-btn ${isFav ? 'favorited' : ''}" data-podcast-id="${item.id}" title="${isFav ? 'Remove from My Podcasts' : 'Save to My Podcasts'}">${isFav ? '\u2764\uFE0F' : '\uD83E\uDD0D'}</button>
            </div>`;
        });
        // Wire up favorite buttons on search cards
        grid.querySelectorAll('.podcast-search-card-wrapper .podcast-fav-btn').forEach(btn => {
            btn.addEventListener('click', (e) => {
                e.stopPropagation();
                const podcastId = btn.dataset.podcastId;
                const podcast = results.find(r => r.id === podcastId);
                if (podcast) {
                    const nowFav = togglePodcastFavorite(podcast);
                    btn.textContent = nowFav ? '\u2764\uFE0F' : '\uD83E\uDD0D';
                    btn.classList.toggle('favorited', nowFav);
                    btn.title = nowFav ? 'Remove from My Podcasts' : 'Save to My Podcasts';
                }
            });
        });
        // Wire up card clicks (need to go through wrapper)
        grid.querySelectorAll('.podcast-search-card-wrapper .album-card').forEach(el => {
            el.addEventListener('click', () => {
                emit('openPodcastEpisodes', el.dataset.id);
            });
        });
    } else if (type === 'track') {
        results.forEach(track => {
            grid.innerHTML += renderTrackCard(track);
        });
    } else if (type === 'album') {
        results.forEach(album => {
            grid.innerHTML += renderAlbumCard(album);
        });
    } else if (type === 'audiobook') {
        results.forEach(book => {
            // Map audiobook properties to album card format
            grid.innerHTML += `<div class="audiobook-search-card-wrapper">${renderAlbumCard({
                id: book.id,
                name: book.title,
                artists: book.author || 'AudiobookBay',
                album_art: book.cover_image,
                total_tracks: 'Audiobook'
            })}${book.genres?.length ? `<div class="audiobook-card-genres">${book.genres.slice(0, 3).map(genre => `<span>${escapeHtml(genre)}</span>`).join('')}</div>` : ''}</div>`;
        });
    } else if (type === 'artist') {
        results.forEach(artist => {
            grid.innerHTML += renderArtistCard(artist);
        });
    }
    // Always append Load More button at the very end
    if (persistentLoadMoreBtn) {
        resultsContainer.appendChild(persistentLoadMoreBtn);
    }

    // Attach click listeners
    if (type === 'track') {
        grid.querySelectorAll('.track-item').forEach(el => {
            // Main card click (Play)
            el.addEventListener('click', (e) => {
                const trackId = String(el.dataset.id);
                const track = results.find(t => String(t.id) === trackId);
                if (track) {
                    emit('playTrack', track);
                    showToast(`Playing "${track.name}"`);
                }
            });

            // Queue button click
            const queueBtn = el.querySelector('.queue-btn');
            if (queueBtn) {
                queueBtn.addEventListener('click', (e) => {
                    e.stopPropagation();
                    const trackId = String(el.dataset.id);
                    const track = results.find(t => String(t.id) === trackId);
                    if (track) addToQueue(track);
                });
            }
        });

        // Fetch features regarding DJ Mode
        if (state.djMode) {
            emit('fetchAudioFeaturesForTracks', results);
        }
    } else if (type === 'album' || type === 'audiobook') {
        // Album/Audiobook cards - open modal
        grid.querySelectorAll('.album-card').forEach(el => {
            el.addEventListener('click', () => {
                const id = el.dataset.id;
                if (type === 'audiobook') {
                    emit('openAudiobook', id);
                } else {
                    emit('openAlbum', id);
                }
            });
        });
    } else if (type === 'podcast') {
        // Podcast click handlers already wired above in the render block
    } else if (type === 'artist') {
        grid.querySelectorAll('.artist-item').forEach((el, i) => {
            el.addEventListener('click', () => emit('openArtist', results[i].id));
        });
    }

}

// Add track to queue (called from Queue button click)
export function addToQueue(track) {
    if (!track) return;
    state.queue.push(track);
    emit('updateQueueUI');
    showToast(`Added "${track.name}" to queue`);
}

// ========== DOWNLOAD LOGIC ==========

const downloadModal = $('#download-modal');
const downloadTrackName = $('#download-track-name');
const downloadFormat = $('#download-format');
const downloadCancelBtn = $('#download-cancel-btn');
const downloadConfirmBtn = $('#download-confirm-btn');
const downloadAllBtn = $('#download-all-btn'); // New button
let trackToDownload = null;
let isBatchDownload = false; // Flag for batch mode

export function openDownloadModal(trackJson) {
    const track = JSON.parse(decodeURIComponent(trackJson));
    trackToDownload = track;
    isBatchDownload = false;

    // Check if we are coming from detailed view (Album/Playlist)
    if (!detailView.classList.contains('hidden')) {
        state.pendingAlbumReopen = true;
    }

    downloadTrackName.textContent = `${track.name} - ${track.artists}`;
    downloadModal.classList.remove('hidden');
}

window.openDownloadModal = openDownloadModal;

if (downloadAllBtn) {
    downloadAllBtn.addEventListener('click', () => {
        if (state.detailTracks.length === 0) return;

        isBatchDownload = true;
        trackToDownload = null;

        // Track previous view
        if (!detailView.classList.contains('hidden')) {
             state.pendingAlbumReopen = true;
        }

        // Get album/playlist name
        const name = $('.detail-name').textContent;
        // Sync state to ensure filename is correct even if state was lost
        state.detailName = name;

        downloadTrackName.textContent = `All tracks from "${name}" (ZIP)`;
        downloadModal.classList.remove('hidden');
    });
}

// Download current playing track buttons
const downloadCurrentBtn = $('#download-current-btn');
const fsDownloadBtn = $('#fs-download-btn');

function downloadCurrentTrack() {
    if (state.currentIndex < 0 || !state.queue[state.currentIndex]) {
        showToast('No track playing');
        return;
    }
    const track = state.queue[state.currentIndex];
    trackToDownload = track;
    isBatchDownload = false;
    downloadTrackName.textContent = `${track.name} - ${track.artists}`;

    // Filter format options based on track source
    updateDownloadFormatOptions(track);

    downloadModal.classList.remove('hidden');
}

// Update download format options based on track source quality
function updateDownloadFormatOptions(track) {
    const source = track?.source || '';
    const formatSelect = $('#download-format');
    const hiresGroup = $('#hires-formats');
    const sourceHint = $('#download-source-hint');

    // Categorize sources
    const isHiResSource = source === 'dab' || source === 'qobuz';
    const isHiFiSource = source === 'deezer' || source === 'jamendo' || source === 'tidal';
    const isLossySource = source === 'ytmusic' || source === 'youtube' || source === 'podcast' ||
                          source === 'import' || source === 'archive' || source === 'phish' ||
                          source === 'soundcloud' || source === 'bandcamp';

    // Re-enable all options first
    formatSelect.querySelectorAll('option, optgroup').forEach(el => {
        el.disabled = false;
        el.style.display = '';
    });

    // Hide/show hint
    if (sourceHint) {
        sourceHint.classList.add('hidden');
        sourceHint.textContent = '';
    }

    if (isLossySource) {
        // Lossy source: only MP3 available
        formatSelect.querySelectorAll('option').forEach(opt => {
            if (opt.dataset.minQuality !== 'lossy') {
                opt.disabled = true;
                opt.style.display = 'none';
            }
        });
        // Hide optgroups for lossless
        formatSelect.querySelectorAll('optgroup').forEach(grp => {
            if (grp.label !== 'Lossy') {
                grp.style.display = 'none';
            }
        });
        formatSelect.value = 'mp3';
        if (sourceHint) {
            sourceHint.textContent = `\u26A0\uFE0F Source is ${source || 'external'} - only MP3 available`;
            sourceHint.classList.remove('hidden');
        }
    } else if (isHiFiSource && !isHiResSource) {
        // HiFi source (16-bit lossless): hide 24-bit options
        if (hiresGroup) hiresGroup.style.display = 'none';
        formatSelect.querySelectorAll('option[data-min-quality="hires"]').forEach(opt => {
            opt.disabled = true;
            opt.style.display = 'none';
        });
        formatSelect.value = 'flac';
    } else if (isHiResSource) {
        // Hi-Res source: show 24-bit only if Hi-Res mode is enabled
        if (!state.hiResMode) {
            if (hiresGroup) hiresGroup.style.display = 'none';
            formatSelect.querySelectorAll('option[data-min-quality="hires"]').forEach(opt => {
                opt.disabled = true;
                opt.style.display = 'none';
            });
            formatSelect.value = 'flac';
            if (sourceHint) {
                sourceHint.textContent = '\uD83D\uDCA1 Enable Hi-Res mode for 24-bit options';
                sourceHint.classList.remove('hidden');
            }
        } else {
            // All options available
            formatSelect.value = 'flac_24';
        }
    } else {
        // Unknown source: default to 16-bit lossless, show 24-bit only if Hi-Res mode
        if (!state.hiResMode) {
            if (hiresGroup) hiresGroup.style.display = 'none';
            formatSelect.querySelectorAll('option[data-min-quality="hires"]').forEach(opt => {
                opt.disabled = true;
                opt.style.display = 'none';
            });
        }
        formatSelect.value = 'flac';
    }
}

if (downloadCurrentBtn) {
    downloadCurrentBtn.addEventListener('click', downloadCurrentTrack);
}

if (fsDownloadBtn) {
    fsDownloadBtn.addEventListener('click', downloadCurrentTrack);
}

function closeDownloadModal() {
    downloadModal.classList.add('hidden');
    trackToDownload = null;
    isBatchDownload = false;

    // Restore Album/Playlist view if it was active
    if (state.pendingAlbumReopen) {
        detailView.classList.remove('hidden');
        state.pendingAlbumReopen = false;
        // Also ensure Results are hidden if we are in detail view
        resultsSection.classList.add('hidden');
    }
}

downloadCancelBtn.addEventListener('click', closeDownloadModal);

// Background Download UI Helpers
const downloadIndicator = $('#download-indicator');
const downloadStatusText = $('#download-status-text');
const downloadProgressFill = $('#download-progress-fill');
const downloadMinimizeBtn = $('#download-minimize-btn');

function updateDownloadUI(percent, text) {
    if (downloadIndicator && downloadIndicator.classList.contains('hidden')) {
        downloadIndicator.classList.remove('hidden');
    }
    if (text && downloadStatusText) downloadStatusText.textContent = text;
    if (downloadProgressFill) downloadProgressFill.style.width = `${percent}%`;
}

function hideDownloadUI() {
    if (downloadIndicator) downloadIndicator.classList.add('hidden');
    if (downloadProgressFill) downloadProgressFill.style.width = '0%';
}

if (downloadMinimizeBtn) {
    downloadMinimizeBtn.addEventListener('click', () => {
        if (downloadIndicator) downloadIndicator.classList.add('hidden');
    });
}

downloadConfirmBtn.addEventListener('click', async () => {
    const format = downloadFormat.value;
    const track = trackToDownload; // Capture before closing modal clears it
    const isBatch = isBatchDownload;

    // Get album/playlist name from state
    const name = state.detailName || 'Batch Download';
    const artist = state.detailArtist || '';
    const albumName = artist ? `${artist} - ${name}` : name;

    closeDownloadModal();

    // Show Background UI
    updateDownloadUI(2, 'Starting download...');

    if (isBatch) {
        // Multi-Part Batch Download for Large Playlists
        const tracks = state.detailTracks;
        const totalTracks = tracks.length;
        const CHUNK_SIZE = 50; // 50 songs per ZIP
        const totalParts = Math.ceil(totalTracks / CHUNK_SIZE);

        // Hide overlay elements just in case
        const progressContainer = $('#loading-progress-container');
        if (progressContainer) progressContainer.classList.add('hidden');

        let successfulParts = 0;
        let failedParts = [];

        try {
            for (let part = 1; part <= totalParts; part++) {
                const start = (part - 1) * CHUNK_SIZE;
                const end = Math.min(start + CHUNK_SIZE, totalTracks);
                const chunkTracks = tracks.slice(start, end);

                // Update message
                const partLabel = totalParts > 1 ? ` (Part ${part}/${totalParts})` : '';
                updateDownloadUI(0, `Downloading${partLabel}: ${chunkTracks.length} tracks...`);

                // Real-Time Progress Polling
                const downloadId = 'dl_' + Date.now() + '_' + Math.random().toString(36).substr(2, 9);
                let pollInterval;

                pollInterval = setInterval(async () => {
                    try {
                        const progRes = await fetch(`/api/progress/${downloadId}`);
                        if (progRes.ok) {
                            const progData = await progRes.json();
                            if (progData.total > 0) {
                                const chunkProgress = (progData.current / progData.total) * 100;
                                // Overall progress
                                const overallProgress = ((successfulParts / totalParts) * 100) + (chunkProgress / totalParts);
                                updateDownloadUI(overallProgress);
                            }
                        }
                    } catch (e) {
                        console.warn('Progress poll failed:', e);
                    }
                }, 2000);

                try {
                    const response = await fetch('/api/download-batch', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({
                            tracks: chunkTracks.map(t => t.isrc || t.id),
                            names: chunkTracks.map(t => t.name),
                            artists: chunkTracks.map(t => t.artists),
                            zip_name: albumName,
                            album_name: (state.detailType === 'album' && (state.detailReleaseYear || chunkTracks[0]?.release_date)) ? albumName : null,
                            format: format,
                            part: part,
                            total_parts: totalParts,
                            download_id: downloadId,
                            album_art_urls: chunkTracks.map(t => t.album_art || t.image || state.detailCover || null),
                            release_year: state.detailReleaseYear || (chunkTracks[0]?.release_date?.substring(0, 4)) || '',
                        })
                    });

                    clearInterval(pollInterval);

                    if (!response.ok) throw new Error(`Part ${part} failed`);

                    // Download ZIP
                    const blob = await response.blob();
                    const url = window.URL.createObjectURL(blob);
                    const a = document.createElement('a');
                    a.style.display = 'none';
                    a.href = url;

                    const zipName = totalParts > 1
                        ? `${albumName} (Part ${part} of ${totalParts}).zip`
                        : `${albumName}.zip`;
                    a.download = zipName.replace(/[\\/:"*?<>|]/g, "_");
                    document.body.appendChild(a);
                    a.click();
                    window.URL.revokeObjectURL(url);
                    document.body.removeChild(a);

                    successfulParts++;

                    if (part < totalParts) {
                        await new Promise(resolve => setTimeout(resolve, 1000));
                    }

                } catch (partError) {
                    clearInterval(pollInterval);
                    console.error(`Part ${part} error:`, partError);
                    failedParts.push(part);
                    showError(`Download Part ${part} failed`);
                }
            }

            updateDownloadUI(100, 'Download complete!');
            setTimeout(hideDownloadUI, 3000);

            if (failedParts.length === 0) {
                showToast(totalParts > 1 ? `Download complete! ${totalParts} parts saved.` : 'Download complete!');
            }

        } catch (error) {
            console.error('Batch download error:', error);
            hideDownloadUI();
            showError('Batch download failed');
        }
        return;
    }

    if (!track) return;

    // Single Track Logic
    updateDownloadUI(0, `Downloading "${track.name}"...`);

    try {
        const query = `${track.name} ${track.artists}`;
        const isrc = track.isrc || track.id;
        const ext = format === 'alac' ? 'm4a' : format.replace(/_24$/, '');
        const filename = `${track.artists} - ${track.name}.${ext}`.replace(/[\\/:"*?<>|]/g, "_");

        const hiresParam = state.hiResMode ? '&hires=true' : '&hires=false';
        const qualityParam = state.hiResMode ? `&hires_quality=${state.hiResQuality}` : '';
        const response = await fetch(`/api/download/${isrc}?q=${encodeURIComponent(query)}&format=${format}&filename=${encodeURIComponent(filename)}${hiresParam}${qualityParam}`);

        if (!response.ok) {
            const err = await response.json();
            throw new Error(err.detail || 'Download failed');
        }

        const blob = await response.blob();
        const url = window.URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.style.display = 'none';
        a.href = url;
        a.download = filename;
        document.body.appendChild(a);
        a.click();
        window.URL.revokeObjectURL(url);
        document.body.removeChild(a);

        updateDownloadUI(100, 'Download complete!');
        setTimeout(hideDownloadUI, 3000);
        showToast(`Downloaded "${track.name}"`);

    } catch (error) {
        console.error('Download error:', error);
        hideDownloadUI();
        showError('Failed to download track.');
    }
});

// ========== CARD RENDERERS ==========

export function renderTrackCard(track) {
    const year = track.release_date ? String(track.release_date).slice(0, 4) : '';
    const isStarred = isInLibrary(track.id);

    // Check for HiRes quality
    const isHiRes = track.is_hi_res || track.is_hires || track.audio_quality?.isHiRes || false;
    const hiResBadge = isHiRes ? '<span class="hires-badge">HI-RES</span>' : '';

    return `
        <div class="track-item" data-id="${track.id}">
            <img class="track-album-art" src="${track.album_art || '/static/icon.svg'}" alt="${escapeHtml(track.name)}" loading="lazy">
            <div class="track-info">
                <div class="track-name">${hiResBadge}${escapeHtml(track.name)}</div>
                <div class="track-artist">${escapeHtml(track.artists)}</div>
            </div>
            <span class="track-duration">${track.duration_ms ? formatTime(track.duration_ms / 1000) : (track.duration && track.duration.toString().includes(':') ? track.duration : formatTime(track.duration))}</span>
            <button class="star-btn ${isStarred ? 'starred' : ''}" data-track-id="${track.id}" title="${isStarred ? 'Remove from Library' : 'Add to Library'}">${isStarred ? '\u2605' : '\u2606'}</button>
            <button class="track-action-btn queue-btn" title="Add to Queue">+</button>
        </div>
    `;
}

export function renderAlbumCard(album) {
    const year = (album.release_date && album.release_date.length >= 4) ? album.release_date.slice(0, 4) : '';
    const trackCount = album.total_tracks ? `${album.total_tracks} tracks` : '';
    // Check for HiRes quality (if available from API)
    const isHiRes = album.is_hi_res || album.is_hires || album.audio_quality?.isHiRes || false;
    const hiResBadge = isHiRes ? '<span class="hires-badge">HI-RES</span>' : '';

    return `
        <div class="album-card" data-id="${album.id}" data-year="${year || '0'}">
            <div class="album-card-art-container">
                <img class="album-card-art" src="${album.album_art || '/static/icon.svg'}" alt="${escapeHtml(album.name)}" loading="lazy">
                ${hiResBadge}
            </div>
            <div class="album-card-info">
                <p class="album-card-title">${escapeHtml(album.name)}</p>
                <p class="album-card-artist">${escapeHtml(album.artists)}</p>
                <div class="album-card-meta">
                    <span>${trackCount}</span>
                    <span>${year}</span>
                </div>
            </div>
        </div>
    `;
}

export function renderArtistCard(artist) {
    const followers = artist.followers ? `${(artist.followers / 1000).toFixed(0)}K followers` : '';
    return `
        <div class="artist-item" data-id="${artist.id}">
            <img class="artist-art" src="${artist.image || '/static/icon.svg'}" alt="Artist" loading="lazy">
            <div class="artist-info">
                <p class="artist-name">${escapeHtml(artist.name)}</p>
                <p class="artist-genres">${artist.genres?.slice(0, 2).join(', ') || 'Artist'}</p>
                <p class="artist-followers">${followers}</p>
            </div>
        </div>
    `;
}

// ========== HELPER FUNCTIONS (podcast favorites - used locally) ==========
// These use state.podcastFavorites which is managed in data.js

function isPodcastFavorited(podcastId) {
    return state.podcastFavorites.some(p => p.id === podcastId);
}

function togglePodcastFavorite(podcast) {
    if (isPodcastFavorited(podcast.id)) {
        removePodcastFavorite(podcast.id);
        return false;
    } else {
        addPodcastFavorite(podcast);
        return true;
    }
}

function savePodcastFavorites() {
    // Use the same key as state.js loads from: 'freedify_podcasts'
    localStorage.setItem('freedify_podcasts', JSON.stringify(state.podcastFavorites));
}

function addPodcastFavorite(podcast) {
    if (!podcast || !podcast.id) return false;
    if (state.podcastFavorites.some(p => p.id === podcast.id)) return false;

    state.podcastFavorites.unshift({
        id: podcast.id,
        name: podcast.name,
        artist: podcast.artists || podcast.artist || '',
        artwork: podcast.album_art || podcast.artwork || '/static/icon.svg',
        addedAt: Date.now(),
        tags: []
    });
    savePodcastFavorites();
    showToast(`\u2764\uFE0F Saved "${podcast.name}" to My Podcasts`);
    return true;
}

function removePodcastFavorite(podcastId) {
    const idx = state.podcastFavorites.findIndex(p => p.id === podcastId);
    if (idx !== -1) {
        const podcast = state.podcastFavorites[idx];
        state.podcastFavorites.splice(idx, 1);
        savePodcastFavorites();
        showToast(`Removed "${podcast.name}" from My Podcasts`);
        return true;
    }
    return false;
}
