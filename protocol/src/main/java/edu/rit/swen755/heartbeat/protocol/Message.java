package edu.rit.swen755.heartbeat.protocol;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * A datagram payload exchanged over one of the three UDP hops.
 *
 * <p>Every message carries a {@code type} discriminator in its JSON form. The discriminator is
 * managed by Jackson via {@link JsonTypeInfo}/{@link JsonSubTypes} on this sealed interface, so it
 * is not a record component: it appears in the wire JSON (e.g. {@code "type":"Heartbeat"}) but is
 * consumed by the type resolver rather than passed to the record constructor.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = SensorReading.class, name = "SensorReading"),
        @JsonSubTypes.Type(value = Heartbeat.class, name = "Heartbeat"),
        @JsonSubTypes.Type(value = StatusReport.class, name = "StatusReport")
})
public sealed interface Message permits SensorReading, Heartbeat, StatusReport {
}
