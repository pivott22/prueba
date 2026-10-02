package cl.pokeofertas.reader;

import java.net.URI;
import java.util.List;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ListingPolicy {
    public static final String SELLER = "550072427";
    public static final String START = "https://listado.mercadolibre.cl/pokemon_CustId_550072427_BRAND_12067163_NoIndex_True?sb=seller_id";
    private static final Pattern SELLER_PATH = Pattern.compile("_CustId_(\\d+)(?:_|$)", Pattern.CASE_INSENSITIVE);
    private static final List<String> HOSTS = Arrays.asList("www.mercadolibre.cl", "listado.mercadolibre.cl", "articulo.mercadolibre.cl", "accounts.mercadolibre.cl");

    public static boolean listing(String value) {
        try {
            URI uri = URI.create(value);
            if (!"https".equals(uri.getScheme()) || !"listado.mercadolibre.cl".equals(uri.getHost())
                    || uri.getUserInfo() != null || uri.getPort() != -1) return false;
            Matcher matcher = SELLER_PATH.matcher(uri.getPath());
            return matcher.find() && SELLER.equals(matcher.group(1));
        } catch (Exception e) { return false; }
    }
    public static boolean firstPage(String value) {
        if (!listing(value)) return false;
        Matcher start = Pattern.compile("_Desde_(\\d+)", Pattern.CASE_INSENSITIVE).matcher(URI.create(value).getPath());
        return !start.find() || "1".equals(start.group(1));
    }
    public static boolean allowedBrowserPage(String value) {
        try {
            URI uri = URI.create(value);
            return "https".equals(uri.getScheme()) && HOSTS.contains(uri.getHost())
                && uri.getUserInfo() == null && uri.getPort() == -1;
        } catch (Exception e) { return false; }
    }
    public static boolean product(String value) {
        try {
            URI uri = URI.create(value);
            return "https".equals(uri.getScheme()) && Arrays.asList("www.mercadolibre.cl", "articulo.mercadolibre.cl").contains(uri.getHost())
                && uri.getUserInfo() == null && uri.getPort() == -1
                && Pattern.compile("/(?:p|up)/MLCU?\\d+(?:/|$)|/MLC-?\\d+(?:-|/|$)").matcher(uri.getPath()).find();
        } catch (Exception e) { return false; }
    }
    public static String withoutHash(String value) { return value == null ? "" : value.split("#", 2)[0]; }
    public static String publicLocation(String value) {
        try {
            URI uri = URI.create(value);
            return uri.getScheme() + "://" + uri.getHost() + uri.getPath();
        } catch (Exception e) { return "Dirección no disponible"; }
    }
    private ListingPolicy() {}
}
