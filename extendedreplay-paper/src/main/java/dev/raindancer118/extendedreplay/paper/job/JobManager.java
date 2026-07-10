package dev.raindancer118.extendedreplay.paper.job;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

/**
 * Tracks long-running background operations (verify, reindex, …) so GUIs/commands can
 * show live progress instead of a single fire-and-forget message. Every submitted
 * {@link JobBody} runs on a Bukkit async task; the manager wraps it so a thrown exception
 * always ends up as a {@code FAILED} job instead of a silently swallowed stack trace, and
 * a cooperative cancellation request that the body honored ends up as {@code CANCELLED}.
 *
 * <p>Thread-safe: {@link #submit} may be called from the main thread (GUI clicks, commands)
 * while jobs run and update themselves from the async pool.</p>
 */
public final class JobManager {

    private static final long MAX_FINISHED_AGE_MILLIS = 60L * 60 * 1000; // 1h
    private static final int MAX_JOBS = 100;

    /** The work a submitted job performs; receives its own {@link Job} to report progress on. */
    @FunctionalInterface
    public interface JobBody {
        void run(Job job) throws Exception;
    }

    private final Plugin plugin;
    private final Map<Integer, Job> jobs = new ConcurrentHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger();

    public JobManager(Plugin plugin) {
        this.plugin = plugin;
    }

    /** Submits and starts a job asynchronously; returns immediately with the tracking handle. */
    public Job submit(String name, String detail, JobBody body) {
        Job job = new Job(nextId.incrementAndGet(), name, detail);
        jobs.put(job.id(), job);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                body.run(job);
                job.setStatus(job.cancelRequested() ? Job.Status.CANCELLED : Job.Status.COMPLETED);
            } catch (Exception e) {
                job.setStatus(Job.Status.FAILED);
                job.setMessage(e.getMessage() != null ? e.getMessage() : e.toString());
                plugin.getLogger().log(Level.WARNING, "Job '" + name + "' failed", e);
            } finally {
                job.setFinishedAtMillis(System.currentTimeMillis());
                pruneFinished();
            }
        });
        return job;
    }

    /** All tracked jobs, newest first. */
    public List<Job> jobs() {
        return jobs.values().stream()
                .sorted(Comparator.comparingInt(Job::id).reversed())
                .toList();
    }

    public Optional<Job> byId(int id) {
        return Optional.ofNullable(jobs.get(id));
    }

    /** Requests cancellation of a still-running job; a no-op for unknown/finished ids. */
    public void cancel(int id) {
        Job job = jobs.get(id);
        if (job != null && job.status() == Job.Status.RUNNING) {
            job.requestCancel();
        }
    }

    /**
     * Removes finished jobs older than one hour, then — if still over the hard cap —
     * removes the oldest finished jobs (never a still-{@code RUNNING} one) until the cap
     * is met. Returns the number of jobs removed.
     */
    public int pruneFinished() {
        int removed = 0;
        long cutoff = System.currentTimeMillis() - MAX_FINISHED_AGE_MILLIS;
        for (Job job : jobs.values()) {
            if (job.status() != Job.Status.RUNNING && job.finishedAtMillis() > 0
                    && job.finishedAtMillis() < cutoff) {
                if (jobs.remove(job.id(), job)) {
                    removed++;
                }
            }
        }
        int overflow = jobs.size() - MAX_JOBS;
        if (overflow > 0) {
            List<Job> oldestFinishedFirst = jobs.values().stream()
                    .filter(job -> job.status() != Job.Status.RUNNING)
                    .sorted(Comparator.comparingLong(Job::startedAtMillis))
                    .toList();
            for (Job job : oldestFinishedFirst) {
                if (overflow <= 0) {
                    break;
                }
                if (jobs.remove(job.id(), job)) {
                    removed++;
                    overflow--;
                }
            }
        }
        return removed;
    }
}
