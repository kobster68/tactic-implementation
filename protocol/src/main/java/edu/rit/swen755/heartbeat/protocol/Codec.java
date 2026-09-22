package edu.rit.swen755.heartbeat.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;

/**
 * UTF-8 JSON encoder/decoder for {@link Message}.
 *
 * <p>{@link #decode(byte[])} performs the parse <em>and</em> the record validation in one call and
 * catches nothing: truncated bytes surface as a Jackson stream/parse exception, and an out-of-range
 * value surfaces as the record's {@link IllegalArgumentException} wrapped by Jackson. The critical
 * service leaves this call uncaught, which is exactly the fault crash the tactic detects.
 */
public final class Codec {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Codec() {
    }

    /** Serialises a message to UTF-8 JSON bytes, including its {@code type} discriminator. */
    public static byte[] encode(Message message) throws IOException {
        return MAPPER.writeValueAsBytes(message);
    }

    /**
     * Parses and validates a datagram payload. Never catches: any parse error or record-validation
     * failure propagates to the caller.
     */
    public static Message decode(byte[] bytes) throws IOException {
        return MAPPER.readValue(bytes, Message.class);
    }
}
