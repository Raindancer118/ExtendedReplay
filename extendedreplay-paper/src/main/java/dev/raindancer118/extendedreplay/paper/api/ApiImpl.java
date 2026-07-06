package dev.raindancer118.extendedreplay.paper.api;

import dev.raindancer118.extendedreplay.api.ExtendedReplayApi;
import dev.raindancer118.extendedreplay.api.ReplayBookmark;
import dev.raindancer118.extendedreplay.api.ReplayEvent;
import dev.raindancer118.extendedreplay.api.ReplayEventCategory;
import dev.raindancer118.extendedreplay.api.ReplaySessionEndReason;
import dev.raindancer118.extendedreplay.api.ReplaySessionHandle;
import dev.raindancer118.extendedreplay.api.ReplaySessionStartRequest;
import dev.raindancer118.extendedreplay.paper.producer.ActiveSession;
import dev.raindancer118.extendedreplay.paper.producer.ProducerManager;
import org.bukkit.Bukkit;
import org.bukkit.World;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link ExtendedReplayApi} backed by the producer manager. Registered in the Bukkit
 * ServicesManager when this server records (PRODUCER/STANDALONE).
 */
public final class ApiImpl implements ExtendedReplayApi {

    private final ProducerManager producer;
    private final Map<String, String> eventTypeLabels = new ConcurrentHashMap<>();

    public ApiImpl(ProducerManager producer) {
        this.producer = producer;
    }

    @Override
    public ReplaySessionHandle startSession(ReplaySessionStartRequest request) {
        World world = Bukkit.getWorld(request.worldName());
        if (world == null) {
            throw new IllegalStateException("World not loaded: " + request.worldName());
        }
        ActiveSession session = producer.startSession(request.name(), request.externalKey(),
                world, request.bounds(), request.metadata());
        return new Handle(session);
    }

    @Override
    public void endSession(UUID sessionId, ReplaySessionEndReason reason) {
        producer.endSession(sessionId, reason);
    }

    @Override
    public void recordEvent(UUID sessionId, ReplayEvent event) {
        producer.sessionById(sessionId).ifPresent(session ->
                producer.recordApiEvent(session, event));
    }

    @Override
    public void createBookmark(UUID sessionId, ReplayBookmark bookmark) {
        producer.sessionById(sessionId).ifPresent(session ->
                producer.recordBookmark(session, bookmark));
    }

    @Override
    public Optional<ReplaySessionHandle> getActiveSession(World world) {
        return producer.sessionForWorld(world.getName()).map(Handle::new);
    }

    @Override
    public Optional<ReplaySessionHandle> getActiveSession(String externalSessionKey) {
        return producer.sessionByExternalKey(externalSessionKey).map(Handle::new);
    }

    @Override
    public List<ReplaySessionHandle> getActiveSessions() {
        return producer.activeSessions().stream()
                .map(s -> (ReplaySessionHandle) new Handle(s))
                .toList();
    }

    @Override
    public boolean isRecording(UUID sessionId) {
        return producer.sessionById(sessionId).map(ActiveSession::isActive).orElse(false);
    }

    @Override
    public void registerEventType(String eventType, ReplayEventCategory category,
                                  String displayName) {
        eventTypeLabels.put(eventType, displayName);
    }

    public String displayNameOf(String eventType) {
        return eventTypeLabels.getOrDefault(eventType, eventType);
    }

    private final class Handle implements ReplaySessionHandle {

        private final ActiveSession session;

        private Handle(ActiveSession session) {
            this.session = session;
        }

        @Override
        public UUID sessionId() {
            return session.sessionId();
        }

        @Override
        public String name() {
            return session.name();
        }

        @Override
        public String externalKey() {
            return session.externalKey();
        }

        @Override
        public String worldName() {
            return session.worldName();
        }

        @Override
        public long startedAtMillis() {
            return session.startedAtMillis();
        }

        @Override
        public int currentTick() {
            return session.currentTick();
        }

        @Override
        public boolean isActive() {
            return session.isActive();
        }

        @Override
        public void recordEvent(ReplayEvent event) {
            ApiImpl.this.recordEvent(session.sessionId(), event);
        }

        @Override
        public void createBookmark(ReplayBookmark bookmark) {
            ApiImpl.this.createBookmark(session.sessionId(), bookmark);
        }

        @Override
        public void end(ReplaySessionEndReason reason) {
            ApiImpl.this.endSession(session.sessionId(), reason);
        }
    }
}
