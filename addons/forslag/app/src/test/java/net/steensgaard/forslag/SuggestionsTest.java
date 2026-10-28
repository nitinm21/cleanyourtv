package net.steensgaard.forslag;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class SuggestionsTest {

    @Test
    public void foldIgnoresCaseSpacesAndPunctuation() {
        assertEquals("tv2play", Suggestions.fold("TV 2 Play"));
        assertEquals("tv2play", Suggestions.fold("tv2-PLAY"));
        assertEquals("disneyplus", Suggestions.fold("Disney Plus"));
    }

    @Test
    public void parsesServiceLines() {
        Suggestions.Service s = Suggestions.parseService(" HBO Max , Max |com.wbd.stream");
        assertArrayEquals(new String[] {"HBO Max", "Max"}, s.names);
        assertEquals("com.wbd.stream", s.pkg);
        assertEquals("", Suggestions.parseService("Netflix").pkg);
        assertNull(Suggestions.parseService(" , |com.example"));
    }

    @Test
    public void matchesByFoldedNameOrIdAndSkipsLookalikes() throws Exception {
        JSONArray providers = new JSONArray("[{\"provider_id\":8,\"provider_name\":\"Netflix\"},"
                + "{\"provider_id\":1796,\"provider_name\":\"Netflix basic with Ads\"},"
                + "{\"provider_id\":383,\"provider_name\":\"TV2 Play\"},"
                + "{\"provider_id\":1899,\"provider_name\":\"Max\"},"
                + "{\"provider_id\":620,\"provider_name\":\"DR TV\"}]");
        Suggestions.Service netflix = Suggestions.parseService("Netflix|n");
        Suggestions.Service tv2 = Suggestions.parseService("TV 2 Play|t");
        Suggestions.Service max = Suggestions.parseService("HBO Max,Max|m");
        Suggestions.Service dr = Suggestions.parseService("620|d");
        Suggestions.Service none = Suggestions.parseService("YouSee Play|y");
        Suggestions.match(Arrays.asList(netflix, tv2, max, dr, none), providers);
        assertEquals(8, netflix.providerId);
        assertEquals(383, tv2.providerId);
        assertEquals("TV2 Play", tv2.label());
        assertEquals(1899, max.providerId);
        assertEquals(620, dr.providerId);
        assertEquals(-1, none.providerId);
        assertEquals("YouSee Play", none.label());
    }

    @Test
    public void parsesMoviesAndSeries() throws Exception {
        JSONObject movie = new JSONObject("{\"results\":[{\"id\":1,\"title\":\"Druk\",\"release_date\":\"2020-09-24\","
                + "\"overview\":\"Fire venner\",\"poster_path\":\"/p.jpg\",\"backdrop_path\":null,\"vote_average\":7.7},"
                + "{\"id\":2,\"title\":\"\"},{\"id\":0,\"title\":\"No id\"}]}");
        List<Suggestions.Title> m = Suggestions.parseDiscover(movie, false);
        assertEquals(1, m.size());
        assertEquals("Druk", m.get(0).name);
        assertEquals("2020", m.get(0).year);
        assertEquals("https://image.tmdb.org/t/p/w185/p.jpg", m.get(0).poster);
        assertEquals("", m.get(0).backdrop);
        assertEquals(7.7, m.get(0).rating, 0.001);

        JSONObject tv = new JSONObject("{\"results\":[{\"id\":5,\"name\":\"Borgen\",\"first_air_date\":\"\"}]}");
        List<Suggestions.Title> s = Suggestions.parseDiscover(tv, true);
        assertEquals("Borgen", s.get(0).name);
        assertEquals("", s.get(0).year);
        assertTrue(s.get(0).tv);
        assertTrue(Suggestions.parseDiscover(new JSONObject("{}"), true).isEmpty());
    }

    @Test
    public void mixTakesTurnsMergesDuplicatesAndStopsAtMax() {
        Suggestions.Service a = Suggestions.parseService("A|a");
        Suggestions.Service b = Suggestions.parseService("B|b");
        List<Suggestions.Title> la = Arrays.asList(title(1), title(2), title(3));
        List<Suggestions.Title> lb = Arrays.asList(title(10), title(2));
        List<Suggestions.Title> out = Suggestions.mix(Arrays.asList(la, lb), Arrays.asList(a, b), 3, (t, sv) -> true);
        assertEquals(3, out.size());
        assertEquals(1, out.get(0).id);
        assertEquals(10, out.get(1).id);
        assertEquals(2, out.get(2).id);
        assertEquals(Arrays.asList(a, b), out.get(2).services);
    }

    @Test
    public void discoverAsksForSubscriptionTitlesInTheRegion() {
        String p = Suggestions.discoverPath(true, 8, "DK", "da-DK", 50, null);
        assertTrue(p.startsWith("/discover/tv?"));
        assertTrue(p.contains("watch_region=DK"));
        assertTrue(p.contains("with_watch_providers=8"));
        assertTrue(p.contains("with_watch_monetization_types=flatrate"));
        assertTrue(p.contains("language=da-DK"));
        assertTrue(p.contains("vote_count.gte=50"));
    }

    @Test
    public void mixLeavesOutTitlesThatAreOnlyRentedOnAService() {
        Suggestions.Service a = Suggestions.parseService("A|a");
        Suggestions.Service b = Suggestions.parseService("B|b");
        List<Suggestions.Title> la = Arrays.asList(title(1), title(2));
        List<Suggestions.Title> lb = Arrays.asList(title(2), title(3));
        // Title 2 streams on A only; B merely rents it.
        List<Suggestions.Title> out = Suggestions.mix(Arrays.asList(la, lb), Arrays.asList(a, b), 10,
                (t, sv) -> !(t.id == 2 && sv == b));
        assertEquals(3, out.size());
        assertEquals(Arrays.asList(a), out.get(1).services);
        // Offered by B first and refused there, it still comes in for A.
        la = Arrays.asList(title(1), title(2));
        lb = Arrays.asList(title(2), title(3));
        out = Suggestions.mix(Arrays.asList(lb, la), Arrays.asList(b, a), 10, (t, sv) -> !(t.id == 2 && sv == b));
        Suggestions.Title two = null;
        for (Suggestions.Title t : out) if (t.id == 2) two = t;
        assertEquals(Arrays.asList(a), two.services);
    }

    @Test
    public void flatrateReadsOnlyTheRegionsSubscriptions() throws Exception {
        JSONObject json = new JSONObject("{\"results\":{\"DK\":{\"buy\":[{\"provider_id\":76}],"
                + "\"flatrate\":[{\"provider_id\":337}]},\"SE\":{\"flatrate\":[{\"provider_id\":76}]}}}");
        assertEquals(new java.util.TreeSet<>(Arrays.asList(337)), Suggestions.flatrate(json, "DK"));
        assertTrue(Suggestions.flatrate(json, "NO").isEmpty());
        assertTrue(Suggestions.flatrate(new JSONObject("{}"), "DK").isEmpty());
    }

    @Test
    public void drtvLinesAreMarked() {
        Suggestions.Service dr = Suggestions.parseService(" drtv:DRTV|dk.dr.tvplayer");
        assertEquals(Suggestions.FROM_DRTV, dr.source);
        assertTrue(dr.own());
        assertArrayEquals(new String[] {"DRTV"}, dr.names);
        assertEquals("dk.dr.tvplayer", dr.pkg);
        assertTrue(!Suggestions.parseService("Netflix|n").own());
        assertEquals(Suggestions.FROM_VIAPLAY, Suggestions.parseService("viaplay:Viaplay|v").source);
    }

    @Test
    public void parsesDrtvFilmsAndSeriesWithSmallImages() throws Exception {
        String img = "https://x/ResizeImage/$value?Format='jpg'&Width=1440&Height=2160&ImageId='1'";
        JSONObject json = new JSONObject("{\"items\":["
                + "{\"id\":\"1\",\"type\":\"program\",\"title\":\"Mass\",\"path\":\"/program/mass_1\","
                + "\"releaseYear\":2021,\"shortDescription\":\"Et drama\",\"images\":{\"poster\":\"" + img + "\"}},"
                + "{\"id\":\"2\",\"type\":\"show\",\"title\":\"Hoest\",\"path\":\"/serie/hoest_2\",\"releaseYear\":null},"
                + "{\"id\":\"3\",\"type\":\"season\",\"title\":\"Kragepigen\",\"path\":\"/saeson/kragepigen_3\"},"
                + "{\"id\":\"4\",\"type\":\"channel\",\"title\":\"DR1\",\"path\":\"/kanal/dr1\"}]}");
        List<Suggestions.Title> films = Suggestions.parseDrtv(json, false);
        assertEquals(1, films.size());
        assertEquals("/program/mass_1", films.get(0).drPath);
        assertEquals("2021", films.get(0).year);
        assertEquals(0, films.get(0).id);
        assertTrue(films.get(0).poster.contains("Width=184&Height=276"));
        List<Suggestions.Title> series = Suggestions.parseDrtv(json, true);
        assertEquals(2, series.size());
        assertEquals("", series.get(0).year);
    }

    @Test
    public void mixMergesTheSameTitleFromDrtvAndTmdbByName() {
        Suggestions.Service dr = Suggestions.parseService("drtv:DRTV|dk.dr.tvplayer");
        Suggestions.Service nf = Suggestions.parseService("Netflix|com.netflix.ninja");
        Suggestions.Title fromDr = title(0);
        fromDr.name = "Midsommar";
        fromDr.drPath = "/program/midsommar_9";
        Suggestions.Title fromTmdb = title(530385);
        fromTmdb.name = "Midsommar";
        fromTmdb.year = "2019";
        fromTmdb.rating = 7.1;
        List<Suggestions.Title> out = Suggestions.mix(Arrays.asList(Arrays.asList(fromDr), Arrays.asList(fromTmdb)),
                Arrays.asList(dr, nf), 10, (t, sv) -> true);
        assertEquals(1, out.size());
        assertEquals(Arrays.asList(dr, nf), out.get(0).services);
        assertEquals(530385, out.get(0).id);
        assertEquals("/program/midsommar_9", out.get(0).drPath);
        assertEquals(7.1, out.get(0).rating, 0.001);
    }

    @Test
    public void wikidataIdsLandOnTheRightTitles() throws Exception {
        Suggestions.Title movie = title(157336);
        Suggestions.Title show = title(1396);
        show.tv = true;
        Suggestions.Title other = title(1396);
        String q = Suggestions.wikidataQuery(Arrays.asList(movie, show, title(0)));
        assertTrue(q.contains("VALUES ?m { \"157336\" }"));
        assertTrue(q.contains("VALUES ?t { \"1396\" }"));
        JSONObject json = new JSONObject("{\"results\":{\"bindings\":["
                + "{\"m\":{\"value\":\"157336\"},\"netflix\":{\"value\":\"70305903\"}},"
                + "{\"t\":{\"value\":\"1396\"},\"netflix\":{\"value\":\"70143836\"},\"disney\":{\"value\":\"abc\"}},"
                + "{\"t\":{\"value\":\"1396\"},\"netflix\":{\"value\":\"99999999\"}}]}}");
        Suggestions.applyWikidata(json, Arrays.asList(movie, show, other));
        assertEquals("70305903", movie.netflixId);
        assertEquals("70143836", show.netflixId);
        assertEquals("abc", show.disneyId);
        assertEquals("", other.netflixId);
        assertEquals("what-we-do-in-the-shadows", Suggestions.slug("What We Do in the Shadows"));
    }

    @Test
    public void genresMatchTmdbIdsAndDrWords() throws Exception {
        Suggestions.Genre crime = genre("crime");
        Suggestions.Genre fun = genre("entertainment");
        assertTrue(!fun.offered(false));
        assertTrue(fun.offered(true));
        assertTrue(Suggestions.discoverPath(false, 8, "DK", "da-DK", 50, crime).contains("&with_genres=80%7C53"));
        assertTrue(Suggestions.discoverPath(true, 8, "DK", "da-DK", 50, crime).contains("&with_genres=80%7C9648"));

        JSONObject tmdb = new JSONObject("{\"results\":[{\"id\":1,\"title\":\"Heat\",\"genre_ids\":[28,53]}]}");
        Suggestions.Title heat = Suggestions.parseDiscover(tmdb, false).get(0);
        assertTrue(crime.matches(heat));
        assertTrue(!genre("comedy").matches(heat));

        JSONObject dr = new JSONObject("{\"items\":["
                + "{\"type\":\"program\",\"title\":\"Police\",\"path\":\"/program/police_1\","
                + "\"keywords\":[\"editorialptaxtop_film\",\"editorialptaxsub_thriller/krimi\",\"mood_sjovt\"]},"
                + "{\"type\":\"show\",\"title\":\"Bagedysten\",\"path\":\"/serie/bagedysten_2\","
                + "\"categories\":[\"dr1\",\"underholdning\"]}]}");
        Suggestions.Title police = Suggestions.parseDrtv(dr, false).get(0);
        Suggestions.Title bake = Suggestions.parseDrtv(dr, true).get(0);
        assertTrue(crime.matches(police));
        assertTrue(!genre("comedy").matches(police));
        assertTrue(fun.matches(bake));
        assertEquals(1, fun.filter(Arrays.asList(police, bake)).size());
    }

    @Test
    public void parsesViaplaySubscriptionTitlesAndFindsGenrePages() throws Exception {
        String api = Suggestions.VIAPLAY_API;
        JSONObject page = new JSONObject("{\"_links\":{\"viaplay:categoryFilters\":["
                + "{\"href\":\"" + api + "/serier/alle\"},{\"href\":\"" + api + "/serier/dokumentarserier\"},"
                + "{\"href\":\"" + api + "/serier/science-fiction\"},{\"href\":\"" + api + "/serier/underholdning\"}]},"
                + "\"_embedded\":{\"viaplay:blocks\":[{\"type\":\"list\",\"_links\":{\"next\":{\"href\":\""
                + api + "/serier/alle?blockId=x&partial=1&pageNumber=2\"}},\"_embedded\":{\"viaplay:products\":["
                + "{\"type\":\"series\",\"publicPath\":\"robinson-ekspeditionen\","
                + "\"system\":{\"availability\":{\"svod\":{\"start\":\"2026-01-01T00:00:00.000Z\",\"end\":\"2027-01-01T00:00:00.000Z\"}}},"
                + "\"content\":{\"series\":{\"title\":\"Robinson Ekspeditionen\",\"synopsis\":\"22 deltagere\"},"
                + "\"production\":{\"year\":2026},\"imdb\":{\"rating\":\"6,1\"},"
                + "\"images\":{\"boxart\":{\"url\":\"https://i/x.jpg?width=199&height=298\"}}},"
                + "\"_links\":{\"viaplay:genres\":[{\"title\":\"Reality\"},{\"title\":\"Underholdning\"}]}},"
                + "{\"type\":\"series\",\"publicPath\":\"rental\",\"system\":{\"availability\":{}},"
                + "\"content\":{\"title\":\"Only for rent\"}},"
                + "{\"type\":\"movie\",\"publicPath\":\"a-film\","
                + "\"system\":{\"availability\":{\"svod\":{\"start\":\"2026-01-01T00:00:00.000Z\"}}},"
                + "\"content\":{\"title\":\"A film\"}}]}}]}}");
        List<Suggestions.Title> series = Suggestions.parseViaplay(page, true, "2026-10-23T20:00:00.000Z");
        assertEquals(1, series.size());
        Suggestions.Title r = series.get(0);
        assertEquals("Robinson Ekspeditionen", r.name);
        assertEquals("2026", r.year);
        assertEquals(6.1, r.rating, 0.001);
        assertEquals("/serier/robinson-ekspeditionen", r.viaplayPath);
        assertTrue(r.poster.endsWith("width=184&height=276"));
        assertTrue(genre("entertainment").matches(r));
        assertEquals("/film/a-film", Suggestions.parseViaplay(page, false, "2026-10-23T20:00:00.000Z").get(0).viaplayPath);
        // Not yet in the subscription.
        assertTrue(Suggestions.parseViaplay(page, true, "2025-12-31T00:00:00.000Z").isEmpty());
        assertTrue(Suggestions.viaplayNext(page).endsWith("pageNumber=2"));
        assertEquals(api + "/serier/dokumentarserier", Suggestions.viaplayGenreUrl(page, genre("documentary")));
        assertEquals(api + "/serier/science-fiction", Suggestions.viaplayGenreUrl(page, genre("scifi")));
        assertNull(Suggestions.viaplayGenreUrl(page, genre("animation")));
    }

    private static Suggestions.Genre genre(String key) {
        for (Suggestions.Genre g : Suggestions.GENRES) if (g.key.equals(key)) return g;
        throw new AssertionError(key);
    }

    private static Suggestions.Title title(int id) {
        Suggestions.Title t = new Suggestions.Title();
        t.id = id;
        t.name = "T" + id;
        return t;
    }
}
