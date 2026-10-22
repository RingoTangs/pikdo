package io.github.ringotangs.pikdo.server.identity.application;

import io.github.ringotangs.pikdo.server.identity.persistence.*;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.*;

@Component
public final class AuthenticationCleanup {
    private final EmailChallengeMapper challenges;
    private final AuthenticationRateMapper rates;
    private final Clock clock;

    public AuthenticationCleanup(
            EmailChallengeMapper challenges, AuthenticationRateMapper rates, Clock clock) {
        this.challenges = challenges;
        this.rates = rates;
        this.clock = clock;
    }

    @Scheduled(fixedDelay = 3600000, initialDelay = 3600000)
    public void clean() {
        var now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        challenges.cleanup(now.minusHours(24));
        rates.cleanup(now);
    }
}
