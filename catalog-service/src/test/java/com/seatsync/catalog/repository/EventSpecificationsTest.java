package com.seatsync.catalog.repository;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EventSpecificationsTest {

    @Test
    void escapesEveryLikeMetacharacter() {
        assertThat(EventSpecifications.escapeLike("100% sold_out \\ now"))
                .isEqualTo("100\\% sold\\_out \\\\ now");
    }

    @Test
    void leavesOrdinaryTextUntouched() {
        assertThat(EventSpecifications.escapeLike("friday night jazz")).isEqualTo("friday night jazz");
        assertThat(EventSpecifications.escapeLike("")).isEmpty();
    }
}
