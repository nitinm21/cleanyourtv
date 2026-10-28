package net.steensgaard.forslag;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Everything about the suggestions that does not need Android: services, TMDb, DRTV and Wikidata requests and
 * answers, and mixing the rows.
 */
final class Suggestions {
    private Suggestions() {}

    static final String NETFLIX = "com.netflix.ninja", DISNEY = "com.disney.disneyplus", DRTV = "dk.dr.tvplayer",
            VIAPLAY = "com.viaplay.android";
    /** Where a service's titles come from: TMDb, or the service's own catalogue. */
    static final String FROM_TMDB = "tmdb", FROM_DRTV = "drtv", FROM_VIAPLAY = "viaplay";

    /** One line of the services list in config.xml, and the TMDb provider it matched. */
    static final class Service {
        final String[] names;
        final String pkg;
        /** FROM_TMDB, FROM_DRTV or FROM_VIAPLAY. */
        final String source;
        int providerId = -1;
        String providerName;

        Service(String[] names, String pkg, String source) {
            this.names = names;
            this.pkg = pkg;
            this.source = source;
        }

        /** Titles from the service's own catalogue: what it lists is what it streams, and TMDb is not needed. */
        boolean own() {
            return !source.equals(FROM_TMDB);
        }

        /** The name to show: TMDb's once matched, else the first one configured. */
        String label() {
            return providerName != null ? providerName : names[0];
        }
    }

    /** Whether a title really streams on a service; TMDb's discover filter alone also lets through rentals. */
    interface Check {
        boolean on(Title t, Service s);
    }

    static final class Title {
        /** TMDb's id; 0 for a title known only from DRTV. */
        int id;
        boolean tv;
        /** poster and backdrop are full URLs. */
        String name = "", year = "", overview = "", poster = "", backdrop = "";
        double rating;
        /** What the apps open a title by: Netflix and Disney+ ids from Wikidata, DRTV's and Viaplay's own paths. */
        String netflixId = "", disneyId = "", drPath = "", viaplayPath = "";
        final List<Service> services = new ArrayList<>();
        /** TMDb's genre ids, and DR's and Viaplay's genre words (lower case), for the genre tabs. */
        final Set<Integer> genres = new HashSet<>();
        final Set<String> tags = new HashSet<>();

        /** Fills in what this title lacks from the same title found on another service. */
        void absorb(Title o) {
            if (id == 0) id = o.id;
            if (rating == 0) rating = o.rating;
            if (year.isEmpty()) year = o.year;
            if (overview.isEmpty()) overview = o.overview;
            if (poster.isEmpty()) poster = o.poster;
            if (backdrop.isEmpty()) backdrop = o.backdrop;
            if (drPath.isEmpty()) drPath = o.drPath;
            if (viaplayPath.isEmpty()) viaplayPath = o.viaplayPath;
            genres.addAll(o.genres);
            tags.addAll(o.tags);
        }

        /** The same title: same kind and folded name, and the same year where both have one. */
        boolean sameAs(Title o) {
            if (id > 0 && o.id > 0) return id == o.id && tv == o.tv;
            return tv == o.tv && fold(name).equals(fold(o.name))
                    && (year.isEmpty() || o.year.isEmpty() || year.equals(o.year));
        }
    }

    /** Lower case, ASCII letters and digits only: "TV 2 Play", "tv2play" and "TV2-Play" are one name. */
    static String fold(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toLowerCase(Locale.ROOT).toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) b.append(c);
        }
        return b.toString();
    }

    /**
     * One genre tab: TMDb's genre ids for movies and for series ("" where the genre is not offered, ids joined
     * with | for "any of"), and the words DR and Viaplay use for it in their genres, categories and page names.
     */
    static final class Genre {
        final String key, movieIds, tvIds;
        final String[] words;

        Genre(String key, String movieIds, String tvIds, String... words) {
            this.key = key;
            this.movieIds = movieIds;
            this.tvIds = tvIds;
            this.words = words;
        }

        boolean offered(boolean tv) {
            return !(tv ? tvIds : movieIds).isEmpty();
        }

        boolean matches(Title t) {
            for (String id : (t.tv ? tvIds : movieIds).split("\\|")) {
                if (!id.isEmpty() && t.genres.contains(Integer.parseInt(id))) return true;
            }
            for (String tag : t.tags) {
                for (String w : words) {
                    if (tag.contains(w)) return true;
                }
            }
            return false;
        }

        /** Whether a page name such as "dokumentarserier" or "science-fiction" is this genre's. */
        boolean names(String slug) {
            String f = fold(slug);
            for (String w : words) {
                String fw = fold(w);
                if (fw.length() >= 4 && f.contains(fw)) return true;
            }
            return false;
        }

        List<Title> filter(List<Title> titles) {
            List<Title> out = new ArrayList<>();
            for (Title t : titles) {
                if (matches(t)) out.add(t);
            }
            return out;
        }
    }

    /** The tabs after "All", in order. TMDb's ids: https://developer.themoviedb.org/reference/genre-movie-list */
    static final Genre[] GENRES = {
        new Genre("drama", "18", "18", "drama"),
        new Genre("comedy", "35", "35", "humor", "komedie", "satire"),
        new Genre("crime", "80|53", "80|9648", "krimi", "thriller"),
        new Genre("action", "28|12", "10759", "action", "eventyr"),
        new Genre("scifi", "878|14", "10765", "sci-fi", "science fiction", "fantasy"),
        new Genre("documentary", "99", "99", "dokumentar"),
        new Genre("entertainment", "", "10764|10767", "underholdning", "reality", "quiz"),
        new Genre("family", "10751", "10751|10762", "familie", "boern", "b\u00f8rn", "ramasjang"),
        new Genre("animation", "16", "16", "animation", "tegnefilm"),
    };

    /**
     * "HBO Max,Max|com.wbd.stream", or "drtv:DRTV|dk.dr.tvplayer" and "viaplay:Viaplay|com.viaplay.android" for
     * those services' own catalogues; null without a name.
     */
    static Service parseService(String line) {
        line = line.trim();
        String source = FROM_TMDB;
        for (String own : new String[] {FROM_DRTV, FROM_VIAPLAY}) {
            if (line.startsWith(own + ":")) {
                source = own;
                line = line.substring(own.length() + 1);
            }
        }
        int bar = line.indexOf('|');
        String names = bar >= 0 ? line.substring(0, bar) : line;
        String pkg = bar >= 0 ? line.substring(bar + 1).trim() : "";
        List<String> list = new ArrayList<>();
        for (String n : names.split(",")) {
            if (!n.trim().isEmpty()) list.add(n.trim());
        }
        return list.isEmpty() ? null : new Service(list.toArray(new String[0]), pkg, source);
    }

    /** Sets providerId on each service from TMDb's /watch/providers results, by id or folded name. */
    static void match(List<Service> services, JSONArray providers) {
        Map<String, JSONObject> byName = new HashMap<>();
        Map<Integer, JSONObject> byId = new HashMap<>();
        for (int i = 0; i < providers.length(); i++) {
            JSONObject p = providers.optJSONObject(i);
            if (p == null) continue;
            byId.put(p.optInt("provider_id", -1), p);
            byName.putIfAbsent(fold(p.optString("provider_name", "")), p);
        }
        for (Service s : services) {
            if (s.providerId >= 0) continue;
            for (String n : s.names) {
                JSONObject p = n.matches("\\d+") ? byId.get(Integer.parseInt(n)) : byName.get(fold(n));
                if (p != null) {
                    s.providerId = p.optInt("provider_id", -1);
                    s.providerName = p.optString("provider_name", n);
                    break;
                }
            }
        }
    }

    /** What is included in one subscription in one country, most popular first. */
    static String discoverPath(boolean tv, int providerId, String region, String language, int minVotes, Genre genre) {
        String genres = genre == null ? "" : "&with_genres=" + (tv ? genre.tvIds : genre.movieIds).replace("|", "%7C");
        return "/discover/" + (tv ? "tv" : "movie") + "?watch_region=" + region + "&with_watch_providers="
                + providerId + "&with_watch_monetization_types=flatrate&sort_by=popularity.desc&include_adult=false"
                + "&vote_count.gte=" + minVotes + genres + "&language=" + language + "&page=1";
    }

    /** Where one title can be watched, in every country. */
    static String watchPath(Title t) {
        return "/" + (t.tv ? "tv" : "movie") + "/" + t.id + "/watch/providers";
    }

    /** The providers that include a title in a subscription in one country, from /{type}/{id}/watch/providers. */
    static Set<Integer> flatrate(JSONObject json, String region) {
        Set<Integer> out = new TreeSet<>();
        JSONObject results = json.optJSONObject("results");
        JSONObject here = results != null ? results.optJSONObject(region) : null;
        JSONArray list = here != null ? here.optJSONArray("flatrate") : null;
        if (list == null) return out;
        for (int i = 0; i < list.length(); i++) {
            JSONObject p = list.optJSONObject(i);
            if (p != null) out.add(p.optInt("provider_id", -1));
        }
        return out;
    }

    static String providersPath(boolean tv, String region, String language) {
        return "/watch/providers/" + (tv ? "tv" : "movie") + "?watch_region=" + region + "&language=" + language;
    }

    static List<Title> parseDiscover(JSONObject json, boolean tv) {
        List<Title> out = new ArrayList<>();
        JSONArray results = json.optJSONArray("results");
        if (results == null) return out;
        for (int i = 0; i < results.length(); i++) {
            JSONObject r = results.optJSONObject(i);
            if (r == null || r.optInt("id", 0) <= 0) continue;
            Title t = new Title();
            t.id = r.optInt("id");
            t.tv = tv;
            t.name = str(r, tv ? "name" : "title");
            String date = str(r, tv ? "first_air_date" : "release_date");
            t.year = date.length() >= 4 ? date.substring(0, 4) : "";
            t.overview = str(r, "overview");
            String poster = str(r, "poster_path"), backdrop = str(r, "backdrop_path");
            t.poster = poster.isEmpty() ? "" : TMDB_IMAGES + "w185" + poster;
            t.backdrop = backdrop.isEmpty() ? "" : TMDB_IMAGES + "w780" + backdrop;
            t.rating = r.optDouble("vote_average", 0);
            JSONArray genres = r.optJSONArray("genre_ids");
            for (int g = 0; genres != null && g < genres.length(); g++) t.genres.add(genres.optInt(g));
            if (!t.name.isEmpty()) out.add(t);
        }
        return out;
    }

    static final String TMDB_IMAGES = "https://image.tmdb.org/t/p/";
    static final String DRTV_API = "https://production.dr-massive.com/api";

    /** One of DRTV's lists, whole (they hold up to about 100 titles). */
    static String drtvListUrl(String listId) {
        return DRTV_API + "/lists/" + listId + "?page=1&page_size=100&device=web_browser&lang=da&sub=Anonymous";
    }

    /** Films (DR calls them programs) or series (shows and seasons) from a DRTV list. */
    static List<Title> parseDrtv(JSONObject json, boolean tv) {
        List<Title> out = new ArrayList<>();
        JSONArray items = json.optJSONArray("items");
        if (items == null) return out;
        for (int i = 0; i < items.length(); i++) {
            JSONObject r = items.optJSONObject(i);
            if (r == null) continue;
            String type = str(r, "type");
            boolean series = type.equals("show") || type.equals("season");
            boolean film = type.equals("program") || type.equals("movie");
            if (tv ? !series : !film) continue;
            Title t = new Title();
            t.tv = tv;
            t.name = str(r, "title");
            t.year = r.optInt("releaseYear", 0) > 0 ? String.valueOf(r.optInt("releaseYear")) : "";
            t.overview = str(r, "shortDescription");
            t.drPath = str(r, "path");
            // Series carry categories ("dokumentar", "underholdning", "krimi"), films only keywords
            // ("editorialptaxsub_thriller/krimi").
            JSONArray categories = r.optJSONArray("categories");
            for (int c = 0; categories != null && c < categories.length(); c++) {
                t.tags.add(categories.optString(c, "").toLowerCase(Locale.ROOT));
            }
            JSONArray keywords = r.optJSONArray("keywords");
            for (int k = 0; keywords != null && k < keywords.length(); k++) {
                String w = keywords.optString(k, "").toLowerCase(Locale.ROOT);
                if (w.startsWith("editorialptax")) t.tags.add(w.substring(w.indexOf('_') + 1));
            }
            JSONObject images = r.optJSONObject("images");
            if (images != null) {
                t.poster = resize(str(images, "poster"), 184, 276);
                t.backdrop = resize(str(images, "wallpaper"), 960, 540);
            }
            if (!t.name.isEmpty() && t.drPath.startsWith("/")) out.add(t);
        }
        return out;
    }

    /** DR's image URLs carry the size to scale to; theirs is 1440x2160, far more than a tile needs. */
    static String resize(String url, int width, int height) {
        return url.replaceFirst("Width=\\d+&Height=\\d+", "Width=" + width + "&Height=" + height);
    }

    static final String VIAPLAY_API = "https://content.viaplay.dk/pcdash-dk";

    /** The first page of Viaplay's films or series, most popular first. */
    static String viaplayAllUrl(boolean tv) {
        return VIAPLAY_API + (tv ? "/serier/alle" : "/film/alle");
    }

    /** Viaplay's own genre page for a genre (from the filters its "all" page lists), or null when it has none. */
    static String viaplayGenreUrl(JSONObject allPage, Genre genre) {
        JSONObject links = allPage.optJSONObject("_links");
        JSONArray filters = links != null ? links.optJSONArray("viaplay:categoryFilters") : null;
        for (int i = 0; filters != null && i < filters.length(); i++) {
            JSONObject f = filters.optJSONObject(i);
            String href = f != null ? str(f, "href") : "";
            String slug = href.substring(href.lastIndexOf('/') + 1);
            if (href.startsWith(VIAPLAY_API) && genre.names(slug)) return href;
        }
        return null;
    }

    /** The product list on a Viaplay page: the first block of type "list" that has products. */
    private static JSONObject viaplayList(JSONObject page) {
        JSONObject embedded = page.optJSONObject("_embedded");
        JSONArray blocks = embedded != null ? embedded.optJSONArray("viaplay:blocks") : null;
        for (int i = 0; blocks != null && i < blocks.length(); i++) {
            JSONObject b = blocks.optJSONObject(i);
            if (b == null || !str(b, "type").equals("list")) continue;
            JSONObject e = b.optJSONObject("_embedded");
            if (e != null && e.optJSONArray("viaplay:products") != null) return b;
        }
        return null;
    }

    /** The next page of a Viaplay list, or null on the last one. */
    static String viaplayNext(JSONObject page) {
        JSONObject list = viaplayList(page);
        JSONObject links = list != null ? list.optJSONObject("_links") : null;
        JSONObject next = links != null ? links.optJSONObject("next") : null;
        String href = next != null ? str(next, "href") : "";
        return href.startsWith(VIAPLAY_API) ? href : null;
    }

    /**
     * Films or series from a Viaplay page, only those included in the subscription now (an "svod" window around
     * nowIso, an ISO 8601 UTC time); the rest are for rent or to buy.
     */
    static List<Title> parseViaplay(JSONObject page, boolean tv, String nowIso) {
        List<Title> out = new ArrayList<>();
        JSONObject list = viaplayList(page);
        JSONArray products = list != null ? list.optJSONObject("_embedded").optJSONArray("viaplay:products") : null;
        for (int i = 0; products != null && i < products.length(); i++) {
            JSONObject p = products.optJSONObject(i);
            if (p == null || !str(p, "type").equals(tv ? "series" : "movie")) continue;
            JSONObject system = p.optJSONObject("system");
            JSONObject availability = system != null ? system.optJSONObject("availability") : null;
            JSONObject svod = availability != null ? availability.optJSONObject("svod") : null;
            if (svod == null || str(svod, "start").compareTo(nowIso) > 0
                    || (!str(svod, "end").isEmpty() && str(svod, "end").compareTo(nowIso) < 0)) continue;
            JSONObject c = p.optJSONObject("content");
            if (c == null) continue;
            JSONObject series = c.optJSONObject("series");
            Title t = new Title();
            t.tv = tv;
            t.name = tv && series != null && !str(series, "title").isEmpty() ? str(series, "title") : str(c, "title");
            JSONObject production = c.optJSONObject("production");
            int year = production != null ? production.optInt("year", 0) : 0;
            t.year = year > 0 ? String.valueOf(year) : "";
            t.overview = !str(c, "synopsis").isEmpty() || series == null ? str(c, "synopsis") : str(series, "synopsis");
            JSONObject imdb = c.optJSONObject("imdb");
            try {
                t.rating = imdb != null ? Double.parseDouble(str(imdb, "rating").replace(',', '.')) : 0;
            } catch (NumberFormatException e) {
                t.rating = 0;
            }
            JSONObject images = c.optJSONObject("images");
            if (images != null) {
                t.poster = viaplaySize(imageUrl(images, "boxart"), 184, 276);
                t.backdrop = viaplaySize(imageUrl(images, "landscape"), 960, 540);
            }
            JSONObject links = p.optJSONObject("_links");
            JSONArray genres = links != null ? links.optJSONArray("viaplay:genres") : null;
            for (int g = 0; genres != null && g < genres.length(); g++) {
                JSONObject gj = genres.optJSONObject(g);
                if (gj != null) t.tags.add(str(gj, "title").toLowerCase(Locale.ROOT));
            }
            String slug = str(p, "publicPath");
            if (!slug.isEmpty() && !slug.contains("/")) t.viaplayPath = (tv ? "/serier/" : "/film/") + slug;
            if (!t.name.isEmpty()) out.add(t);
        }
        return out;
    }

    private static String imageUrl(JSONObject images, String key) {
        JSONObject i = images.optJSONObject(key);
        return i != null ? str(i, "url") : "";
    }

    /** Viaplay's image URLs carry the size to scale to. */
    static String viaplaySize(String url, int width, int height) {
        return url.replaceFirst("width=\\d+&height=\\d+", "width=" + width + "&height=" + height);
    }

    /** One SPARQL query for the Netflix and Disney+ ids of all these titles, by their TMDb ids. */
    static String wikidataQuery(List<Title> titles) {
        StringBuilder movies = new StringBuilder(), series = new StringBuilder();
        for (Title t : titles) {
            if (t.id > 0) (t.tv ? series : movies).append(" \"").append(t.id).append('"');
        }
        return "SELECT ?m ?t ?netflix ?disney WHERE { { VALUES ?m {" + movies + " } ?item wdt:P4947 ?m . "
                + "OPTIONAL { ?item wdt:P7595 ?disney } } UNION { VALUES ?t {" + series + " } ?item wdt:P4983 ?t . "
                + "OPTIONAL { ?item wdt:P7596 ?disney } } OPTIONAL { ?item wdt:P1874 ?netflix } }";
    }

    /** Sets netflixId and disneyId from the query's answer; the first id Wikidata gives wins. */
    static void applyWikidata(JSONObject json, List<Title> titles) {
        JSONObject results = json.optJSONObject("results");
        JSONArray rows = results != null ? results.optJSONArray("bindings") : null;
        if (rows == null) return;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject b = rows.optJSONObject(i);
            if (b == null) continue;
            boolean tv = b.has("t");
            String id = value(b, tv ? "t" : "m");
            for (Title t : titles) {
                if (t.tv != tv || !String.valueOf(t.id).equals(id)) continue;
                if (t.netflixId.isEmpty()) t.netflixId = value(b, "netflix");
                if (t.disneyId.isEmpty()) t.disneyId = value(b, "disney");
            }
        }
    }

    private static String value(JSONObject binding, String key) {
        JSONObject v = binding.optJSONObject(key);
        return v != null ? v.optString("value", "").trim() : "";
    }

    /** "What We Do in the Shadows" -> "what-we-do-in-the-shadows", for Disney+ links. */
    static String slug(String name) {
        String s = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        return s.isEmpty() ? "title" : s;
    }

    /**
     * One row from each service's list: taken in turns, so every service shows near the start, and a title on
     * several services (even from different sources) appears once with all of them. A title that fails the check
     * for the service whose list it is on is left out for that service.
     */
    static List<Title> mix(List<List<Title>> lists, List<Service> services, int max, Check check) {
        List<Title> out = new ArrayList<>();
        int longest = 0;
        for (List<Title> l : lists) longest = Math.max(longest, l.size());
        for (int i = 0; i < longest; i++) {
            for (int s = 0; s < lists.size(); s++) {
                if (i >= lists.get(s).size()) continue;
                Title t = lists.get(s).get(i);
                Service service = services.get(s);
                Title known = null;
                for (Title o : out) {
                    if (o.sameAs(t)) {
                        known = o;
                        break;
                    }
                }
                if (known == null && out.size() >= max) continue;
                if (known != null && known.services.contains(service)) continue;
                if (!check.on(t, service)) continue;
                if (known != null) {
                    known.services.add(service);
                    known.absorb(t);
                } else {
                    t.services.add(service);
                    out.add(t);
                }
            }
        }
        return out;
    }

    /** JSON null comes back from optString as "null"; treat it as empty. */
    private static String str(JSONObject o, String key) {
        return o.isNull(key) ? "" : o.optString(key, "").trim();
    }
}
