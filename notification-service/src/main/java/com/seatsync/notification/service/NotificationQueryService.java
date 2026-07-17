package com.seatsync.notification.service;

import com.seatsync.notification.api.NotificationResponse;
import com.seatsync.notification.domain.Notification;
import com.seatsync.notification.domain.NotificationRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Read-side facade between the API layer and the persistence layer (§8.1:
 * controllers must not touch repositories or entities). Loads the latest
 * notifications and maps them to the {@link NotificationResponse} DTO so
 * only DTOs cross the API edge.
 */
@Service
public class NotificationQueryService {

    private final NotificationRepository repository;

    public NotificationQueryService(NotificationRepository repository) {
        this.repository = repository;
    }

    public List<NotificationResponse> recent(int limit) {
        return repository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, limit)).stream()
                .map(NotificationQueryService::toResponse)
                .toList();
    }

    private static NotificationResponse toResponse(Notification notification) {
        return new NotificationResponse(
                notification.getId(),
                notification.getType(),
                notification.getRecipient(),
                notification.getSubject(),
                notification.getBody(),
                notification.getEventId(),
                notification.getSeatId(),
                notification.getStatus().name(),
                notification.getCreatedAt());
    }
}
