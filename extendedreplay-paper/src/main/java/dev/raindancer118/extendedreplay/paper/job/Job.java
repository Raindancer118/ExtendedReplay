package dev.raindancer118.extendedreplay.paper.job;

/**
 * One tracked background job. Instances are created and progressed by {@link JobManager}
 * only; the running {@link JobBody} reports progress through {@link #setProgress(int)}
 * and {@link #setMessage(String)}, and observes {@link #cancelRequested()} to abort early.
 * Every field the body doesn't own itself (status, finish time) is set by the manager once
 * the body returns or throws.
 */
public final class Job {

    /** Lifecycle state. Terminal states are {@code COMPLETED}, {@code FAILED}, {@code CANCELLED}. */
    public enum Status {
        RUNNING, COMPLETED, FAILED, CANCELLED
    }

    private final int id;
    private final String name;
    private final String detail;
    private final long startedAtMillis;

    private volatile Status status = Status.RUNNING;
    /** 0-100, or -1 while the job's total work is unknown/indeterminate. */
    private volatile int progress;
    private volatile String message = "";
    private volatile long finishedAtMillis;
    private volatile boolean cancelRequested;
    private volatile boolean cancelAcknowledged;

    Job(int id, String name, String detail) {
        this.id = id;
        this.name = name;
        this.detail = detail;
        this.startedAtMillis = System.currentTimeMillis();
    }

    public int id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String detail() {
        return detail;
    }

    public Status status() {
        return status;
    }

    public int progress() {
        return progress;
    }

    public String message() {
        return message;
    }

    public long startedAtMillis() {
        return startedAtMillis;
    }

    public long finishedAtMillis() {
        return finishedAtMillis;
    }

    public boolean cancelRequested() {
        return cancelRequested;
    }

    /** Marks the job for cooperative cancellation; the job body decides when/whether to honor it. */
    public void requestCancel() {
        this.cancelRequested = true;
    }

    /**
     * Whether the job body actually stopped early because of a cancellation request (as
     * opposed to merely having one pending while it ran to completion regardless).
     */
    public boolean cancelAcknowledged() {
        return cancelAcknowledged;
    }

    /**
     * Called by the running job body once it decides to actually abort because of a
     * cancellation request — as opposed to {@link #cancelRequested()}, which is set the
     * moment cancellation is asked for and stays true even if the body finishes its real
     * work anyway. Only bodies that call this end up {@code CANCELLED}; everything else
     * that returns normally is {@code COMPLETED}, matching this class's contract that the
     * body decides when/whether to honor a cancellation request.
     */
    public void acknowledgeCancel() {
        this.cancelAcknowledged = true;
    }

    /** Called by the running job body to report progress (0-100, or -1 if indeterminate). */
    public void setProgress(int progress) {
        this.progress = progress;
    }

    /** Called by the running job body to report a short human-readable status line. */
    public void setMessage(String message) {
        this.message = message != null ? message : "";
    }

    // --- manager-only lifecycle transitions ---

    void setStatus(Status status) {
        this.status = status;
    }

    void setFinishedAtMillis(long finishedAtMillis) {
        this.finishedAtMillis = finishedAtMillis;
    }
}
