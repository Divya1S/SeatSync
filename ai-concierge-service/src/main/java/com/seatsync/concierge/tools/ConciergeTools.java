package com.seatsync.concierge.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Spring AI tools registered on the concierge ChatClient. Each tool flips the
 * per-request {@link ToolInvocationTracker} flag (confidence heuristic: tool
 * used ⇒ HIGH) and delegates to {@link DownstreamClients}, which carries the
 * {@code downstream} circuit breaker. The try/catch here is a last line of
 * defense: a tool never throws into the LLM loop; it returns a
 * {@code TOOL_UNAVAILABLE} string so the model reports missing data instead of
 * guessing. Each turn also has a tool budget ({@value #MAX_TOOL_CALLS_PER_TURN}):
 * once exhausted, tools answer {@code TOOL_BUDGET_EXCEEDED} without touching the
 * downstream services, which bounds both runaway loops and cost.
 */
@Component
public class ConciergeTools {

    /** Three tools, a couple of calls each, is a generous turn; more is a loop. */
    static final int MAX_TOOL_CALLS_PER_TURN = 8;

    static final String TOOL_BUDGET_EXCEEDED = "TOOL_BUDGET_EXCEEDED: no further tool calls are allowed "
            + "in this turn; answer from the data already gathered or say you are not sure.";

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
        if (tracker.markInvoked() > MAX_TOOL_CALLS_PER_TURN) {
            return TOOL_BUDGET_EXCEEDED;
        }
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
        if (tracker.markInvoked() > MAX_TOOL_CALLS_PER_TURN) {
            return TOOL_BUDGET_EXCEEDED;
        }
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
        if (tracker.markInvoked() > MAX_TOOL_CALLS_PER_TURN) {
            return TOOL_BUDGET_EXCEEDED;
        }
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
