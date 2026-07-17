package com.seatsync.booking.api;

import com.seatsync.booking.api.dto.BookingRequest;
import com.seatsync.booking.api.dto.BookingResponse;
import com.seatsync.booking.idempotency.IdempotencyService;
import com.seatsync.booking.security.JwtUser;
import com.seatsync.booking.service.BookingService;
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
@RequestMapping("/api/bookings")
public class BookingController {

    private final BookingService bookingService;
    private final IdempotencyService idempotencyService;

    public BookingController(BookingService bookingService, IdempotencyService idempotencyService) {
        this.bookingService = bookingService;
        this.idempotencyService = idempotencyService;
    }

    /**
     * 201 for a fresh booking; 200 when an already-confirmed hold is replayed
     * (idempotent retry). An optional {@code Idempotency-Key} header replays the
     * stored outcome of an earlier identical request instead of re-executing
     * (conventions 6.3) — including 4xx outcomes whose domain transaction rolled back.
     */
    @PostMapping
    public ResponseEntity<?> confirm(@Valid @RequestBody BookingRequest request,
                                     @AuthenticationPrincipal JwtUser user,
                                     HttpServletRequest httpRequest) {
        return idempotencyService.execute(user, httpRequest, () -> {
            BookingService.ConfirmResult result = bookingService.confirm(request.holdId(), user);
            return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                    .body(result.booking());
        });
    }

    @GetMapping("/mine")
    public List<BookingResponse> myBookings(@AuthenticationPrincipal JwtUser user) {
        return bookingService.myBookings(user);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> cancel(@PathVariable UUID id, @AuthenticationPrincipal JwtUser user) {
        bookingService.cancel(id, user);
        return ResponseEntity.noContent().build();
    }
}
