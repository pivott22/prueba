package cl.pokeofertas.reader;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class Jobs {
    private Jobs() {}
    // Serializes Telegram calls and acknowledgements, including manual retries.
    static final ExecutorService NETWORK = Executors.newSingleThreadExecutor();
}
