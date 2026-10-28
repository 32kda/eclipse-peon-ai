package org.sterl.llmpeon.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class SessionTabControllerTest {

    private static final SessionId A = id();
    private static final SessionId B = id();
    private static final SessionId C = id();

    private static SessionId id() {
        return new SessionId(UUID.randomUUID());
    }

    @Test
    void openCloseAndActivateMaintainTheTabOrder() {
        var controller = new SessionTabController();
        assertThat(controller.open(A)).isTrue();
        assertThat(controller.open(B)).isTrue();
        assertThat(controller.open(A)).isFalse();

        controller.activate(A);
        assertThat(controller.active()).isEqualTo(A);
        assertThat(controller.openIds()).containsExactly(A, B);

        controller.close(A);
        assertThat(controller.active()).isEqualTo(B);
        assertThat(controller.openIds()).containsExactly(B);
    }

    @Test
    void restoreKeepsOrderAndFallsBackToFirstActive() {
        var controller = new SessionTabController();

        controller.restore(List.of(A, B, C), C);

        assertThat(controller.openIds()).containsExactly(A, B, C);
        assertThat(controller.active()).isEqualTo(C);

        controller.restore(List.of(A, B), C);
        assertThat(controller.active()).isEqualTo(A);
    }

    @Test
    void closingLastTabLeavesNoActiveSession() {
        var controller = new SessionTabController();
        controller.open(A);
        controller.activate(A);

        controller.close(A);

        assertThat(controller.openIds()).isEmpty();
        assertThat(controller.active()).isNull();
    }
}
