package com.onlineexam.service;

import com.onlineexam.domain.Attempt;
import com.onlineexam.repository.AttemptRepository;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Recovers overdue attempts after startup and on a bounded periodic scan. */
@Component
public class ExpiryRecovery implements ApplicationRunner {
    private static final Logger LOG = LoggerFactory.getLogger(ExpiryRecovery.class);
    private final AttemptRepository attempts;
    private final FinalizationService finalization;
    private final Clock clock;
    private final int batchSize;
    private long lastScannedId;
    private long cycleMaxId;

    public ExpiryRecovery(AttemptRepository attempts, FinalizationService finalization, Clock clock,
                          @Value("${exam.expiry.batch-size:100}") int batchSize) {
        if (batchSize < 1 || batchSize > 1000) {
            throw new IllegalArgumentException("Expiry batch size must be between 1 and 1000");
        }
        this.attempts = attempts;
        this.finalization = finalization;
        this.clock = clock;
        this.batchSize = batchSize;
    }

    @Override
    public void run(ApplicationArguments ignored) {
        scanBatch();
    }

    @Scheduled(fixedDelayString = "${exam.expiry.scan-delay-ms:15000}",
               initialDelayString = "${exam.expiry.scan-delay-ms:15000}")
    public void scheduledScan() {
        scanBatch();
    }

    /** Each attempt is finalized in its own transaction, so one failure cannot undo the batch. */
    public synchronized int scanBatch() {
        int processed = 0;
        var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (cycleMaxId == 0) cycleMaxId = attempts.maxOverdueId(now);
        if (cycleMaxId == 0) return 0;
        List<Attempt> overdue = attempts.findOverdueAfter(now, lastScannedId, cycleMaxId,
                batchSize);
        if (overdue.isEmpty()) {
            lastScannedId = 0;
            cycleMaxId = attempts.maxOverdueId(now);
            if (cycleMaxId == 0) return 0;
            overdue = attempts.findOverdueAfter(now, 0, cycleMaxId, batchSize);
        }
        for (Attempt attempt : overdue) {
            lastScannedId = attempt.id();
            try {
                finalization.finalizeIfExpired(attempt.id());
                processed++;
            } catch (RuntimeException failure) {
                LOG.warn("Could not recover overdue attempt {} ({}); will retry",
                        attempt.id(), failure.getClass().getSimpleName());
            }
        }
        return processed;
    }
}
