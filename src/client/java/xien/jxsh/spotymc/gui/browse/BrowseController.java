package xien.jxsh.spotymc.gui.browse;

import xien.jxsh.spotymc.PlaybackPoller;
import xien.jxsh.spotymc.api.PlaybackState;
import xien.jxsh.spotymc.gui.model.RowEntry;
import xien.jxsh.spotymc.gui.model.RowKind;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

/**
 * Owns everything about the left panel's content: which tab is active (Search vs. Library), the
 * current search query/results, the fetched library (Liked Songs + your own playlists), the
 * Liked-Songs drill-down, and the row list's scroll position.
 * <p>
 * Deliberately has no Minecraft/rendering dependencies -- it only knows how to fetch and shape
 * data into {@link RowEntry} rows. The screen turns those rows into pixels and hit-boxes, and
 * decides what actually happens when one is clicked.
 */
public final class BrowseController {

	public enum Mode { SEARCH, LIBRARY }

	private final PlaybackPoller poller;
	private final ExecutorService bgExecutor;

	private Mode mode = Mode.SEARCH;
	private String searchQuery = "";
	private volatile boolean searching = false;
	private volatile List<PlaybackState.Track> searchTrackResults;
	private volatile List<PlaybackState.Playlist> searchPlaylistResults;

	private volatile List<PlaybackState.Track> likedSongs;
	private volatile List<PlaybackState.Playlist> myPlaylists;
	private volatile boolean loadingLibrary = false;
	private boolean viewingLikedSongs = false;

	private int scrollOffset = 0;

	// Cache for buildEntries() -- see the javadoc there for why.
	private List<RowEntry> cachedEntries = List.of();
	private Mode cachedEntriesMode;
	private boolean cachedEntriesViewingLikedSongs;
	private List<PlaybackState.Track> cachedEntriesSearchTracks;
	private List<PlaybackState.Playlist> cachedEntriesSearchPlaylists;
	private List<PlaybackState.Track> cachedEntriesLikedSongs;
	private List<PlaybackState.Playlist> cachedEntriesMyPlaylists;

	public BrowseController(PlaybackPoller poller, ExecutorService bgExecutor) {
		this.poller = poller;
		this.bgExecutor = bgExecutor;
	}

	/**
	 * Non-null when the Web API tier is unusable (no/invalid Client ID). Renderers use this to
	 * show one honest explanation in place of Search/Library results.
	 */
	public String webApiDisabledReason() { return poller.getWebApiDisabledReason(); }

	/** Full per-field breakdown behind {@link #webApiDisabledReason()}, for the left panel. */
	public List<String> webApiDiagnosticLines() { return poller.getWebApiDiagnosticLines(); }

	public Mode mode() { return mode; }
	public String searchQuery() { return searchQuery; }
	public void setSearchQuery(String q) { this.searchQuery = q; }
	public boolean isSearching() { return searching; }
	public boolean isLoadingLibrary() { return loadingLibrary; }
	public boolean hasLibraryLoaded() { return likedSongs == null; }

	public List<PlaybackState.Track> likedSongs() { return likedSongs; }
	public int scrollOffset() { return scrollOffset; }

	/** Clamps the current scroll offset to a valid range for a list holding `total` items, shown `maxRows` at a time. */
	public void clampScroll(int total, int maxRows) {
		int maxScroll = Math.max(0, total - maxRows);
		scrollOffset = Math.clamp(scrollOffset, 0, maxScroll);
	}

	/** Scrolls by one "notch"; direction should be -1 (up) or +1 (down). */
	public void scrollBy(int direction, int total, int maxRows) {
		int maxScroll = Math.max(0, total - maxRows);
		scrollOffset = Math.clamp(maxScroll, 0, scrollOffset + direction);
	}

	/** Sets the scroll offset directly (e.g. from a scrollbar drag), clamped to a valid range. */
	public void setScrollOffset(int offset, int total, int maxRows) {
		int maxScroll = Math.max(0, total - maxRows);
		scrollOffset = Math.clamp(maxScroll, 0, offset);
	}

	public void switchMode(Mode newMode, Runnable onChanged) {
		switchMode(newMode, onChanged, _ -> {});
	}

	public void switchMode(Mode newMode, Runnable onChanged, Consumer<String> onError) {
		if (mode == newMode) return;
		mode = newMode;
		scrollOffset = 0;
		viewingLikedSongs = false;
		if (newMode == Mode.LIBRARY && likedSongs == null && !loadingLibrary) loadLibrary(onChanged, onError);
		onChanged.run();
	}

	/** Drills in to show the individual liked songs, rather than playing blind. */
	public void openLikedSongs() {
		viewingLikedSongs = true;
		scrollOffset = 0;
	}

	/** Returns from the Liked-Songs drill-down to the library root. */
	public void closeLikedSongs() {
		viewingLikedSongs = false;
		scrollOffset = 0;
	}

	/** Fetches Liked Songs and the user's own playlists for the Library tab, if not already loading. */
	public void loadLibrary(Runnable onChanged) {
		loadLibrary(onChanged, _ -> {});
	}

	/**
	 * Same as {@link #loadLibrary(Runnable)}, but reports a failure on either half of the fetch
	 * via {@code onError} instead of silently leaving that part of the Library tab looking empty
	 * with no indication why (e.g. an auth problem, or Spotify returning something unparsable).
	 */
	public void loadLibrary(Runnable onChanged, Consumer<String> onError) {
		if (loadingLibrary) return;
		if (webApiDisabledReason() != null) {
			// Don't spend a background thread and a doomed HTTP round-trip on a call that
			// can't succeed. LeftPanelRenderer already shows a full diagnostic for this before
			// this can even be reached, so skip onError here too -- it would just duplicate that
			// same explanation as a second banner at the bottom of the screen.
			return;
		}
		loadingLibrary = true;
		CompletableFuture<List<PlaybackState.Track>> likedFuture = CompletableFuture.supplyAsync(() -> {
			try {
				return poller.api.getLikedSongs();
			} catch (Exception e) {
				onError.accept("Couldn't load Liked Songs: " + e.getMessage());
				return List.of();
			}
		}, bgExecutor);
		CompletableFuture<List<PlaybackState.Playlist>> playlistsFuture = CompletableFuture.supplyAsync(() -> {
			try {
				return poller.api.getPlaylists();
			} catch (Exception e) {
				onError.accept("Couldn't load playlists: " + e.getMessage());
				return List.of();
			}
		}, bgExecutor);
		likedFuture.thenCombine(playlistsFuture, (liked, playlists) -> {
			likedSongs = liked;
			myPlaylists = playlists;
			return null;
		}).whenComplete((_, _) -> {
			loadingLibrary = false;
			onChanged.run();
		});
	}

	/** Kicks off a search for both tracks and playlists, if a query is present and not already searching. */
	public void doSearch(Runnable onSearchStarted, Runnable onSearchFinished, Consumer<String> onError) {
		String q = searchQuery.trim();
		if (q.isEmpty() || searching) return;
		if (webApiDisabledReason() != null) {
			// Same reasoning as loadLibrary() above -- the left panel already covers this.
			return;
		}
		searching = true;
		scrollOffset = 0;
		onSearchStarted.run();
		CompletableFuture<List<PlaybackState.Track>> trackFuture = CompletableFuture.supplyAsync(() -> {
			try {
				return poller.api.searchTracks(q);
			} catch (Exception e) {
				onError.accept("Search failed: " + e.getMessage());
				return List.of();
			}
		}, bgExecutor);
		CompletableFuture<List<PlaybackState.Playlist>> playlistFuture = CompletableFuture.supplyAsync(() -> {
			try {
				return poller.api.searchPlaylists(q);
			} catch (Exception e) {
				return List.of();
			}
		}, bgExecutor);
		trackFuture.thenCombine(playlistFuture, (tracks, playlists) -> {
			searchTrackResults = tracks;
			searchPlaylistResults = playlists;
			return null;
		}).whenComplete((_, _) -> {
			searching = false;
			onSearchFinished.run();
		});
	}

	public boolean hasSearchResults() {
		return searchTrackResults != null || searchPlaylistResults != null;
	}

	/**
	 * Builds the row list for whichever tab/drill-down is currently active.
	 * <p>
	 * Called once per render frame by {@code LeftPanelRenderer}, but the underlying search/library
	 * data only ever changes on a search completing, a library load completing, a mode switch, or
	 * entering/leaving the Liked-Songs drill-down -- all of which wholesale-replace the relevant
	 * field(s) rather than mutating them in place. So rather than re-running the label string
	 * concatenation for every row on every single frame, this only rebuilds when one of those
	 * inputs has actually changed (by reference) since the last call.
	 */
	public List<RowEntry> buildEntries() {
		List<PlaybackState.Track> st = searchTrackResults;
		List<PlaybackState.Playlist> sp = searchPlaylistResults;
		List<PlaybackState.Track> ls = likedSongs;
		List<PlaybackState.Playlist> mp = myPlaylists;
		boolean vls = viewingLikedSongs;

		if (mode == cachedEntriesMode && vls == cachedEntriesViewingLikedSongs
				&& st == cachedEntriesSearchTracks && sp == cachedEntriesSearchPlaylists
				&& ls == cachedEntriesLikedSongs && mp == cachedEntriesMyPlaylists) {
			return cachedEntries;
		}

		List<RowEntry> entries = mode == Mode.LIBRARY ? buildLibraryEntries() : buildSearchEntries();

		cachedEntries = entries;
		cachedEntriesMode = mode;
		cachedEntriesViewingLikedSongs = vls;
		cachedEntriesSearchTracks = st;
		cachedEntriesSearchPlaylists = sp;
		cachedEntriesLikedSongs = ls;
		cachedEntriesMyPlaylists = mp;
		return entries;
	}

	private List<RowEntry> buildSearchEntries() {
		List<RowEntry> entries = new ArrayList<>();
		if (searchTrackResults != null) {
			for (PlaybackState.Track t : searchTrackResults) {
				entries.add(new RowEntry(t.title() + " — " + t.artists(), t.uri(), RowKind.TRACK));
			}
		}
		if (searchPlaylistResults != null) {
			for (PlaybackState.Playlist p : searchPlaylistResults) {
				entries.add(new RowEntry("♫ " + p.name() + " (playlist)", p.uri(), RowKind.PLAYLIST));
			}
		}
		return entries;
	}

	/**
	 * Root view: Liked Songs (a single row -- click to see the individual songs) followed by the
	 * user's own playlists. When viewingLikedSongs is true, shows a Back row plus every liked
	 * song instead, each clickable to play from that point through the rest of the list.
	 */
	private List<RowEntry> buildLibraryEntries() {
		List<RowEntry> entries = new ArrayList<>();

		if (viewingLikedSongs) {
			entries.add(new RowEntry("← Back", "", RowKind.BACK));
			if (likedSongs != null) {
				for (int i = 0; i < likedSongs.size(); i++) {
					PlaybackState.Track t = likedSongs.get(i);
					entries.add(new RowEntry(t.title() + " — " + t.artists(), t.uri(), RowKind.LIKED_SONG_TRACK, i));
				}
			}
			return entries;
		}

		if (likedSongs != null && !likedSongs.isEmpty()) {
			entries.add(new RowEntry("♥ Liked Songs (" + likedSongs.size() + ")", "", RowKind.LIKED_SONGS));
		}
		if (myPlaylists != null) {
			for (PlaybackState.Playlist p : myPlaylists) {
				entries.add(new RowEntry("♫ " + p.name() + " (" + p.trackCount() + ")", p.uri(), RowKind.PLAYLIST));
			}
		}
		return entries;
	}
}