package io.github.quicodomingo.continuewatching;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class EpisodesTest {

    @Test
    public void seasonAndEpisodeComeFromTheVideoId() {
        assertArrayEquals(new int[]{1, 5}, Episodes.seasonEpisode("tt0944947:1:5"));
        assertArrayEquals(new int[]{12, 104}, Episodes.seasonEpisode("kitsu:7936:12:104"));
    }

    @Test
    public void aMovieOrABadIdHasNoEpisode() {
        assertNull(Episodes.seasonEpisode(null));
        assertNull(Episodes.seasonEpisode("tt0361748"));
        assertNull(Episodes.seasonEpisode("tt0944947:one:5"));
    }

    @Test
    public void placeholdersAreNotEpisodeNames() {
        assertFalse(Episodes.hasRealName(null));
        assertFalse(Episodes.hasRealName("  "));
        assertFalse(Episodes.hasRealName("TBA"));
        assertFalse(Episodes.hasRealName("tbd"));
        assertTrue(Episodes.hasRealName("A Different Corner"));
    }
}
