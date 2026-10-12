package com.seatsync.booking.service;

import com.seatsync.booking.catalog.CatalogGateway;
import com.seatsync.booking.catalog.EventDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Three-tier name resolution: memory, the event_names table, then catalog; and
 * never a blank result, because the event schemas require eventName minLength 1.
 */
@ExtendWith(MockitoExtension.class)
class EventNameResolverTest {

    @Mock
    private CatalogGateway catalogGateway;
    @Mock
    private JdbcTemplate jdbcTemplate;

    private EventNameResolver resolver;

    private final UUID eventId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        resolver = new EventNameResolver(catalogGateway, jdbcTemplate);
    }

    @Test
    void storedNameIsUsedWithoutCallingCatalog() {
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), any()))
                .thenReturn(List.of("Stored Night"));

        assertThat(resolver.resolve(eventId)).isEqualTo("Stored Night");

        verifyNoInteractions(catalogGateway);
        // Second lookup is served from memory.
        assertThat(resolver.resolve(eventId)).isEqualTo("Stored Night");
        verify(jdbcTemplate, times(1)).queryForList(anyString(), eq(String.class), any());
    }

    @Test
    void catalogNameIsPersistedForNextTime() {
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), any())).thenReturn(List.of());
        when(catalogGateway.getEvent(eventId)).thenReturn(new EventDto(eventId, "Friday Night Jazz", "PUBLISHED"));

        assertThat(resolver.resolve(eventId)).isEqualTo("Friday Night Jazz");

        verify(jdbcTemplate).update(contains("INSERT INTO event_names"), eq(eventId), eq("Friday Night Jazz"));
        assertThat(resolver.resolve(eventId)).isEqualTo("Friday Night Jazz");
        verify(catalogGateway, times(1)).getEvent(eventId);
    }

    @Test
    void unresolvableNameFallsBackToThePlaceholderNeverBlank() {
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), any())).thenReturn(List.of());
        when(catalogGateway.getEvent(eventId)).thenThrow(new IllegalStateException("catalog down"));

        String name = resolver.resolve(eventId);

        assertThat(name).isEqualTo(EventNameResolver.UNKNOWN_EVENT_NAME).isNotBlank();
        verify(jdbcTemplate, never()).update(anyString(), any(), any());
    }

    @Test
    void databaseOutageDegradesToCatalogThenPlaceholder() {
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), any()))
                .thenThrow(new DataAccessResourceFailureException("db down"));
        when(catalogGateway.getEvent(eventId)).thenReturn(null);

        assertThat(resolver.resolve(eventId)).isEqualTo(EventNameResolver.UNKNOWN_EVENT_NAME);
    }

    @Test
    void cacheIgnoresBlankNamesAndPersistsRealOnes() {
        resolver.cache(eventId, "   ");
        verifyNoInteractions(jdbcTemplate);

        resolver.cache(eventId, "Jazz");
        verify(jdbcTemplate).update(contains("event_names"), eq(eventId), eq("Jazz"));
        assertThat(resolver.resolve(eventId)).isEqualTo("Jazz");
        verifyNoInteractions(catalogGateway);
    }
}
