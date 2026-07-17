package com.seatsync.booking.api;

import com.seatsync.booking.api.dto.HoldRequest;
import com.seatsync.booking.api.dto.HoldSummary;
import com.seatsync.booking.idempotency.IdempotencyService;
import com.seatsync.booking.security.JwtUser;
import com.seatsync.booking.service.HoldService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/holds")
public class HoldController {

    private final HoldService holdService;
    private final IdempotencyService idempotencyService;

    public HoldController(HoldService holdService, IdempotencyService idempotencyService) {
        this.holdService = holdService;
        this.idempotencyService = idempotencyService;
    }

    /**
     * 201 for a fresh hold; 200 when the caller already holds this seat (idempotent
     * retry). An optional {@code Idempotency-Key} header replays the stored outcome
     * of an earlier identical request instead of re-executing (conventions 6.3).
     */
    @PostMapping
    public ResponseEntity<?> createHold(@Valid @RequestBody HoldRequest request,
                                        @AuthenticationPrincipal JwtUser user,
                                        HttpServletRequest httpRequest) {
        return idempotencyService.execute(user, httpRequest, () -> {
            HoldService.HoldResult result = holdService.createHold(request.eventId(), request.seatId(), user);
            return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                    .body(result.hold());
        });
    }

    @DeleteMapping("/{holdId}")
    public ResponseEntity<Void> releaseHold(@PathVariable UUID holdId,
                                            @AuthenticationPrincipal JwtUser user) {
        holdService.releaseHold(holdId, user);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/mine")
    public List<HoldSummary> myHolds(@AuthenticationPrincipal JwtUser user) {
        return holdService.myActiveHolds(user);
    }
}
