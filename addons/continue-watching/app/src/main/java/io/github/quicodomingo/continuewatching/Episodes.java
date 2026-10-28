package io.github.quicodomingo.continuewatching;

/** Small rules about episode data from Cinemeta, kept apart so the JVM tests can reach them. */
final class Episodes {

    private Episodes() {
    }

    /** Cinemeta fills unnamed future episodes with placeholders like "TBA". */
    static boolean hasRealName(String name) {
        if (name == null) return false;
        String n = name.trim();
        return !n.isEmpty() && !n.equalsIgnoreCase("TBA") && !n.equalsIgnoreCase("TBD");
    }

    /** {season, episode} from a series video id like "tt0944947:1:5", or null. */
    static int[] seasonEpisode(String videoId) {
        if (videoId == null) return null;
        String[] p = videoId.split(":");
        if (p.length < 3) return null;
        try {
            return new int[]{Integer.parseInt(p[p.length - 2]), Integer.parseInt(p[p.length - 1])};
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
