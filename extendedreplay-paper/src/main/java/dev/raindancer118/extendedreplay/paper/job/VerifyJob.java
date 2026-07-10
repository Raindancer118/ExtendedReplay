package dev.raindancer118.extendedreplay.paper.job;

import dev.raindancer118.extendedreplay.storage.ReplayStorage;
import dev.raindancer118.extendedreplay.storage.meta.SessionRecord;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Runs session verification (checksums, degradation markers, integrity classification) as
 * a {@link JobManager} job instead of blocking a plain async task with no progress
 * reporting. Used by both {@code /erp verify} and the session details GUI so the two
 * entry points share one implementation.
 */
public final class VerifyJob {

    private VerifyJob() {
    }

    /**
     * Submits the verification job. {@code report} is invoked once per output line (mirrors
     * the pre-existing {@code /erp verify} chat output): a single summary line prefixed with
     * the resulting integrity classification, followed by up to five problem lines (plus an
     * ellipsis line if there were more).
     */
    public static Job submit(JobManager jobs, ReplayStorage storage, UUID sessionId,
                             String sessionName, Consumer<String> report) {
        return jobs.submit("Verifizierung " + sessionName, "Session " + sessionId, job -> {
            job.setProgress(10);
            List<String> problems = storage.verifySession(sessionId);

            job.setProgress(50);
            if (job.cancelRequested()) {
                job.acknowledgeCancel();
                job.setMessage("Abgebrochen.");
                return;
            }
            boolean degraded = storage.hasDegradationMarkers(sessionId);

            job.setProgress(80);
            SessionRecord record = storage.getSession(sessionId).orElse(null);
            boolean hasSnapshot = record != null && record.snapshotName() != null;
            boolean finished = record != null && record.isFinished();
            String integrity = ReplayStorage.classifyIntegrity(problems, degraded, hasSnapshot, finished);
            storage.database().setIntegrity(sessionId, integrity);
            job.setProgress(100);
            job.setMessage(integrity);

            if (problems.isEmpty()) {
                report.accept("✔ Integrität: " + integrity + " — Session ist intakt (alle Checksummen OK).");
            } else {
                report.accept("✘ Integrität: " + integrity + " — " + problems.size() + " Problem(e):");
                int max = Math.min(5, problems.size());
                for (int i = 0; i < max; i++) {
                    report.accept("  " + problems.get(i));
                }
                if (problems.size() > 5) {
                    report.accept("  …");
                }
            }
        });
    }
}
