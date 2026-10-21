package com.example.store.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;

import lombok.RequiredArgsConstructor;

import org.hibernate.Session;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Optional;

// Native SQL on idempotency_key through the JPA session, so each statement joins the caller's transaction and counts
// in Hibernate's statistics like the statements of the create it accompanies.
@Repository
@RequiredArgsConstructor
public class IdempotencyKeyStore {

    static final String IN_PROGRESS = "IN_PROGRESS";
    static final String COMPLETED = "COMPLETED";

    private static final String CLAIM = "insert into idempotency_key (client_id, idempotency_key, request_fingerprint,"
            + " request_path, status, created_at, expires_at)"
            + " values (:client, :key, :fingerprint, :path, 'IN_PROGRESS', now(), now() + :ttl * interval '1 second')"
            + " on conflict (client_id, idempotency_key) do update set"
            + " request_fingerprint = excluded.request_fingerprint, request_path = excluded.request_path,"
            + " status = excluded.status, response_status = null, response_location = null, response_body = null,"
            + " created_at = excluded.created_at, expires_at = excluded.expires_at"
            + " where idempotency_key.expires_at <= now()"
            + " or (idempotency_key.status = 'IN_PROGRESS'"
            + " and idempotency_key.created_at <= now() - :stale * interval '1 second')";
    private static final String FIND = "select request_fingerprint, status, cast(response_status as integer),"
            + " response_location, response_body from idempotency_key"
            + " where client_id = :client and idempotency_key = :key";
    private static final String COMPLETE = "update idempotency_key set status = 'COMPLETED',"
            + " response_status = :status, response_location = :location, response_body = :body"
            + " where client_id = :client and idempotency_key = :key";
    private static final String RELEASE = "delete from idempotency_key"
            + " where client_id = :client and idempotency_key = :key and status = 'IN_PROGRESS'";
    private static final String PURGE = "delete from idempotency_key where expires_at <= now()";

    private final EntityManager entityManager;

    public int claim(String clientId, String key, String fingerprint, String path, Duration ttl, Duration staleAfter) {
        return session()
                .createNativeMutationQuery(CLAIM)
                .setParameter("client", clientId)
                .setParameter("key", key)
                .setParameter("fingerprint", fingerprint)
                .setParameter("path", path)
                .setParameter("ttl", ttl.toSeconds())
                .setParameter("stale", staleAfter.toSeconds())
                .executeUpdate();
    }

    public Optional<StoredKey> find(String clientId, String key) {
        return session()
                .createNativeQuery(FIND, Tuple.class)
                .setParameter("client", clientId)
                .setParameter("key", key)
                .getResultList()
                .stream()
                .findFirst()
                .map(StoredKey::of);
    }

    public int complete(String clientId, String key, int responseStatus, String responseLocation, String responseBody) {
        return session()
                .createNativeMutationQuery(COMPLETE)
                .setParameter("status", responseStatus)
                .setParameter("location", responseLocation, String.class)
                .setParameter("body", responseBody, String.class)
                .setParameter("client", clientId)
                .setParameter("key", key)
                .executeUpdate();
    }

    public int release(String clientId, String key) {
        return session()
                .createNativeMutationQuery(RELEASE)
                .setParameter("client", clientId)
                .setParameter("key", key)
                .executeUpdate();
    }

    public int purgeExpired() {
        return session().createNativeMutationQuery(PURGE).executeUpdate();
    }

    private Session session() {
        return entityManager.unwrap(Session.class);
    }

    public record StoredKey(
            String fingerprint, String status, Integer responseStatus, String responseLocation, String responseBody) {

        static StoredKey of(Tuple row) {
            return new StoredKey(
                    row.get(0, String.class),
                    row.get(1, String.class),
                    row.get(2, Integer.class),
                    row.get(3, String.class),
                    row.get(4, String.class));
        }

        public boolean completed() {
            return COMPLETED.equals(status);
        }
    }
}
