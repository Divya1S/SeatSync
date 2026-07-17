package com.seatsync.concierge.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Spring AI tools registered on the concierge ChatClient. Each tool flips the
 * per-request {@link ToolInvocationTracker} flag (confidence heuristic: tool
 * used ⇒ HIGH) and delegates to {@link DownstreamClients}, which carries the
 * {@code downstream} circuit breaker. The try/catch here is a last line of
 * defense: a tool NEVER throws into the LLM loop — it returns a
 * {@code TOOL_UNAVAILABLE} string so the model reports missing data instead of
 * guessing.
 */
@Component
public class ConciergeTools {

    private final DownstreamClients downstreamClients;
    private final ToolInvocationTracker tracker;

    public ConciergeTools(DownstreamClients downstreamClients, ToolInvocationTracker tracker) {
        this.downstreamClients = downstreamClients;
        this.tracker = tracker;
    }

    @Tool(description = """
            Search published SeatSync events. All filters are optional. Returns live data: \
            a JSON array of up to 5 events with id, name, venue, city, startsAt (ISO-8601 UTC) \
            and priceFrom, or TOOL_UNAVAILABLE if the catalog cannot be reached.""")
    public String searchEvents(
            @ToolParam(description = "Free-text search over event names and descriptions", required = false) String query,
            @ToolParam(description = "Filter by venue city", required = false) String city,
            @ToolParam(description = "Filter by category: CONCERT, WORKSHOP, SPORTS, CAMPUS or OTHER", required = false) String category) {
        tracker.markInvoked();
        try {
            return downstreamClients.searchEvents(query, city, category);
        } catch (Exception ex) {
            return DownstreamClients.TOOL_UNAVAILABLE_PREFIX + safeMessage(ex);
        }
    }

    @Tool(description = """
            Get the full live details of one SeatSync event by its id (UUID): name, description, \
            category, venue, start/end times, status, total seats and price range. Returns \
            NOT_FOUND if the event does not exist, or TOOL_UNAVAILABLE if the catalog is down.""")
    public String getEventDetails(
            @ToolParam(description = "The event id (UUID)") String eventId) {
        tracker.markInvoked();
        try {
            return downstreamClients.getEventDetails(eventId);
        } catch (Exception ex) {
            return DownstreamClients.TOOL_UNAVAILABLE_PREFIX + safeMessage(ex);
        }
    }

    @Tool(description = """
            Get live seat availability counts for one SeatSync event by its id (UUID): total, \
            available, held and booked seat counts. Returns TOOL_UNAVAILABLE if the booking \
            service is down.""")
    public String getSeatAvailability(
            @ToolParam(description = "The event id (UUID)") String eventId) {
        tracker.markInvoked();
        try {
            return downstreamClients.getSeatAvailability(eventId);
        } catch (Exception ex) {
            return DownstreamClients.TOOL_UNAVAILABLE_PREFIX + safeMessage(ex);
        }
    }

    private static String safeMessage(Exception ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
    }
}
