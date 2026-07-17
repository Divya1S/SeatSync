package com.seatsync.notification.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatsync.notification.domain.Notification;
import com.seatsync.notification.domain.NotificationRepository;
import com.seatsync.notification.domain.NotificationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Claim-then-send unit tests (§6.5): the SENDING row is inserted BEFORE the
 * email leaves, and on a duplicate messageId the existing row's state drives
 * the outcome — SENT skip / FAILED retry / fresh SENDING skip / stale SENDING
 * retry. Transient send failures mark the row FAILED and THROW (feeding the
 * retry-topic machinery); poison payloads throw before any claim.
 */
@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    private static final String EVENT_ID = "8d7c2c6e-3f1a-4a71-9a3b-111111111111";
    private static final UUID MESSAGE_ID = UUID.fromString("5f0e8a9c-0000-4000-8000-333333333333");

    @Mock
    private JavaMailSender mailSender;

    @Mock
    private NotificationRepository repository;

    private NotificationService service;

    @BeforeEach
    void setUp() {
        service = new NotificationService(new ObjectMapper(), new EmailComposer(), mailSender,
                repository, "noreply@seatsync.local");
    }

    private static String payloadWithMessageId() {
        return """
                {"type":"BookingConfirmed","messageId":"%s","bookingId":"%s","eventId":"%s",
                 "eventName":"Friday Night Jazz","seatId":"A-1-4","userId":"%s",
                 "userEmail":"attendee@seatsync.local","price":49.00,
                 "occurredAt":"2026-07-16T18:00:00Z"}
                """.formatted(MESSAGE_ID, UUID.randomUUID(), EVENT_ID, UUID.randomUUID());
    }

    private static Notification existingRow(NotificationStatus status) {
        return new Notification(MESSAGE_ID, "BookingConfirmed", "attendee@seatsync.local",
                "subject", "body", UUID.fromString(EVENT_ID), "A-1-4", status);
    }

    private static DataIntegrityViolationException duplicateClaim() {
        return new DataIntegrityViolationException(
                "duplicate key value violates unique constraint \"uq_notifications_message_id\"");
    }

    // --- happy path -------------------------------------------------------

    @Test
    void claimsSendingRowBeforeSendingThenMarksSent() {
        // Snapshot the row's state at claim (insert) time — the same instance
        // is mutated to SENT after the send succeeds.
        List<NotificationStatus> statusAtClaim = new ArrayList<>();
        List<UUID> messageIdAtClaim = new ArrayList<>();
        when(repository.saveAndFlush(any(Notification.class))).thenAnswer(invocation -> {
            Notification row = invocation.getArgument(0);
            statusAtClaim.add(row.getStatus());
            messageIdAtClaim.add(row.getMessageId());
            return row;
        });

        service.process(payloadWithMessageId());

        InOrder order = inOrder(repository, mailSender);
        order.verify(repository).saveAndFlush(any(Notification.class));
        order.verify(mailSender).send(any(SimpleMailMessage.class));

        // Claim row: SENDING with the messageId, inserted before the send.
        assertThat(statusAtClaim).containsExactly(NotificationStatus.SENDING);
        assertThat(messageIdAtClaim).containsExactly(MESSAGE_ID);

        ArgumentCaptor<Notification> updated = ArgumentCaptor.forClass(Notification.class);
        order.verify(repository).save(updated.capture());
        assertThat(updated.getValue().getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(updated.getValue().getMessageId()).isEqualTo(MESSAGE_ID);
    }

    @Test
    void legacyPayloadWithoutMessageIdStillSends() {
        when(repository.saveAndFlush(any(Notification.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.process("""
                {"type":"HoldExpired","holdId":"%s","eventId":"%s",
                 "eventName":"Friday Night Jazz","seatId":"A-2-9","userId":"%s",
                 "userEmail":"slowpoke@seatsync.local","occurredAt":"2026-07-16T18:05:00Z"}
                """.formatted(UUID.randomUUID(), EVENT_ID, UUID.randomUUID()));

        ArgumentCaptor<Notification> claimed = ArgumentCaptor.forClass(Notification.class);
        verify(repository).saveAndFlush(claimed.capture());
        assertThat(claimed.getValue().getMessageId()).isNull();
        verify(mailSender).send(any(SimpleMailMessage.class));
    }

    // --- claim state machine on duplicate messageId ------------------------

    @Test
    void sentRowSkipsDuplicateDeliveryWithoutThrowing() {
        when(repository.saveAndFlush(any(Notification.class))).thenThrow(duplicateClaim());
        when(repository.findByMessageId(MESSAGE_ID))
                .thenReturn(Optional.of(existingRow(NotificationStatus.SENT)));

        assertThatCode(() -> service.process(payloadWithMessageId())).doesNotThrowAnyException();

        verify(mailSender, never()).send(any(SimpleMailMessage.class));
        verify(repository, never()).save(any(Notification.class));
    }

    @Test
    void failedRowIsRetriedOnRedeliveryAndFlipsToSent() {
        Notification failed = existingRow(NotificationStatus.FAILED);
        when(repository.saveAndFlush(any(Notification.class)))
                .thenThrow(duplicateClaim())                          // fresh claim insert conflicts
                .thenAnswer(invocation -> invocation.getArgument(0)); // re-claim of the existing row
        when(repository.findByMessageId(MESSAGE_ID)).thenReturn(Optional.of(failed));

        service.process(payloadWithMessageId());

        // Re-claim (same row back to SENDING) committed before the send.
        InOrder order = inOrder(repository, mailSender);
        order.verify(repository).saveAndFlush(any(Notification.class));
        order.verify(repository).findByMessageId(MESSAGE_ID);
        order.verify(repository).saveAndFlush(failed);
        order.verify(mailSender).send(any(SimpleMailMessage.class));

        ArgumentCaptor<Notification> updated = ArgumentCaptor.forClass(Notification.class);
        order.verify(repository).save(updated.capture());
        assertThat(updated.getValue()).isSameAs(failed);
        assertThat(updated.getValue().getStatus()).isEqualTo(NotificationStatus.SENT);
    }

    @Test
    void freshSendingClaimIsSkippedAsConcurrentInFlight() {
        // Constructor stamps updatedAt = now → fresher than the 5-min window.
        when(repository.saveAndFlush(any(Notification.class))).thenThrow(duplicateClaim());
        when(repository.findByMessageId(MESSAGE_ID))
                .thenReturn(Optional.of(existingRow(NotificationStatus.SENDING)));

        assertThatCode(() -> service.process(payloadWithMessageId())).doesNotThrowAnyException();

        verify(mailSender, never()).send(any(SimpleMailMessage.class));
        verify(repository, never()).save(any(Notification.class));
    }

    @Test
    void staleSendingClaimIsTreatedAsCrashedAttemptAndRetried() {
        Notification stale = existingRow(NotificationStatus.SENDING);
        ReflectionTestUtils.setField(stale, "updatedAt",
                Instant.now().minus(Duration.ofMinutes(6)));
        when(repository.saveAndFlush(any(Notification.class)))
                .thenThrow(duplicateClaim())
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(repository.findByMessageId(MESSAGE_ID)).thenReturn(Optional.of(stale));

        service.process(payloadWithMessageId());

        verify(mailSender).send(any(SimpleMailMessage.class));
        ArgumentCaptor<Notification> updated = ArgumentCaptor.forClass(Notification.class);
        verify(repository).save(updated.capture());
        assertThat(updated.getValue()).isSameAs(stale);
        assertThat(updated.getValue().getStatus()).isEqualTo(NotificationStatus.SENT);
    }

    // --- failure classification --------------------------------------------

    @Test
    void mailFailureMarksRowFailedAndThrowsTransient() {
        when(repository.saveAndFlush(any(Notification.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        doThrow(new MailSendException("boom")).when(mailSender).send(any(SimpleMailMessage.class));

        assertThatThrownBy(() -> service.process(payloadWithMessageId()))
                .isInstanceOf(MailSendException.class);

        ArgumentCaptor<Notification> updated = ArgumentCaptor.forClass(Notification.class);
        verify(repository).save(updated.capture());
        assertThat(updated.getValue().getStatus()).isEqualTo(NotificationStatus.FAILED);
    }

    @Test
    void poisonPayloadThrowsWithoutClaimingOrSending() {
        assertThatThrownBy(() -> service.process("{ this is not json"))
                .isInstanceOf(PoisonMessageException.class);
        assertThatThrownBy(() -> service.process("""
                {"type":"SomethingElse","userEmail":"a@b.c"}
                """))
                .isInstanceOf(PoisonMessageException.class);
        assertThatThrownBy(() -> service.process("""
                {"type":"BookingConfirmed","eventName":"No recipient","seatId":"A-1-1"}
                """))
                .isInstanceOf(PoisonMessageException.class);

        verifyNoInteractions(repository, mailSender);
    }

    @Test
    void unclaimableRowThrowsTransientWithoutSending() {
        when(repository.saveAndFlush(any(Notification.class)))
                .thenThrow(new DataAccessResourceFailureException("database unavailable"));

        assertThatThrownBy(() -> service.process(payloadWithMessageId()))
                .isInstanceOf(DataAccessResourceFailureException.class);

        verify(mailSender, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    void integrityViolationWithoutMessageIdPropagates() {
        when(repository.saveAndFlush(any(Notification.class))).thenThrow(
                new DataIntegrityViolationException("value too long for column"));

        assertThatThrownBy(() -> service.process("""
                {"type":"HoldExpired","holdId":"%s","eventId":"%s",
                 "eventName":"Friday Night Jazz","seatId":"A-2-9","userId":"%s",
                 "userEmail":"slowpoke@seatsync.local","occurredAt":"2026-07-16T18:05:00Z"}
                """.formatted(UUID.randomUUID(), EVENT_ID, UUID.randomUUID())))
                .isInstanceOf(DataIntegrityViolationException.class);

        verify(mailSender, never()).send(any(SimpleMailMessage.class));
    }
}
