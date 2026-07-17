package com.seatsync.booking.api;

import com.seatsync.booking.api.dto.SeatsResponse;
import com.seatsync.booking.api.dto.StatsResponse;
import com.seatsync.booking.api.dto.WaitlistJoinResponse;
import com.seatsync.booking.security.JwtUser;
import com.seatsync.booking.service.SeatQueryService;
import com.seatsync.booking.service.WaitlistService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/events")
public class EventViewController {

    private final SeatQueryService seatQueryService;
    private final WaitlistService waitlistService;

    public EventViewController(SeatQueryService seatQueryService, WaitlistService waitlistService) {
        this.seatQueryService = seatQueryService;
        this.waitlistService = waitlistService;
    }

    /** Public: works anonymously; "mine" is only ever true for the authenticated caller. */
    @GetMapping("/{eventId}/seats")
    public SeatsResponse seats(@PathVariable UUID eventId, @AuthenticationPrincipal JwtUser user) {
        return seatQueryService.liveSeats(eventId, user);
    }

    /** ORGANIZER or ADMIN only (enforced in the security chain). */
    @GetMapping("/{eventId}/stats")
    public StatsResponse stats(@PathVariable UUID eventId) {
        return seatQueryService.stats(eventId);
    }

    @PostMapping("/{eventId}/waitlist")
    public ResponseEntity<WaitlistJoinResponse> joinWaitlist(@PathVariable UUID eventId,
                                                             @AuthenticationPrincipal JwtUser user) {
        long position = waitlistService.join(eventId, user);
        return ResponseEntity.status(HttpStatus.CREATED).body(new WaitlistJoinResponse(position));
    }

    @DeleteMapping("/{eventId}/waitlist")
    public ResponseEntity<Void> leaveWaitlist(@PathVariable UUID eventId,
                                              @AuthenticationPrincipal JwtUser user) {
        waitlistService.leave(eventId, user);
        return ResponseEntity.noContent().build();
    }
}
